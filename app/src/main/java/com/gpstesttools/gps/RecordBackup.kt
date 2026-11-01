package com.gpstesttools.gps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 行驶记录备份 / 还原。
 *
 * 备份文件固定为 `/sdcard/GPS速度测试记录备份文件.zip`，内含记录目录下全部 csv
 * （文件名 = 条目名，还原时按条目名覆盖写入记录目录）。
 *
 * 权限说明：
 *  - 记录目录是应用私有外部目录（/sdcard/Android/data/...），读写无需任何权限；
 *  - 但 zip 固定放在 /sdcard 根目录，Android 11+ 直接路径读写该位置需要
 *    "所有文件访问权限"（MANAGE_EXTERNAL_STORAGE，特殊权限，只能跳系统设置页授予）；
 *    Android 10 靠 manifest 的 requestLegacyExternalStorage + WRITE 权限；
 *    Android 9- 同理走 WRITE 权限。
 */
object RecordBackup {

    const val BACKUP_NAME = "GPS速度测试记录备份文件.zip"

    /**
     * 跳系统设置页授权"所有文件访问"前置位；MainActivity ON_PAUSE 消费后复位。
     * 原因：设置页盖在 app 上 → ON_PAUSE 触发保活 FGS 启动，但此刻 app 已处于后台，
     * startForeground(location) 被 eligible-state 校验拒绝 → 服务无法在时限内前台化
     * → ForegroundServiceDidNotStartInTimeException 闪退。本次 ON_PAUSE 须跳过 FGS。
     */
    @Volatile
    var suppressKeepAliveOnce = false

    fun backupFile(): File =
        File(Environment.getExternalStorageDirectory(), BACKUP_NAME)

    /** 是否具备在 /sdcard 根目录直接读写 zip 的权限 */
    fun canAccessSdcard(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** 跳转"所有文件访问权限"系统设置页（Android 11+） */
    fun allFilesAccessIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:com.gpstesttools"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 打包全部 csv 到固定 zip 路径（覆盖已有 zip）。
     * @return 打包的 csv 文件数
     * @throws Exception IO / 无权限等失败
     */
    fun backup(context: Context): Int {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedBy { it.name } ?: emptyList()
        require(files.isNotEmpty()) { "没有可备份的记录" }
        // 仅存储（STORED）：条目不做 deflate 压缩，备份/还原更快，csv 内容原样进 zip。
        // STORED 条目必须预填 size / compressedSize / crc32。
        ZipOutputStream(FileOutputStream(backupFile())).use { zip ->
            for (f in files) {
                val bytes = f.readBytes() // 单条 csv 体量小（MB 级），整读无压力
                val entry = ZipEntry(f.name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return files.size
    }

    /**
     * 从固定 zip 覆盖还原全部 csv（按条目名写入记录目录，同名覆盖）。
     * @return 还原的 csv 文件数
     * @throws Exception zip 不存在 / 记录进行中 / IO 失败
     */
    fun restore(context: Context): Int {
        check(!RecordStore.isRecording()) { "记录进行中，不能还原（先停止记录）" }
        val zipFile = backupFile()
        require(zipFile.exists()) { "备份文件不存在：${zipFile.absolutePath}" }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        var n = 0
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 只接受纯文件名的 csv 条目（防路径穿越），还原为"覆盖写入"
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.endsWith(".csv") && !name.contains("..")) {
                    FileOutputStream(File(dir, name)).use { zip.copyTo(it) }
                    n++
                }
                zip.closeEntry()
            }
        }
        return n
    }
}
