package com.gpstesttools.ui.pages

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import com.gpstesttools.R
import com.gpstesttools.gps.ChartCache
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.gps.RecordStore
import com.gpstesttools.ui.components.BackupRestoreDialog
import com.gpstesttools.ui.theme.BgBlack
import com.gpstesttools.ui.theme.Dati
import com.gpstesttools.ui.theme.MonitoringOff
import com.gpstesttools.ui.theme.MonitoringPause
import com.gpstesttools.ui.theme.MonitoringRecord
import com.gpstesttools.ui.theme.NavigationColor
import com.gpstesttools.ui.theme.NavigationOld
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 记录状态机：停止 / 暂停 / 记录中（对应原版 GpsService.n() 0/1/3） */
enum class RecordState { STOPPED, PAUSED, RECORDING }

/** 速度上限模式：名称 + 上限(km/h) */
data class SpeedMode(val name: String, val limit: Int)
private val SPEED_MODES = listOf(
    SpeedMode("骑行", 60),
    SpeedMode("汽车", 200),
    SpeedMode("高铁", 500),
    SpeedMode("飞机", 1500),
)

/** ===== 上限自动切换（自动挡）参数 ===== */
private const val AUTO_UP_FRAC = 0.85f       // 速度 ≥ 当前上限 85%
private const val AUTO_UP_HOLD_SEC = 5       // 持续 >5s → 升一档
private const val AUTO_DOWN_FRAC = 0.20f     // 速度 < 当前档位 20%
private const val AUTO_DOWN_HOLD_SEC = 20    // 连续满 20s → 降一档
private const val AUTO_SAMPLE_MS = 1_000L    // 采样周期 = fix 更新周期(1s)

/**
 * 指针平滑时长（ms）：线性插值 0.8s。
 * fix 每 1s 更新一次，动画短于采样周期 → 指针连续走动、显示延迟 < 1 个数据周期；
 * 速度不变时目标不更新 → 动画停摆，零帧零开销（省电）。
 */
private const val NEEDLE_MS = 800f

/**
 * 指针角度自驱平滑（不复用 Compose 动画框架）。
 *
 * 为什么不用 animateFloatAsState / tween：Compose 动画跟随系统"动画程序时长缩放"
 * （ANIMATOR_DURATION_SCALE —— 无障碍里的"移除动画"，以及部分厂商省电策略会把它置 0）。
 * 该值为 0 时动画会在**下一帧直接跳到终点**，指针表现为瞬移、完全没有平滑
 * （A16 / ColorOS 16 实测；同一份代码在 A12 / MIUI 上是平滑的）。
 *
 * 改用 withFrameNanos 按真实经过时间自行做 [NEEDLE_MS] 线性插值，与系统动画设置完全解耦；
 * 目标稳定后协程挂起、不再请求帧 → 保留"速度不变时零帧零功耗"的原设计。
 * 返回值是 State：在 graphicsLayer 的渲染层内读取，动画帧不触发重组、不重绘 Canvas。
 */
@Composable
private fun rememberNeedleAngle(target: Float): State<Float> {
    val angle = remember { mutableFloatStateOf(target) }
    val targetState = rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        // collectLatest：动画途中目标再次变化 → 从当前位置续走，不会回跳
        snapshotFlow { targetState.value }.collectLatest { tgt ->
            val from = angle.floatValue
            if (abs(tgt - from) < 0.01f) {
                angle.floatValue = tgt
                return@collectLatest
            }
            var startNanos = 0L
            while (true) {
                val done = withFrameNanos { now ->
                    if (startNanos == 0L) startNanos = now
                    val t = ((now - startNanos) / 1_000_000f / NEEDLE_MS).coerceIn(0f, 1f)
                    angle.floatValue = from + (tgt - from) * t
                    t >= 1f
                }
                if (done) break
            }
        }
    }
    return angle
}

/** gps_test.ttf 自定义字体（图标字形：T 速度/W 航向/L 计时/D 距离/K 时间/H 海拔） */
private val IconFont = FontFamily(Font(R.font.gps_test))

/** 当前时间 HH:mm:ss（记录页与记录中护屏共用） */
internal fun currentTimeStr(): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

/**
 * 页面三：速度测试 / 导航（半圆仪表盘 + 记录卡片）
 * - 顶部：半圆仪表盘（指针随速度偏转；0-35%绿 / 35-65%橙 / 65%+红；点击切换上限模式）
 * - 行驶距离（整行单卡）
 * - 平均速度（左, 1位小数 km/h）| 最大速度（右）
 * - 当前时间（左）| 记录时间（右）
 * - 开始 / 暂停 / 停止
 * 记录会话状态由 MainActivity 提升保存，切页/滑动不丢。
 */
@Composable
fun NavigationPage(
    recordState: RecordState,
    elapsedMs: Long,
    distanceM: Float,
    maxSpeedMs: Float,
    lastFixTime: Long,
    speedLimit: Int,
    onSpeedLimit: (Int) -> Unit,
    onRecordState: (RecordState) -> Unit,
    onElapsed: (Long) -> Unit,
    onDistance: (Float) -> Unit,
    onMaxSpeed: (Float) -> Unit,
    onLastFixTime: (Long) -> Unit,
    /** 单次记录达到 12 小时上限：自动保存记录并退出应用（MainActivity 实现） */
    onRecordingLimitReached: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fix by GpsEngine.fix.collectAsState()
    val context = LocalContext.current

    val showLimitDialog = remember { mutableStateOf(false) }
    val showHistory = remember { mutableStateOf(false) }   // 历史记录悬浮框开关（T4 渲染）
    val detailMeta = remember { mutableStateOf<RecordStore.RecordMeta?>(null) }  // 点历史条目打开的详情弹窗
    val nowStr = remember { mutableStateOf(currentTimeStr()) }

    val currentDistance by rememberUpdatedState(distanceM)
    val currentMax by rememberUpdatedState(maxSpeedMs)
    val currentElapsed by rememberUpdatedState(elapsedMs)
    val currentLastFix by rememberUpdatedState(lastFixTime)

    // 记录中：每秒 tick 累计时间 + 距离积分 + 最大速度 + 写一条速度到 csv
    // 循环条件额外检查 RecordStore.isRecording()：用户点"停止"（或 12h 上限保存退出、
    // 系统清理信号等任何路径 finish 会话）后，即使 Compose 重组被后台节流延迟，
    // tick 也会在下一轮检测到会话已结束并退出，不会残留累计时间
    //（否则回前台会看到"继续记录"而非干净的"开始记录"）。
    //
    // 累加改为**本地变量**而非依赖 rememberUpdatedState：记录中允许长时间后台运行
    // （见 MainActivity 后台豁免策略），Compose 后台重组被节流时 state 回读可能滞后，
    // 用旧值回写会丢失累计；本地累加器不受重组节流影响，后台计时/里程同样准确。
    LaunchedEffect(recordState) {
        if (recordState == RecordState.RECORDING) {
            var elapsed = currentElapsed
            var dist = currentDistance
            var maxV = currentMax
            var lastFix = currentLastFix
            while (isActive && RecordStore.isRecording()) {
                delay(1000)
                val f = fix
                if (f != null && lastFix > 0) {
                    val dt = (f.time - lastFix) / 1000f
                    if (dt >= 0f && dt <= 5f) {
                        dist += f.speed * dt
                        maxV = max(maxV, f.speed)
                    }
                }
                lastFix = f?.time ?: lastFix
                elapsed += 1000
                onDistance(dist)
                onMaxSpeed(maxV)
                onLastFixTime(lastFix)
                onElapsed(elapsed)
                // csv：每 1s 一条（序号,速度 m/s）；fix 为空记 0
                RecordStore.append(f?.speed ?: 0f)
                // 单次记录 12 小时上限：自动保存记录并退出应用
                if (elapsed >= RecordStore.MAX_RECORDING_MS) {
                    onRecordingLimitReached()
                    break
                }
            }
        }
    }

    // 时钟每秒刷新（独立于记录状态，保证当前时间实时走动）
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            nowStr.value = currentTimeStr()
        }
    }

    // ===== 上限自动切换（自动挡）：按速度自动升降档，替代手选上限 =====
    // 每秒采样一次（与 fix 周期一致）。rememberUpdatedState 取最新值 → effect 不随参数重启。
    //  - 速度 ≥ 当前上限 85% 且持续 >5s  → 升一档（钳制到最高档）
    //  - 速度 <  当前档位 20% 且连续满 20s → 降一档（钳制到最低档）
    // 无 fix 按 0 计 → 静止/丢星超过 20s 自然回到最低档；切走页签 effect 取消、计时归零。
    val currentFix by rememberUpdatedState(fix)
    val currentLimit by rememberUpdatedState(speedLimit)
    LaunchedEffect(Unit) {
        var upHold = 0   // 达到 85% 上限的连续秒数
        var downHold = 0 // 低于 20% 档位的连续秒数
        while (isActive) {
            delay(AUTO_SAMPLE_MS)
            val sp = (currentFix?.speed ?: 0f) * 3.6f // km/h
            val lim = currentLimit.toFloat()
            if (lim <= 0f) continue
            upHold = if (sp >= AUTO_UP_FRAC * lim) upHold + 1 else 0
            downHold = if (sp < AUTO_DOWN_FRAC * lim) downHold + 1 else 0
            when {
                upHold > AUTO_UP_HOLD_SEC -> { // 持续超过 5s → 升档
                    val next = shiftSpeedMode(currentLimit, +1)
                    if (next != currentLimit) onSpeedLimit(next)
                    upHold = 0; downHold = 0
                }
                downHold >= AUTO_DOWN_HOLD_SEC -> { // 连续满 20s → 降档
                    val next = shiftSpeedMode(currentLimit, -1)
                    if (next != currentLimit) onSpeedLimit(next)
                    upHold = 0; downHold = 0
                }
            }
        }
    }

    val speedKmh = fix?.let { it.speed * 3.6f } ?: 0f
    val avgKmh = if (elapsedMs > 0 && distanceM > 0) (distanceM / (elapsedMs / 1000f)) * 3.6f else 0f
    val maxKmh = maxSpeedMs * 3.6f
    val elapsedStr = formatElapsed(elapsedMs)
    // 指针/速度颜色：按原区间分段（f<0.35 绿 / <0.65 橙 / 否则红）
    val speedF = speedFrac(speedKmh, speedLimit.toFloat())
    val speedColor = needleColorAt(speedF)

    val recording = recordState == RecordState.RECORDING
    val valueColor = if (recording) NavigationOld else NavigationColor

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 半圆仪表盘（点击切换上限）
        // 整页唯一的弹性件：导航栏占掉 47dp 时由它缩，保证底部按钮行不被裁字
        SpeedGauge(
            speedKmh = speedKmh,
            limit = speedLimit,
            onClick = { showLimitDialog.value = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // 当前速度（图表下方独立一行，颜色跟随指针渐变）
        // 整数 44sp + 小数 22sp（半高）。小数用 alignByBaseline 与整数共享同一条基线：
        // 不能用 Alignment.Bottom（Box 底部对齐的是含 descent 的盒底，44sp 与 22sp 的
        // descent 不同，会导致小数数字底比整数数字底低约 4sp，变成"下标"而非正排 63.4）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "%.0f".format(speedKmh),
                color = speedColor,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                // 与 "%.1f" 整体四舍五入保持一致：直接从同源字符串拆分，
                // 避免 63.96 这类值出现整数位进位成 64、小数位仍是 .9 的错位
                text = "." + "%.1f".format(Locale.US, speedKmh).substringAfter('.'),
                color = speedColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = "km/h",
                color = Color(0xFFAAAAAA),
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
            )
        }

        // 行驶距离（整行单卡）+ 上方外侧"当前上限"（点击切换，完全在卡片框外，更远离）
        Box(Modifier.fillMaxWidth()) {
            StatCard(
                title = "行驶距离",
                value = formatDistance(distanceM),
                unit = distanceUnit(distanceM),
                color = valueColor,
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            )
            // offset 上移到卡片框外（负 padding 会崩：PaddingValues 强制非负）
            Text(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-12).dp)
                    .padding(top = 4.dp, end = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showLimitDialog.value = true }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                text = "当前上限 $speedLimit km/h",
                color = Color(0xFFBBBBBB),
                fontSize = 11.sp,
            )
        }

        // 平均速度（左）| 最大速度（右）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "平均速度",
                value = "%.1f".format(Locale.US, avgKmh),
                unit = "km/h",
                color = valueColor,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "最大速度",
                value = formatNum(maxKmh),
                unit = "km/h",
                color = valueColor,
                modifier = Modifier.weight(1f),
            )
        }

        // 当前时间（左）| 记录时间（右）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "当前时间",
                value = nowStr.value,
                unit = "",
                color = valueColor,
                valueSize = 26.sp,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "记录时间",
                value = elapsedStr,
                unit = "",
                color = valueColor,
                valueSize = 26.sp,
                modifier = Modifier.weight(1f),
            )
        }

        // 播放器风格控制条：主按钮（▶/⏸ 切换）占满左侧 + 右侧二态按钮
        // （未记录 = "历史记录"；记录中 = "停止"）
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 主按钮：状态切换（已有记录时长时显示"继续记录"，停止清零后恢复"开始记录"）
            MediaButton(
                icon = if (recording) "⏸" else "▶",
                text = when {
                    recording -> "暂停记录"
                    elapsedMs > 0 -> "继续记录"
                    else -> "开始记录"
                },
                color = if (recording) MonitoringPause else MonitoringRecord,
                modifier = Modifier.weight(1f),
            ) {
                when (recordState) {
                    RecordState.STOPPED -> {
                        onElapsed(0); onDistance(0f); onMaxSpeed(0f); onLastFixTime(fix?.time ?: 0L)
                        RecordStore.begin(context, System.currentTimeMillis())  // 建 csv 文件
                        onRecordState(RecordState.RECORDING)
                    }
                    RecordState.PAUSED -> onRecordState(RecordState.RECORDING)
                    RecordState.RECORDING -> {
                        // 暂停：先把缓冲落盘。会话保持打开（可继续记录），
                        // 若此后长时间挂起且进程被系统回收，尾部数据已落盘不会丢
                        RecordStore.flushBuffered()
                        onRecordState(RecordState.PAUSED)
                    }
                }
            }
            // 右按钮：STOPPED → 历史记录；记录中（RECORDING/PAUSED）→ 停止（保存）
            if (recordState == RecordState.STOPPED) {
                MediaButton(
                    icon = "📂",
                    text = "历史记录",
                    color = MonitoringPause,
                    small = true,
                    modifier = Modifier.width(110.dp),
                ) {
                    showHistory.value = true
                }
            } else {
                MediaButton(
                    icon = "■",
                    text = "停止",
                    color = MonitoringRecord,
                    small = true,
                    enabled = recordState != RecordState.STOPPED,
                    modifier = Modifier.width(96.dp),
                ) {
                    // 停止记录并保存 csv（未开始记录时 finish 为 no-op）
                    RecordStore.finish(System.currentTimeMillis())
                    onRecordState(RecordState.STOPPED)
                    onElapsed(0); onDistance(0f); onMaxSpeed(0f)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    if (showLimitDialog.value) {
        LimitDialog(
            currentLimit = speedLimit,
            onSelect = { onSpeedLimit(it); showLimitDialog.value = false },
            onClose = { showLimitDialog.value = false },
        )
    }

    if (showHistory.value) {
        HistoryDialog(
            context = context,
            onClose = { showHistory.value = false },
            onItemClick = { meta ->
                showHistory.value = false
                detailMeta.value = meta
            },
        )
    }

    detailMeta.value?.let { meta ->
        DetailDialog(
            context = context,
            meta = meta,
            onClose = {
                detailMeta.value = null
                showHistory.value = true   // 关闭详情回到历史列表
            },
        )
    }
}

/**
 * 半圆仪表盘（汽车速度表样式），分两层绘制：
 *  - 静态表盘 [GaugeDial]：背景轨道 + 弧带渐变（0-35%绿 / 35-65%橙 / 65-100%红）、
 *    主刻度每 10% + 次刻度每 5%、数值标签每 20%（0 → 上限）。只依赖 limit，指针动画不触发重绘。
 *  - 指针层 [GaugeNeedle]：中心圆 + 杆 + 三角形箭头。旋转用 graphicsLayer.rotationZ——
 *    动画期间仅更新渲染层矩阵、不重绘 Canvas 内容（最低开销/最省电）；
 *    指针静止时动画停摆，零帧零开销。
 *  - 指针角度 = 180° + f*180°（f=速度/上限，钳制 0..1）；0.8s 平滑，显示延迟 < 1 数据周期(1s)。
 *  - 点击切换上限（当前上限文本位于"行驶距离"卡右上外侧）。
 */
@Composable
internal fun SpeedGauge(
    speedKmh: Float,
    limit: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22000000))
            .clickable(onClick = onClick),
    ) {
        GaugeDial(limit = limit, modifier = Modifier.fillMaxSize())
        GaugeNeedle(frac = speedFrac(speedKmh, limit.toFloat()), modifier = Modifier.fillMaxSize())
    }
}

/** 表盘几何（px）。刻度盘与指针层共用，保证同心、同半径 */
private data class GaugeGeo(val cx: Float, val cy: Float, val r: Float, val stroke: Float) {
    /** 弧带内缘半径（刻度起点 / 指针长度基准） */
    val rIn: Float get() = r - stroke / 2f
}

private fun DrawScope.gaugeGeo(): GaugeGeo {
    val w = size.width
    val h = size.height
    return GaugeGeo(
        cx = w / 2f,
        cy = h * 0.88f,
        r = min(w / 2f, h * 0.88f) - 12.dp.toPx(),
        stroke = 16.dp.toPx(),
    )
}

/** 静态表盘层：轨道 + 弧带渐变 + 刻度 + 数值标签。仅参数 limit 变化时才重绘 */
@Composable
private fun GaugeDial(limit: Int, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val g = gaugeGeo()
        val topLeft = Offset(g.cx - g.r, g.cy - g.r)
        val arcSize = Size(g.r * 2, g.r * 2)
        val rIn = g.rIn

        // 背景轨道
        drawArc(
            color = Color(0x33333333),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = g.stroke, cap = StrokeCap.Round),
        )
        // 弧带渐变：按原区间分界（0-35% 绿 / 35-65% 橙 / 65-100% 红）平滑过渡
        // sweepGradient 0° 在 +x 方向；0°-180° 用背景色（弧带外），180°-360° 是弧带本体
        val arcBrush = Brush.sweepGradient(
            *arrayOf(
                0.0f to Color(0x33333333),
                0.5f to Color(0xFF00E500),   // 180° 弧起点：绿
                0.675f to Color(0xFFFF8C00), // 243° (35%)：绿→橙过渡中点
                0.825f to Color(0xFFFF4500), // 297° (65%)：橙→红过渡中点
                1.0f to Color(0xFFFF0000),   // 360° 弧终点：红
            ),
            center = Offset(g.cx, g.cy),
        )
        drawArc(
            brush = arcBrush,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = g.stroke, cap = StrokeCap.Round),
        )

        // 刻度：主刻度每 10%，次刻度每 5%（从弧带内缘向内延伸）
        val tickColor = Color(0xFFCCCCCC)
        for (k in 0..20) {
            val frac = k / 20f
            val angRad = Math.toRadians((180.0 + frac * 180.0))
            val major = k % 2 == 0
            val rTick = rIn - (if (major) 12.dp.toPx() else 6.dp.toPx())
            val x1 = g.cx + rIn * cos(angRad).toFloat()
            val y1 = g.cy + rIn * sin(angRad).toFloat()
            val x2 = g.cx + rTick * cos(angRad).toFloat()
            val y2 = g.cy + rTick * sin(angRad).toFloat()
            drawLine(
                color = tickColor,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }

        // 数值标签：每 20% 一个（0 → 上限），置于主刻度内侧
        val labelStyle = TextStyle(color = Color(0xFFBBBBBB), fontSize = 11.sp)
        val labelR = rIn - 12.dp.toPx() - 15.dp.toPx()
        for (k in 0..10 step 2) {
            val frac = k / 10f
            val angRad = Math.toRadians((180.0 + frac * 180.0))
            val text = (limit * frac).roundToInt().toString()
            val layout = textMeasurer.measure(AnnotatedString(text), labelStyle)
            val lx = g.cx + labelR * cos(angRad).toFloat() - layout.size.width / 2f
            val ly = g.cy + labelR * sin(angRad).toFloat() - layout.size.height / 2f
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                topLeft = Offset(lx, ly),
                style = labelStyle,
            )
        }
    }
}

/**
 * 指针层：内容（中心圆 + 杆 + 箭头）只画一次；指向变化只改 graphicsLayer.rotationZ。
 * 基准朝向 +x（右），旋转到 180°+f*180° 即与原实现一致；轴心 = 表盘圆心 (w/2, 0.88h)。
 */
@Composable
private fun GaugeNeedle(frac: Float, modifier: Modifier = Modifier) {
    val needleColor = needleColorAt(frac)
    val angle = rememberNeedleAngle(180f + frac.coerceIn(0f, 1f) * 180f)
    // 静态内容记住为 Modifier：仅当颜色档位变化（或密度/尺寸体系变化）才重建重绘，
    // 动画帧期间引用不变 → 只有 graphicsLayer 属性被更新，Canvas 内容零重绘。
    val drawMod = remember(needleColor) {
        Modifier.drawBehind {
            val g = gaugeGeo()
            val rN = g.rIn
            val cx = g.cx
            val cy = g.cy
            val tipX = cx + rN     // 基线指向 +x
            val tipY = cy
            val triLen = 24.dp.toPx()
            val halfW = 7.dp.toPx()
            val bx = cx + (rN - triLen) // 三角底边中点
            drawLine(
                color = needleColor,
                start = Offset(cx, cy),
                end = Offset(bx, tipY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            val triPath = Path().apply {
                moveTo(tipX, tipY)
                lineTo(bx, cy - halfW)
                lineTo(bx, cy + halfW)
                close()
            }
            drawPath(path = triPath, color = needleColor)
            drawCircle(color = needleColor, radius = 7.dp.toPx(), center = Offset(cx, cy))
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                // 在渲染层内读取动画 State：每帧只更新图层矩阵，不触发重组/重绘
                rotationZ = angle.value
                // 旋转轴 = 表盘圆心 (w/2, 0.88h)，比例固定 → 任意尺寸下都准确
                transformOrigin = TransformOrigin(0.5f, 0.88f)
            }
            .then(drawMod),
    )
}

/**
 * 指标卡片：标题在上，数值大字（单位小字置于数值右下角）。
 * valueSize 可自定义数值字号（默认 34sp）；数值强制单行不换行。
 */
@Composable
private fun StatCard(
    title: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 34.sp,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = title, fontSize = 13.sp, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = Color(0xFF888888),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/** 上限选择弹窗：4 个模式按钮（选中绿色背景）+ 底部红色关闭按钮 */
@Composable
private fun LimitDialog(
    currentLimit: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择速度上限", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            SPEED_MODES.forEach { mode ->
                val selected = mode.limit == currentLimit
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFF00C853) else Color(0x33FFFFFF))
                        .clickable { onSelect(mode.limit) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${mode.name} (${mode.limit})",
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF0000))
                    .clickable(onClick = onClose)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("关闭", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 历史记录悬浮框（大 Dialog）：
 * 序列号(最新=1) + 开始时间 + 累计记录时间 + 行驶里程 + 平均速度 + 最大速度
 * 每行右侧有删除（垃圾桶）按钮，点击弹确认框，确认后删除对应 csv 并刷新列表
 */
@Composable
private fun HistoryDialog(
    context: Context,
    onClose: () -> Unit,
    onItemClick: (RecordStore.RecordMeta) -> Unit,
) {
    val recordsState = remember { mutableStateOf(RecordStore.list(context)) }
    val pendingDelete = remember { mutableStateOf<Pair<Int, RecordStore.RecordMeta>?>(null) }
    val showBackupRestore = remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .height(520.dp)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "历史记录",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // 备份恢复入口（蓝色，位于关闭按钮左边）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1976D2))
                        .clickable { showBackupRestore.value = true }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("备份恢复", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF0000))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("关闭", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (recordsState.value.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无记录", color = Color(0xFF888888), fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(recordsState.value) { index, meta ->
                        HistoryRow(
                            seq = index + 1,
                            meta = meta,
                            onClick = { onItemClick(meta) },
                            onDelete = { pendingDelete.value = (index + 1) to meta },
                        )
                    }
                }
            }
        }
    }
    if (showBackupRestore.value) {
        BackupRestoreDialog(
            context = context,
            onClose = { showBackupRestore.value = false },
            onChanged = { recordsState.value = RecordStore.list(context) },  // 还原后刷新列表
        )
    }
    if (pendingDelete.value != null) {
        ConfirmDeleteDialog(
            seq = pendingDelete.value!!.first,
            meta = pendingDelete.value!!.second,
            onConfirm = {
                RecordStore.delete(context, pendingDelete.value!!.second.fileName)
                recordsState.value = recordsState.value.filter { it.fileName != pendingDelete.value!!.second.fileName }
                pendingDelete.value = null
            },
            onDismiss = { pendingDelete.value = null },
        )
    }
}

/** 历史记录单行：左侧序列号 + 中间信息（开始时间/累计/里程/平均/最大，均带中文标签，点击打开详情）+ 右侧删除（垃圾桶）按钮 */@Composable
private fun HistoryRow(seq: Int, meta: RecordStore.RecordMeta, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22FFFFFF)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 中间内容（可点击打开详情）
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 序列号：字号略增大；在"表项左缘 ↔ 右侧信息文本"之间水平居中，不贴边显示
            Text(
                text = "$seq",
                color = Color(0xFF00C853),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(46.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${fmtStartTime(meta.startTimeMs)}   ${fmtDuration(meta.count)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                // 三个带中文标签的指标（行驶里程 / 平均速度 / 最大速度）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LabeledStat("行驶里程", "%.1f".format(Locale.US, meta.distanceM / 1000f), "km")
                    LabeledStat("平均速度", "%.1f".format(Locale.US, meta.avgSpeedMs * 3.6f), "km/h")
                    LabeledStat("最大速度", "%.1f".format(Locale.US, meta.maxSpeedMs * 3.6f), "km/h")
                }
            }
        }
        // 右侧删除（emoji 垃圾桶）— IconButton 提供 48dp 自适应最小触达区（density 自适应，跨设备一致）
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp),
        ) {
            Text("🗑️", fontSize = 20.sp)
        }
    }
}


/**
 * 确认删除弹窗：中间照搬待删除历史条目的内容（序列号 + 开始时间 + 记录时长 + 三项指标），
 * 下方提示"是否确认删除这条记录？"，并提供 取消 / 删除 按钮。
 */
@Composable
private fun ConfirmDeleteDialog(
    seq: Int,
    meta: RecordStore.RecordMeta,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Text("确认删除", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            // 中间：照搬该条记录显示的内容
            Text("#$seq", color = Color(0xFF00C853), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("开始时间  ${fmtStartTime(meta.startTimeMs)}", color = Dati, fontSize = 13.sp)
            Text("记录时长  ${fmtDuration(meta.count)}", color = Dati, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LabeledStat("行驶里程", "%.1f".format(Locale.US, meta.distanceM / 1000f), "km")
                LabeledStat("平均速度", "%.1f".format(Locale.US, meta.avgSpeedMs * 3.6f), "km/h")
                LabeledStat("最大速度", "%.1f".format(Locale.US, meta.maxSpeedMs * 3.6f), "km/h")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "是否确认删除这条记录？",
                color = Color(0xFFFF5555),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF0000))
                        .clickable(onClick = onConfirm)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("删除", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 带中文标签的小指标（标签在上、数值+单位在下，居中对齐） */
@Composable
private fun LabeledStat(
    label: String,
    value: String,
    unit: String,
    labelSize: TextUnit = 11.sp,
    valueSize: TextUnit = 14.sp,
    unitSize: TextUnit = 10.sp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF888888), fontSize = labelSize)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, color = Color.White, fontSize = valueSize, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = Color(0xFF888888),
                    fontSize = unitSize,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** 开始时间显示：yy-MM-dd HH:mm（如 26-09-01 16:35） */
private fun fmtStartTime(ms: Long): String =
    SimpleDateFormat("yy-MM-dd HH:mm", Locale.US).format(Date(ms))

/** 累计记录时间：按数据条数（每 1s 一条）→ x小时x分x秒（不足 1 小时省略"小时"） */
private fun fmtDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}小时${m}分${s}秒" else "${m}分${s}秒"
}

/** 完整日期时间显示：yy-MM-dd HH:mm:ss（详情页用） */
private fun fmtFullTime(ms: Long): String =
    SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

/** 横轴时间标签：秒 → m:ss（不足 1 分钟显示 x秒） */
private fun fmtClock(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "%d:%02d".format(m, s) else "%ds".format(s)
}

/**
 * 详细记录弹窗：
 *  - 顶部：开始时间 / 结束时间 / 记录时长（各一行）+ 行驶里程/平均速度/最大速度（中文标签）
 *  - 下方：速度曲线（Canvas 绘制，纵轴=速度 km/h，横轴=时间；纵轴上限取数据最大值外推 10% 取整，避免线顶到顶部）
 *
 * 曲线数据来自 [ChartCache]（EMA 平滑 + 按绘图区宽度重采样的图表专用缓存 csv），
 * 不再是 1Hz 原始序列 —— 原始序列在 967px 宽的绘图区里是 1.7 样本/像素，逐点连线会糊成毛刺带。
 * 顶部三项统计仍走 [RecordStore.RecordMeta]（原始数据口径），与历史列表数字保持一致。
 */
@Composable
private fun DetailDialog(
    context: Context,
    meta: RecordStore.RecordMeta,
    onClose: () -> Unit,
) {
    val series = remember(meta.fileName) { ChartCache.load(context, meta.fileName) }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .heightIn(min = 440.dp, max = 600.dp)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "详细记录",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF0000))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("关闭", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("开始时间  ${fmtFullTime(meta.startTimeMs)}", color = Dati, fontSize = 12.sp)
            Text("结束时间  ${fmtFullTime(meta.stopTimeMs)}", color = Dati, fontSize = 12.sp)
            Text("记录时长  ${fmtDuration(meta.count)}", color = Dati, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LabeledStat("行驶里程", "%.1f".format(Locale.US, meta.distanceM / 1000f), "km", valueSize = 16.sp, unitSize = 11.sp)
                LabeledStat("平均速度", "%.1f".format(Locale.US, meta.avgSpeedMs * 3.6f), "km/h", valueSize = 16.sp, unitSize = 11.sp)
                LabeledStat("最大速度", "%.1f".format(Locale.US, meta.maxSpeedMs * 3.6f), "km/h", valueSize = 16.sp, unitSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x11000000)),
            ) {
                SpeedChart(
                    series = series,
                    totalSec = meta.count,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 速度曲线（Canvas 手绘）：
 *  - 纵轴 = 速度 km/h；横轴 = 时间（[series] 为图表缓存的重采样点，均匀铺满 [totalSec] 秒）
 *  - 纵轴上限 yMax = 数据最大值 * 1.1 再向上取整到整数/整十，留出顶部余量，绘图线不顶到表顶
 *  - 填充区域渐变 + 折线；无数据时居中提示"无数据"
 */
@Composable
private fun SpeedChart(
    series: List<Float>,
    totalSec: Int,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padL = 42.dp.toPx()
        val padR = 10.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 24.dp.toPx()
        val plotW = (w - padL - padR).coerceAtLeast(1f)
        val plotH = (h - padT - padB).coerceAtLeast(1f)

        val maxV = series.maxOrNull() ?: 0f
        // 纵轴上限：外推 10% 后取整，避免折线顶到表格顶部
        val yMax = when {
            maxV <= 0f -> 10f
            maxV <= 20f -> kotlin.math.ceil((maxV * 1.1f).toDouble()).toFloat()
            maxV <= 100f -> kotlin.math.ceil((maxV * 1.1f) / 10.0).toFloat() * 10f
            maxV <= 300f -> kotlin.math.ceil((maxV * 1.1f) / 20.0).toFloat() * 20f
            else -> kotlin.math.ceil((maxV * 1.1f) / 50.0).toFloat() * 50f
        }

        val labelColor = Color(0xFF888888)
        val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)
        // 低对比度灰色网格（不密集）
        val gridColor = Color(0x2C2C2C)

        // 横向网格线 + 纵轴速度刻度（低对比度灰）
        val yticks = 4
        for (t in 0..yticks) {
            val frac = t.toFloat() / yticks
            val yv = yMax * frac
            val y = padT + plotH * (1f - frac)
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1.dp.toPx())
            val txt = "%.0f".format(yv)
            val tl = textMeasurer.measure(AnnotatedString(txt), labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = txt,
                topLeft = Offset(padL - tl.size.width - 5.dp.toPx(), y - tl.size.height / 2f),
                style = labelStyle,
            )
        }

        // 顶部刻度（最大速度）下方标注 km/h 作为纵轴单位
        val kmhTl = textMeasurer.measure(AnnotatedString("km/h"), labelStyle)
        drawText(
            textMeasurer = textMeasurer,
            text = "km/h",
            topLeft = Offset(padL - kmhTl.size.width - 5.dp.toPx(), padT + 14.dp.toPx()),
            style = labelStyle,
        )

        // 纵向网格线（时间轴，分 4 段，低密度）
        val vGridN = 4
        for (t in 0..vGridN) {
            val x = padL + plotW * (t.toFloat() / vGridN)
            drawLine(gridColor, Offset(x, padT), Offset(x, padT + plotH), strokeWidth = 1.dp.toPx())
        }

        val n = series.size
        if (n == 0) {
            val t = "无数据"
            val tl = textMeasurer.measure(AnnotatedString(t), TextStyle(color = labelColor, fontSize = 13.sp))
            drawText(
                textMeasurer = textMeasurer,
                text = t,
                topLeft = Offset(w / 2 - tl.size.width / 2, h / 2 - tl.size.height / 2),
                style = TextStyle(color = labelColor, fontSize = 13.sp),
            )
            return@Canvas
        }

        val xAt: (Int) -> Float = { i -> padL + plotW * if (n == 1) 0.5f else (i.toFloat() / (n - 1)) }
        val yAt: (Float) -> Float = { v -> padT + plotH * (1f - (v / yMax).coerceIn(0f, 1f)) }

        if (n > 1) {
            val linePath = Path()
            val fillPath = Path()
            for (i in series.indices) {
                val x = xAt(i)
                val y = yAt(series[i])
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, padT + plotH)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(xAt(n - 1), padT + plotH)
            fillPath.close()
            drawPath(
                fillPath,
                Brush.verticalGradient(
                    0f to Color(0xFF00C853).copy(alpha = 0.35f),
                    1f to Color(0xFF00C853).copy(alpha = 0.02f),
                    startY = padT,
                    endY = padT + plotH,
                ),
            )
            drawPath(
                linePath,
                Color(0xFF00E500),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        } else {
            drawCircle(
                Color(0xFF00E500),
                radius = 4.dp.toPx(),
                center = Offset(xAt(0), yAt(series[0])),
            )
        }

        // 横轴时间标签（开始 / 中点 / 结束）：缓存点是等时间重采样，按 totalSec 反算真实秒数
        val span = if (totalSec > 0) totalSec else n
        val xticks = listOf(0, (n - 1) / 2, n - 1).distinct()
        for (i in xticks) {
            val x = xAt(i)
            val lbl = fmtClock(if (n <= 1) 0 else Math.round(span.toFloat() * i / (n - 1)))
            val tl = textMeasurer.measure(AnnotatedString(lbl), labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = lbl,
                topLeft = Offset(
                    (x - tl.size.width / 2f).coerceIn(0f, w - tl.size.width),
                    padT + plotH + 5.dp.toPx(),
                ),
                style = labelStyle,
            )
        }
    }
}

/** 播放器风格按钮：图标 + 文本（主按钮大号 / 停止按钮小号） */
@Composable
private fun MediaButton(
    icon: String,
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val vertPad = if (small) 8.dp else 12.dp
    val iconSize = if (small) 13.sp else 18.sp
    val textSize = if (small) 14.sp else 17.sp
    val gap = if (small) 5.dp else 8.dp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Color(0x33FFFFFF) else Color(0x11000000))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = vertPad),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                fontSize = iconSize,
                fontWeight = FontWeight.Bold,
                color = if (enabled) color else Color(0xFF666666),
            )
            if (text.isNotEmpty()) {
                Spacer(Modifier.width(gap))
                Text(
                    text = text,
                    fontSize = textSize,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) color else Color(0xFF666666),
                )
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

private fun formatNum(v: Float): String = if (v > 999f) "%.0f".format(v) else "%.1f".format(v)

private fun formatDistance(m: Float): String =
    if (m >= 1000f) "%.2f".format(m / 1000f) else "%.0f".format(m)

private fun distanceUnit(m: Float): String = if (m >= 1000f) "km" else "m"

/** 速度比例：speed/limit，钳制 0..1 */
internal fun speedFrac(speedKmh: Float, limit: Float): Float =
    (speedKmh / limit).coerceIn(0f, 1f)

/** 分段色（原区间逻辑）：f<0.35 绿 / f<0.65 橙 / 否则红 */
internal fun needleColorAt(f: Float): Color = when {
    f < 0.35f -> Color(0xFF00E500)
    f < 0.65f -> Color(0xFFFF8C00)
    else -> Color(0xFFFF0000)
}

/**
 * 在 SPEED_MODES 档位表中平移一档（dir=±1）。
 * 自动切换钳制在最低/最高档之间（当前表 = 骑行60 … 飞机1500），越界保持原档。
 */
private fun shiftSpeedMode(currentLimit: Int, dir: Int): Int {
    val idx = SPEED_MODES.indexOfFirst { it.limit == currentLimit }
    if (idx < 0) return currentLimit // 未知上限值（防御）不做改动
    return SPEED_MODES[(idx + dir).coerceIn(0, SPEED_MODES.lastIndex)].limit
}
