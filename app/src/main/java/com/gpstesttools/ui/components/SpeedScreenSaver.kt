package com.gpstesttools.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.ui.pages.SpeedGauge
import com.gpstesttools.ui.pages.currentTimeStr
import com.gpstesttools.ui.pages.needleColorAt
import com.gpstesttools.ui.pages.speedFrac
import com.gpstesttools.ui.theme.BgBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * ===== 记录中"护屏"（防烧屏）参数 =====
 */

/** 无触摸多久后进入护屏（需求 30s） */
internal const val IDLE_ENTER_MS = 30_000L

/** 护屏内速度块整体上下随机漂移的间隔（需求 30s，避免烧屏） */
private const val DRIFT_INTERVAL_MS = 30_000L

/**
 * 屏幕上下边缘最小留白（**物理像素**，需求：≥20 像素）。
 * 注意不能写成 dp：本页在 ScreenScaling 下密度被整体缩放，dp 会随设备变化；
 * 漂移换算全程用 px（布局期 constraints 与实测块高都是 px），
 * 保证任何设备上距屏幕边缘都真的 ≥20 物理像素。
 */
private const val EDGE_MARGIN_PX = 20

/** 漂移平滑时长（ms）：用真实帧时间自行插值，不受系统"动画程序时长缩放"影响 */
private const val DRIFT_ANIM_MS = 1_400f

/** 无触摸检测轮询周期（ms）：越小进入/退出越灵敏，越小越耗电（400ms 足够） */
internal const val TOUCH_POLL_MS = 400L

/**
 * 记录中护屏界面（防烧屏 / 免打扰）。
 *
 * 触发条件：记录中（RECORDING）且连续 [IDLE_ENTER_MS] 无任何触摸动作 —— 由 MainActivity 判定，
 * 判定成立时把本组件盖在整个界面之上。
 *
 * 界面内容（只保留速度相关信息）：
 *  - 半圆速度图表 [SpeedGauge] + 放大的速度数值（120sp 级，随速度值区间变色）作为一个整体；
 *  - 其下留一段间距显示当前时间（HH:mm:ss，每秒走动）；
 *  - 三者同属一个整体块，每 [DRIFT_INTERVAL_MS] 随机选一个新的纵向**比例**位置（0..1），
 *    用真实帧时间平滑滑过去（1.4s）。位置在布局期由真实测量尺寸换算成像素，
 *    上下边距恒 ≥ [EDGE_MARGIN_PX] 物理像素 —— 比例式自适应，任何分辨率/密度的设备
 *    都不需要单独适配（旧实现用 onSizeChanged 回写 px 状态再 offset，测量与摆放
 *    跨帧不同步，部分设备（如 a16/ColorOS 隐藏系统栏前后）会拿到过期块高，
 *    内容被漂移出屏幕外）。
 *
 * 全屏：进入本组件时隐藏系统状态栏 + 导航栏（immersive），退出时恢复。
 *
 * 触摸退出：本组件吃掉所有触摸事件（避免误触到下面的"暂停/停止"按钮），
 * 退出判定由 MainActivity 的全局触摸时间戳统一处理 —— 轻触即退出护屏，计时重新开始。
 *
 * 记录不受影响：本组件是叠加层，下面的记录页仍在组合/重组中，CSV 采样照常进行。
 */
@Composable
fun SpeedScreenSaver(
    speedLimit: Int,
    modifier: Modifier = Modifier,
) {
    val fix by GpsEngine.fix.collectAsState()
    val speedKmh = fix?.let { it.speed * 3.6f } ?: 0f
    val speedColor = needleColorAt(speedFrac(speedKmh, speedLimit.toFloat()))

    // ===== 隐藏系统状态栏/导航栏（全屏，不显示通知栏）=====
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevBehavior = controller?.systemBarsBehavior
        if (controller != null) {
            // 上滑临时呼出后自动再隐藏，防止状态栏长期驻留
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                if (prevBehavior != null) controller.systemBarsBehavior = prevBehavior
            }
        }
    }

    // ===== 时钟：每秒刷新（时间也是静态像素，跟着整体一起漂移才不烧屏）=====
    var clock by remember { mutableStateOf(currentTimeStr()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            clock = currentTimeStr()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BgBlack)
            // 吃掉所有触摸：护屏期间不响应任何下层按钮；"触摸退出"由 MainActivity 的
            // 全局 Initial 通道时间戳处理（父节点先收到 Initial，本消费不影响它）
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        // ===== 漂移位置 = 屏高比例（0..1），布局期换算成像素 =====
        // 只存比例、不存 px：px 在同一次布局里由真实测量高度算出并钳制，
        // 不存在"过期尺寸"，任何设备都不会把内容推出屏幕
        var targetFrac by remember { mutableFloatStateOf(Random.nextFloat()) }
        LaunchedEffect(Unit) {
            while (isActive) {
                delay(DRIFT_INTERVAL_MS)
                // 全范围均匀随机（0..1），覆盖最多像素位置，防烧屏最有效
                targetFrac = Random.nextFloat()
            }
        }
        val driftFrac = rememberDriftFrac(targetFrac)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .verticalDrift(driftFrac),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ===== 速度图表 + 速度值：作为整体 =====
            SpeedGauge(
                speedKmh = speedKmh,
                limit = speedLimit,
                onClick = {},                       // 护屏中点击不做事（触摸即退出护屏）
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            )

            Spacer(Modifier.height(14.dp))

            // 大幅放大的速度数值：整数位 + 小数位共享基线；位数增多时自动降一档字号防溢出
            val intStr = "%.0f".format(speedKmh)
            val mainSize = when {
                intStr.length <= 3 -> 120.sp
                intStr.length == 4 -> 96.sp
                else -> 76.sp
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = intStr,
                    color = speedColor,
                    fontSize = mainSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    // 与 "%.1f" 同源拆分，避免整数位进位后小数位错位
                    text = "." + "%.1f".format(Locale.US, speedKmh).substringAfter('.'),
                    color = speedColor,
                    fontSize = (mainSize.value * 0.5f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    text = "km/h",
                    color = Color(0xFFAAAAAA),
                    fontSize = 26.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            // ===== 当前时间（速度块下方一点）=====
            Text(
                text = clock,
                color = Color(0xFFEEEEEE),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 位置自驱平滑（与记录页 rememberNeedleAngle 同一套路）：
 * 不用 animateXxxAsState —— Compose 动画跟随系统 ANIMATOR_DURATION_SCALE，该值为 0 的设备
 * （如 A16 / ColorOS 16 实测 =0）动画会在下一帧直接跳到终点，漂移会变成"瞬移"。
 * 这里用 withFrameNanos 按真实经过时间做 ease-in-out 插值，与系统动画设置完全解耦。
 * 插值对象是 0..1 的比例值（设备无关），px 换算在 [verticalDrift] 布局期完成。
 */
@Composable
private fun rememberDriftFrac(target: Float): State<Float> {
    val frac = remember { mutableFloatStateOf(target) }
    val targetState = rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        snapshotFlow { targetState.value }.collectLatest { tgt ->
            val from = frac.floatValue
            val to = tgt
            if (abs(to - from) < 0.001f) {
                frac.floatValue = to
                return@collectLatest
            }
            var startNanos = 0L
            while (true) {
                val done = withFrameNanos { now ->
                    if (startNanos == 0L) startNanos = now
                    val raw = ((now - startNanos) / 1_000_000f / DRIFT_ANIM_MS).coerceIn(0f, 1f)
                    val t = raw * raw * (3f - 2f * raw)   // smoothstep 缓入缓出
                    frac.floatValue = from + (to - from) * t
                    raw >= 1f
                }
                if (done) break
            }
        }
    }
    return frac
}

/**
 * 比例式纵向漂移（防烧屏核心，替代旧 onSizeChanged+offset 方案）：
 *  - 位置 = 上边距 + 剩余空间 × frac，**在同一次布局里**用真实测量高度换算成 px；
 *    布局期读 State（frac 动画帧触发重布局），测量与摆放永不脱节 ——
 *    任何设备、任何窗口变化（隐藏系统栏、分屏、旋转）都不会把内容摆出屏幕外。
 *  - 上下边距恒 ≥ [EDGE_MARGIN_PX] 物理像素（需求 ≥20px）。
 *  - 屏幕比内容还矮（极端短屏/横屏）：整块等比缩小到恰好放进上下边距内（顶部对齐），
 *    按比例动态适配，无需按设备单独调整。
 */
private fun Modifier.verticalDrift(frac: State<Float>): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val bounded = constraints.maxHeight != Constraints.Infinity
    val avail = if (bounded) (constraints.maxHeight - 2 * EDGE_MARGIN_PX).coerceAtLeast(0)
                else placeable.height
    val free = (avail - placeable.height).coerceAtLeast(0)
    val y = if (bounded) EDGE_MARGIN_PX + (free * frac.value).roundToInt() else 0
    layout(placeable.width, placeable.height) {
        if (bounded && placeable.height > avail && placeable.height > 0) {
            // 屏幕比内容还矮（极端短屏/横屏）：整块等比缩小到恰好放进上下边距内
            val s = avail.toFloat() / placeable.height
            placeable.placeWithLayer(0, y) {
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)   // 顶部为锚：缩完仍从 y 开始
            }
        } else {
            placeable.place(0, y)
        }
    }
}

/** 从任意 Context 包装链里找到宿主 Activity（LocalContext 未必直接是 Activity） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
