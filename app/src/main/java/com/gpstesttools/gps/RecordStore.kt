package com.gpstesttools.gps

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行驶记录 CSV 存储（单例，一次只持有一个活动会话）。
 *
 * 文件：`{app外部目录}/开始时间.csv`（如 2026-09-01_23-50-00.csv）
 * 格式：
 *   第 1 行：开始时间（文件头，yyyy-MM-dd HH:mm:ss）
 *   第 2 行起：数据行 = 序号,速度(m/s)，每 1s 一条，行驶时间 = 记录条数
 *   最后一行：停止日期（单独一行）
 *
 * 缓冲：内置 4KB StringBuilder，满了写一次磁盘；finish 时 try-finally 保证
 * 剩余缓冲 flush + writer close，不丢数据。
 */
object RecordStore {

    /** 一条历史记录的元数据（由 csv 解析得出） */
    data class RecordMeta(
        val fileName: String,     // 文件名（如 2026-09-01_23-50-00.csv）
        val startTimeMs: Long,    // 开始时间
        val stopTimeMs: Long,     // 停止时间
        val count: Int,           // 数据行数 = 行驶秒数
        val distanceM: Float,     // 行驶里程（m）= Σ速度 × 1s
        val avgSpeedMs: Float,    // 平均速度（m/s）= Σ速度 / 条数
        val maxSpeedMs: Float,    // 最大速度（m/s）
    )

    private const val BUFFER_SIZE = 4096
    private const val FMT_FILE = "yyyy-MM-dd_HH-mm-ss"
    private const val FMT_TIME = "yyyy-MM-dd HH:mm:ss"

    /**
     * 单次记录时长上限（12 小时）。tick 循环累计到达后自动保存记录并退出应用，
     * 防止无限制写入导致文件过大/用户忘记停止。
     */
    const val MAX_RECORDING_MS = 12L * 60L * 60L * 1000L

    private var writer: FileWriter? = null
    private val buf = StringBuilder()
    private var sessionCount = 0

    /** 是否有活动记录会话（begin 后未 finish） */
    fun isRecording(): Boolean = writer != null

    /**
     * 仅把缓冲中尚未落盘的数据写入磁盘（不写停止时间行、不关闭会话）。
     * 用于暂停记录等"会话仍要保留、但可能长时间不再写入"的时点：先把缓冲清空落盘，
     * 之后即使进程被系统回收，也已无尾部数据可丢（csv 缺停止时间行时解析容错）。
     */
    fun flushBuffered() {
        if (buf.isNotEmpty()) flush()
    }

    /** 当前会话已写入的数据条数（含缓冲未落盘部分） */
    fun sessionCount(): Int = sessionCount

    /** 记录目录（应用私有外部目录，读写无需权限；不可用时回退内部 filesDir） */
    private fun dir(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** 某条记录对应的 csv 文件（不保证存在） */
    fun recordFile(context: Context, fileName: String): File = File(dir(context), fileName)

    /**
     * 开始一次记录：创建 csv 文件并写开始时间行。返回文件绝对路径。
     * 若上次会话未正常结束（异常路径），先防御性收尾。
     */
    fun begin(context: Context, startTimeMs: Long): String {
        finishQuietly()
        val directory = dir(context)
        val name = SimpleDateFormat(FMT_FILE, Locale.US).format(Date(startTimeMs)) + ".csv"
        val file = File(directory, name)
        val w = FileWriter(file, false)
        writer = w
        sessionCount = 0
        buf.setLength(0)
        w.write(SimpleDateFormat(FMT_TIME, Locale.US).format(Date(startTimeMs)))
        w.write("\n")
        return file.absolutePath
    }

    /** 追加一条数据：序号,速度（m/s，1 位小数）。缓冲满 4KB 写一次盘。 */
    fun append(speedMs: Float) {
        val w = writer ?: return
        sessionCount++
        buf.append(sessionCount).append(',').append("%.1f".format(Locale.US, speedMs)).append('\n')
        if (buf.length >= BUFFER_SIZE) flush()
    }

    /** 结束记录：写停止时间行 + flush + close。未开始记录时为 no-op。 */
    fun finish(stopTimeMs: Long) {
        val w = writer ?: return
        try {
            if (buf.isNotEmpty()) flush()
            w.write(SimpleDateFormat(FMT_TIME, Locale.US).format(Date(stopTimeMs)))
            w.write("\n")
            w.flush()
        } finally {
            closeWriter()
        }
    }

    /**
     * 删除某条记录对应的 csv 文件，并同步清掉它的图表缓存（ChartCache）。
     * @param fileName 完整文件名（含 .csv）
     * @return 文件删除成功返回 true；文件不存在或无权限时返回 false
     */
    fun delete(context: Context, fileName: String): Boolean {
        // 缓存随记录一起删；缓存本可被系统清理，这里只是避免留下孤儿文件
        ChartCache.delete(context, fileName)
        val f = File(dir(context), fileName)
        return runCatching { f.delete() }.getOrDefault(false)
    }

    /** 列出全部历史记录，按开始时间倒序（最新在前，序列号 1 = 最新）。 */
    fun list(context: Context): List<RecordMeta> {
        val dir = dir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        return files.mapNotNull { parseFile(it) }
    }

    /**
     * 读取某条记录的全部速度序列（m/s，每 1s 一条）。无数据或文件不存在时返回空列表。
     * 绘图请走 [ChartCache.load]（它会在此原始序列上做 EMA + 重采样平滑）。
     */
    fun loadSeries(context: Context, fileName: String): List<Float> =
        loadSeries(File(dir(context), fileName))

    /**
     * 读取 csv 全部速度序列（m/s）。
     * 末行为停止时间行，需跳过；异常退出（进程被回收 / 关机广播未及处理）时停止行可能缺失，
     * 此时末行就是数据行，按时间格式判别，避免静默丢掉最后一条样本。
     */
    fun loadSeries(f: File): List<Float> {
        if (!f.isFile) return emptyList()
        val lines = f.readLines()
        if (lines.size < 2) return emptyList()
        val end = if (parseTime(lines.last()) != null) lines.size - 1 else lines.size
        val out = ArrayList<Float>(end)
        for (i in 1 until end) {
            val speed = lines[i].substringAfter(',').toFloatOrNull() ?: continue
            out.add(speed)
        }
        return out
    }

    /** 解析单个 csv → 元数据；格式损坏时返回 null 跳过。 */
    private fun parseFile(f: File): RecordMeta? = runCatching {
        val lines = f.readLines()
        if (lines.size < 2) return@runCatching null
        val start = parseTime(lines.first()) ?: return@runCatching null
        // 末行缺失停止时间时按"全部为数据行"处理（与 loadSeries 口径保持一致，
        // 否则 meta.count 会比序列长度少 1，图表横轴标签会跟着偏）
        val stop = parseTime(lines.last())
        val end = if (stop != null) lines.size - 1 else lines.size
        var count = 0
        var dist = 0.0
        var maxV = 0f
        for (i in 1 until end) {
            val speed = lines[i].substringAfter(',').toFloatOrNull() ?: continue
            count++
            dist += speed
            if (speed > maxV) maxV = speed
        }
        RecordMeta(
            fileName = f.name,
            startTimeMs = start,
            stopTimeMs = stop ?: f.lastModified(),
            count = count,
            distanceM = dist.toFloat(),
            avgSpeedMs = if (count > 0) (dist / count).toFloat() else 0f,
            maxSpeedMs = maxV,
        )
    }.getOrNull()

    private fun flush() {
        val w = writer ?: return
        w.write(buf.toString())
        w.flush()
        buf.setLength(0)
    }

    private fun closeWriter() {
        writer = null
        sessionCount = 0
        buf.setLength(0)
    }

    /** 防御性收尾：有活动会话则补写停止时间并关闭（不抛异常） */
    private fun finishQuietly() {
        if (writer != null) {
            runCatching { finish(System.currentTimeMillis()) }
        }
    }

    private fun parseTime(s: String): Long? = runCatching {
        SimpleDateFormat(FMT_TIME, Locale.US).parse(s.trim())?.time
    }.getOrNull()
}
