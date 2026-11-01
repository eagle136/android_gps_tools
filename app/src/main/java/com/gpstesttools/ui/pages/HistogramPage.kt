package com.gpstesttools.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpstesttools.R
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.ui.theme.BgBlack
import com.gpstesttools.ui.theme.BoxBackground
import com.gpstesttools.ui.theme.Dati
import com.gpstesttools.ui.theme.GpsOff
import com.gpstesttools.ui.theme.GpsOn
import com.gpstesttools.ui.theme.TopbarBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

// gps_test.ttf 图标字体：
//   顶栏：Z(状态) O/Q(卫星) R(精度) l(底栏)
//   表头：Q(旗帜+PRN) S(条+数值) O(在用) G(方位) t(仰角)
//   行：  O/N(在用/不在用)
private val IconFont = FontFamily(Font(R.font.gps_test))

// 行高（原版 viewHeight/(satCount+1)，固定 30dp 以保证可读）
private val RowH: Dp = 30.dp

// 列宽（按 360dp 屏换算原版 480px 比例：旗+PRN≈15.6% 条≈50% 数值/用/方位/仰角）
private val FlagW: Dp = 26.dp
private val PrnW: Dp = 28.dp
private val DbW: Dp = 24.dp
private val UsedW: Dp = 24.dp
private val AzW: Dp = 40.dp
private val ElW: Dp = 30.dp

// 颜色（对齐原版 colors.xml）
private val BoxIconRed = GpsOff           // box_icon #FFFF0000
private val DividerLine = Color(0x33FFFFFF)
private val UsedGreen = GpsOn
private val UsedRed = GpsOff
private val AzElWhite = Dati

/**
 * SNR 条颜色（渐变）：<15 红 / >40 绿 / 15~40 红→绿线性渐变
 * 与 O/N 在用标记解耦：信号条纯按 SNR 值着色
 */
private fun snrBarColor(snr: Float): Color {
    val s = snr.coerceIn(0f, 50f)
    return when {
        s < 15f -> Color(0xFFFF0000)       // <15 红
        s > 40f -> Color(0xFF00E500)       // >40 绿
        else -> lerp(Color(0xFFFF0000), Color(0xFF00E500), (s - 15f) / 25f) // 15~40 渐变
    }
}

/**
 * 页面二：信号强度（保持原版 HistogramView 布局）
 *  顶栏：GPS状态 | 在用/可见 | 精度
 *  表头：Q(旗帜+PRN) S(条+数值) O(在用) G(方位) t(仰角)
 *  行：  emoji(国家) | PRN(legacy) | SNR满高色块 | dB | O/N | 方位° | 仰角°
 *  底栏：l 图标 | 坐标 | WGS84
 *
 * 移植要点：旗帜列从国旗 drawable 改为 emoji 国家图标（无需图片资源）
 */
@Composable
fun HistogramPage(modifier: Modifier = Modifier) {
    val fix by GpsEngine.fix.collectAsState()
    val gpsState by GpsEngine.gpsState.collectAsState()

    // 2s 节流快照：只保留有信号（SNR > 0）的卫星
    var signalSats by remember { mutableStateOf(emptyList<GpsEngine.SatInfo>()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            signalSats = GpsEngine.sats.value.filter { it.snr > 0f }
            delay(2000)
        }
    }
    // 顶栏计数与过滤后一致：在用 = 参与定位的，可见 = 有信号的
    val inUse = signalSats.count { it.usedInFix }
    val inView = signalSats.size

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBlack),
    ) {
        TopStatusBar(gpsState, inUse, inView, fix)

        // 表格区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            HeaderRow()
            HorizontalDivider(color = DividerLine, thickness = 0.5.dp)

            if (signalSats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("等待 GNSS 卫星…", color = Color(0xFF808080), fontSize = 12.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(
                        items = signalSats,
                        key = { sat -> "${sat.constellation}-${sat.svid}" },
                    ) { sat ->
                        SatRow(sat)
                        HorizontalDivider(color = DividerLine, thickness = 0.5.dp)
                    }
                }
            }
        }

        BottomCoordBar(fix)
    }
}

// ======================== 顶栏 ========================
@Composable
private fun TopStatusBar(state: Int, inUse: Int, inView: Int, fix: GpsEngine.FixInfo?) {
    // GPS 二值：state==0 视为关，其余（ON/WAIT/AGPS）视为开
    val (stText, stColor) = if (state == 0) {
        "GPS：关" to GpsOff
    } else {
        "GPS：开" to GpsOn
    }
    val accText = fix?.let { "%.1f m".format(it.accuracy) } ?: "--"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopbarBackground)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stText,
            color = stColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TopVDivider()
        Text(
            text = "$inUse/$inView",
            color = Dati,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f),
        )
        TopVDivider()
        Text(
            text = accText,
            color = Dati,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
private fun TopVDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(0.5.dp)
            .height(28.dp)
            .background(DividerLine),
    )
}

// ======================== 表头 ========================
@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().height(RowH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Q：旗帜+PRN 整区（原版无内部分割线）
        Box(
            modifier = Modifier.width(FlagW + PrnW).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Q", fontFamily = IconFont, color = BoxIconRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        VDivider()
        // S：条+数值 整区（weight=1f，与数据行的 bar|db 等宽）
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text("S", fontFamily = IconFont, color = BoxIconRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        VDivider()
        GlyphCell(UsedW, "O")
        VDivider()
        GlyphCell(AzW, "G")
        VDivider()
        GlyphCell(ElW, "t")
    }
}

@Composable
private fun GlyphCell(w: Dp, glyph: String) {
    Box(
        modifier = Modifier.width(w).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontFamily = IconFont, color = BoxIconRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(0.5.dp).fillMaxHeight().background(DividerLine))
}

// ======================== 卫星行 ========================
@Composable
private fun SatRow(sat: GpsEngine.SatInfo) {
    val prn = GpsEngine.legacyPrn(sat.constellation, sat.svid)
    val used = sat.usedInFix
    val snr = sat.snr
    val ratio = (snr / 50f).coerceIn(0f, 1f)
    val barColor = snrBarColor(snr)
    val prnColor = if (used) UsedGreen else UsedRed
    val usedGlyph = if (used) "O" else "N"
    val usedColor = if (used) UsedGreen else UsedRed
    val snrText = if (snr > 0f) "${snr.toInt()}" else "0"
    val azText = "${sat.azimuth.toInt()}°"
    val elText = "${sat.elevation.toInt()}°"

    Row(
        modifier = Modifier.fillMaxWidth().height(RowH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // emoji(国家) + PRN（原版无内部分割线）
        Box(modifier = Modifier.width(FlagW + PrnW).fillMaxHeight()) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(FlagW).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        text = GpsEngine.constellationEmoji(sat.constellation),
                        fontSize = 15.sp,
                    )
                }
                Box(modifier = Modifier.width(PrnW).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "$prn",
                        color = prnColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        VDivider()
        // SNR 满高色块（左侧 ratio 宽度）+ dB 数值（右对齐）
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val totalW = maxWidth
            val barMaxW = totalW - DbW
            val blockW = (barMaxW * ratio).coerceAtLeast(0.dp)
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (blockW > 0.dp) {
                    Box(
                        modifier = Modifier
                            .width(blockW)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                            .background(barColor),
                    )
                }
                Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                Box(
                    modifier = Modifier.width(DbW).fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = snrText,
                        color = AzElWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        VDivider()
        // 在用标记（gps_test O/N 字形）
        Box(
            modifier = Modifier.width(UsedW).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = usedGlyph,
                fontFamily = IconFont,
                color = usedColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        VDivider()
        // 方位
        Box(
            modifier = Modifier.width(AzW).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(azText, color = AzElWhite, fontSize = 12.sp, maxLines = 1)
        }
        VDivider()
        // 仰角
        Box(
            modifier = Modifier.width(ElW).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(elText, color = AzElWhite, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ======================== 底栏坐标 ========================
@Composable
private fun BottomCoordBar(fix: GpsEngine.FixInfo?) {
    val posText = fix?.let {
        val latD = abs(it.lat).toInt()
        val latM = (abs(it.lat) - latD) * 60.0
        val lonD = abs(it.lon).toInt()
        val lonM = (abs(it.lon) - lonD) * 60.0
        val latH = if (it.lat >= 0) "N" else "S"
        val lonH = if (it.lon >= 0) "E" else "W"
        "%d°%.4f'%s  %d°%.4f'%s".format(latD, latM, latH, lonD, lonM, lonH)
    } ?: "--"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BoxBackground, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "l",
            fontFamily = IconFont,
            color = BoxIconRed,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = posText,
            color = Dati,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Text(text = "WGS84", color = Color(0xFF888888), fontSize = 11.sp)
    }
}
