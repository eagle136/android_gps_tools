package com.gpstesttools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density

/**
 * 屏幕等比缩放（不同设备显示效果基本一致）
 *
 * 原理：覆盖全局 Density —— 把密度乘一个比例因子 scale，所有 dp/sp
 * （含 Canvas 里的 dp.toPx()）随之整体放大/缩小。相比逐行改布局，
 * 一套代码即可覆盖三个页面：表盘、半圆仪表、信号表格列宽/行高、
 * 卡片、字号、间距、Dialog 全部自动跟随，无需改动各页面。
 *
 * 设计基准 = 当前真机：1080x2340px @ 440dpi（2.75x）→ 393dp 宽。
 * 基准机上 scale == 1，UI 与现在完全一致；
 * 更窄的屏整体缩小、更宽的屏（大屏旗舰/平板竖屏）整体放大，观感一致。
 *
 * 为何只按宽度、不用 min(宽, 高)：
 * 现代手机全屏高度差异很小（约 780~960dp），宽度（320~430dp）才是
 * 决定"挤不挤、像不像"的变量；纵向余量由各页面已有的 weight 弹性吸收。
 * 极端形态（横屏 / 老 16:9 短屏）缩放前后都不是目标使用场景，不做特判。
 *
 * 上下限截断：小屏不缩到字不可读，平板/大屏不放大到巨型 UI。
 *
 * 用法：包在 setContent 最外层、Theme 之内
 *     GPS工具箱Theme { ScreenScaling { GpsToolboxApp() } }
 * 若未来换参考机重新排版，改 [DESIGN_W_DP] 一个常量即可。
 */
private const val DESIGN_W_DP = 393f

/** 缩放下限：320dp 级小屏仍有 ~0.82，字可读；0.75 兜底极少见短屏 */
private const val MIN_SCALE = 0.75f

/** 缩放上限：折叠内屏/平板竖屏约 600~800dp 宽，放大到 1.3 倍封顶，避免巨物化 */
private const val MAX_SCALE = 1.30f

/**
 * 将子树内所有 dp/sp 尺寸按屏幕宽度比例缩放。
 * 窗口尺寸变化（横竖屏、窗口缩放）会自动触发重组并重算 scale。
 */
@Composable
fun ScreenScaling(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    // containerSize.width：edge-to-edge 下恒等于物理屏宽（px），无 inset 歧义
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val scale = remember(windowWidthPx, base.density) {
        (windowWidthPx / base.density / DESIGN_W_DP).coerceIn(MIN_SCALE, MAX_SCALE)
    }
    // fontScale 保留用户系统字体设置，只动密度
    val scaled = remember(scale, base.fontScale) {
        Density(density = base.density * scale, fontScale = base.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
