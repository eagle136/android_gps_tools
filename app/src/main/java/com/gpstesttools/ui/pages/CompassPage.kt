package com.gpstesttools.ui.pages

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpstesttools.R
import com.gpstesttools.gps.CompassEngine
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.ui.theme.BgBlack
import com.gpstesttools.ui.theme.Dati
import kotlin.math.abs

private val DIRECTIONS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

/**
 * 页面一：指南针
 * 顶部大角度 + 表盘+指针（dial 固定、needle 反向旋转）+ 中文方向
 * 中部：精度 / 磁偏角 / 卫星 三个小数据卡（避免大块空白）
 * 底部：坐标 + 海拔
 */
@Composable
fun CompassPage(modifier: Modifier = Modifier) {
    val heading by CompassEngine.heading.collectAsState()
    val declination by CompassEngine.declination.collectAsState()
    val fix by GpsEngine.fix.collectAsState()
    val satsInView by GpsEngine.satInView.collectAsState()
    val satsInUse by GpsEngine.satInUse.collectAsState()

    // 定位后更新磁偏角（一次性副作用）
    LaunchedEffect(fix?.time) {
        val f = fix ?: return@LaunchedEffect
        CompassEngine.updateDeclination(f.lat, f.lon, f.altitude, f.time)
    }

    val round = ((heading + 0.5f).toInt()) % 360
    val dirIdx = ((round + 22) / 45) % 8
    val dir = DIRECTIONS[dirIdx]

    val accuracyStr = fix?.let { "%.1f m".format(it.accuracy) } ?: "--"
    val declStr = declination?.let { "%.1f°".format(abs(it)) + if (it >= 0) "E" else "W" } ?: "--"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ① 顶部朝向角度（已删除：不再显示顶部大字角度；保留等高占位使表盘位置不动）
        Spacer(Modifier.height(80.dp))

        // ② 表盘 + 指针（叠放：dial 固定，needle 反向旋转 -heading）+ 中央大角度
        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.compass_dial),
                contentDescription = "表盘",
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.drawable.compass_needle),
                contentDescription = "指针",
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(-heading),
            )
            // 中央大字角度（与原顶部同大小，白字+黑阴影保证可读，叠在指针之上）
            Text(
                text = "$round°",
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(2f, 2f),
                        blurRadius = 6f,
                    ),
                ),
            )
        }

        // ③ 中文方向 + 当前角度（大号显示，作为主要角度读数）
        Text(
            text = "$dir $round°",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )

        Spacer(Modifier.height(24.dp))

        // ④ 信息卡片三联（精度 / 磁偏角 / 卫星）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoCard(title = "精度", value = accuracyStr, modifier = Modifier.weight(1f))
            InfoCard(title = "磁偏角", value = declStr, modifier = Modifier.weight(1f))
            InfoCard(title = "卫星", value = "$satsInUse/$satsInView", modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(18.dp))

        // ⑤ 实时读数行（速度 / 位置更新 / 精度 — 让页面更紧凑）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x22000000), RoundedCornerShape(8.dp))
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val speedKmh = fix?.let { "%.1f".format(it.speed * 3.6f) } ?: "--"
            val altStr = fix?.let { "%.1f".format(it.altitude) } ?: "--"
            LiveCell("速度", "$speedKmh km/h")
            LiveCell("海拔", "$altStr m")
            LiveCell("状态", if (fix != null) "已定位" else "搜索中")
        }

        Spacer(Modifier.weight(1f))

        // ⑥ 底部坐标条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val loc = fix?.let { "%.6f, %.6f".format(it.lat, it.lon) } ?: "--"
            val alt = fix?.let { "海拔 %.1f m".format(it.altitude) } ?: "海拔 -- m"
            Text(text = loc, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text(text = alt, fontSize = 15.sp, color = Color.White, textAlign = TextAlign.End)
        }
    }
}

/** 指南针页中部小数据卡（精度 / 磁偏角 / 卫星） */
@Composable
private fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0x22000000), RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, fontSize = 13.sp, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Dati,
            maxLines = 1,
        )
    }
}

/** 实时读数单元格（速度/海拔/状态） */
@Composable
private fun LiveCell(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, fontSize = 12.sp, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Dati,
            maxLines = 1,
        )
    }
}