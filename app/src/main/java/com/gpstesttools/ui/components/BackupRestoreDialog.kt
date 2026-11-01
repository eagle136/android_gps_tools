package com.gpstesttools.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gpstesttools.gps.RecordBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份 / 恢复弹窗（速度测量 → 历史记录 → "备份恢复"按钮打开）：
 * 两个蓝色按钮（备份 / 还原）。
 * 备份 = 把记录目录全部 csv 打包成 zip 覆盖保存到 /sdcard/GPS速度测试记录备份文件.zip；
 * 还原 = 读取该 zip，按条目名覆盖写入记录目录（记录进行中禁止还原，见 RecordBackup.restore）。
 * Android 11+ 在 /sdcard 根目录读写需要"所有文件访问权限"，未授予时跳系统设置页。
 * IO 放 Dispatchers.IO，结果回主线程（状态文本 + Toast）。
 */
@Composable
fun BackupRestoreDialog(
    context: Context,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val statusText = remember { mutableStateOf("备份文件：/sdcard/${RecordBackup.BACKUP_NAME}") }
    val busy = remember { mutableStateOf(false) }

    // Android 11+ 未授予"所有文件访问权限"→ 跳系统设置页，授权后返回重按即可
    val accessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        statusText.value = if (RecordBackup.canAccessSdcard()) {
            "已授权，请重新点击备份或还原"
        } else {
            "未授予\"所有文件访问权限\"，无法读写 /sdcard 根目录的备份文件"
        }
    }

    fun runOp(backup: Boolean) {
        if (busy.value) return
        if (!RecordBackup.canAccessSdcard()) {
            // 跳设置页前置位：通知 MainActivity 这次 ON_PAUSE 不启动保活 FGS
            //（app 已在后台，location 型 startForeground 会被拒绝 → DidNotStartInTime 崩溃）
            RecordBackup.suppressKeepAliveOnce = true
            runCatching { accessLauncher.launch(RecordBackup.allFilesAccessIntent()) }
                .onFailure {
                    RecordBackup.suppressKeepAliveOnce = false
                    statusText.value = "无法打开权限设置页：${it.message}"
                }
            return
        }
        busy.value = true
        statusText.value = if (backup) "正在备份…" else "正在还原…"
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (backup) RecordBackup.backup(context) else RecordBackup.restore(context)
            }
            withContext(Dispatchers.Main) {
                busy.value = false
                result.fold(
                    onSuccess = { n ->
                        statusText.value = if (backup) {
                            "备份完成：$n 条记录 → /sdcard/${RecordBackup.BACKUP_NAME}"
                        } else {
                            "还原完成：覆盖写入 $n 条记录"
                        }
                        Toast.makeText(
                            context,
                            if (backup) "备份完成（$n 条）" else "还原完成（$n 条）",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (!backup) onChanged()
                    },
                    onFailure = { e ->
                        statusText.value = "失败：${e.message}"
                        Toast.makeText(context, "失败：${e.message}", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy.value) onClose() }) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("备份 / 恢复", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                text = statusText.value,
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            DialogButton("备份（打包 csv → zip）") { runOp(backup = true) }
            Spacer(Modifier.height(10.dp))
            DialogButton("还原（zip 覆盖 → csv）") { runOp(backup = false) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (busy.value) "处理中…" else "还原会覆盖现有同名记录，且记录进行中不可还原",
                color = Color(0xFF888888),
                fontSize = 11.sp,
            )
        }
    }
}

/** 弹窗内通宽蓝色按钮（备份/恢复共用样式） */
@Composable
private fun DialogButton(text: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1976D2))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
