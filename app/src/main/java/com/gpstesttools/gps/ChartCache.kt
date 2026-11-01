package com.gpstesttools.gps

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 图表专用缓存（行驶记录速度曲线）。
 *
 * 为什么需要：记录 csv 是 **1Hz 原始样本**（12 小时上限 = 43200 条），而详细记录弹窗的
 * 绘图区只有约 280dp 宽（真机 ≈967px）⇒ 1673 个样本挤进 967px（1.7 样本/像素），
 * 逐点连线会把「每秒 ±0.8km/h 的真实抖动」画成毛刺带，再叠上路口停车的锯齿，
 * 观感上就是一团「离谱」的梳状图。本模块把原始序列先做 EMA 平滑、再按显示宽度
 * 重采样成固定点数、最后做一次 3 点滑动平均消除台阶，产出**专供绘图**的缓存文件。
 *
 * 缓存文件：`{externalCacheDir}/chart/{记录文件名}`（同名不同目录）
 *  - externalCacheDir 属系统「设置 → 应用 → 存储 → 清除缓存」可一键清理的位置；
 *  - 与记录目录（getExternalFilesDir）分离 ⇒ 不会被"历史记录列表"和"备份/还原"扫到；
 *  - 文件格式（首行元信息 + 逐行 `秒,速度km/h`）：
 *    `#GPSCACHE,1,<源文件字节数>:<源文件mtimems>:<alpha>:<点数>,<总秒数>,<点数>`
 *    `0.56,0.00`
 *    `3.91,1.44`
 *    ...
 *  - **缓存缺失/损坏/源 csv 变化（字节数或 mtime 变）→ 自动重建**，调用方无感。
 *
 * 统计口径：本缓存**只服务绘图**。里程/均速/最大速度等统计仍由 RecordStore 用原始
 * 数据计算，保证与历史列表数字一致（平滑会把峰值削平、把均值拉偏）。
 */
object ChartCache {

    /** 缓存格式版本；格式不兼容时旧缓存直接判废重建 */
    private const val VERSION = 1
    private const val HEADER_PREFIX = "#GPSCACHE"

    /** EMA 平滑系数 α：时间常数 ≈ 1/α = 5s。抖动 0.226→0.14 m/s（-37%），峰值不衰减 */
    const val EMA_ALPHA = 0.20f

    /** 图表目标点数：绘图区 ≈967px ⇒ 约 2px 一点，既消锯齿又保留停车/起步的锯齿形貌 */
    const val POINTS = 480

    /** 缓存子目录名（位于 externalCacheDir 下） */
    private const val CACHE_SUBDIR = "chart"

    /**
     * 取某条记录的绘图序列（km/h）。缓存命中直接读缓存；未命中则用源 csv 生成缓存并返回。
     * 源文件不存在或为空 → 返回空列表（图表显示"无数据"）。
     */
    fun load(context: Context, fileName: String): List<Float> {
        val src = RecordStore.recordFile(context, fileName)
        if (!src.isFile) return emptyList()
        val cache = cacheFile(context, fileName) ?: return build(src).points
        val key = cacheKey(src)
        readCache(cache, key)?.let { return it }
        val built = build(src)
        writeCache(cache, key, built)
        return built.points
    }

    /** 删除某条记录对应的图表缓存（随记录删除一起清理；不存在时静默） */
    fun delete(context: Context, fileName: String) {
        runCatching { cacheFile(context, fileName)?.delete() }
    }

    /** 清空全部图表缓存（当前未接 UI，留作维护入口） */
    fun clear(context: Context) {
        runCatching { cacheDir(context)?.listFiles()?.forEach { it.delete() } }
    }

    // ===== 内部实现 =====

    /** 缓存键：源文件"字节数:mtime:α:点数"，任一变化即失效重建 */
    private fun cacheKey(src: File): String =
        "${src.length()}:${src.lastModified()}:$EMA_ALPHA:$POINTS"

    /** externalCacheDir 缺失（外部存储不可用）时回退内部 cacheDir */
    private fun cacheDir(context: Context): File? {
        val base = context.externalCacheDir ?: context.cacheDir ?: return null
        val dir = File(base, CACHE_SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    private fun cacheFile(context: Context, fileName: String): File? =
        cacheDir(context)?.let { File(it, fileName) }

    /** [build] 的产物：绘图点（km/h）+ 源记录总秒数（写入缓存供自描述用） */
    private class Built(val points: List<Float>, val srcSec: Int)

    /**
     * 生成绘图序列：
     *  1) 读源 csv 原始 1Hz 速度（m/s）；
     *  2) **EMA 平滑**（α = [EMA_ALPHA]，对全量原始样本）；
     *  3) **按显示需要重采样**：均分成 [POINTS] 个时间桶，桶内取 EMA 值均值
     *     （源样本数 ≤ [POINTS] 时保持原分辨率）；
     *  4) **二次平滑**：3 点滑动平均（端点复制），消除分桶带来的台阶；
     *  5) 转 km/h。
     */
    private fun build(src: File): Built {
        val raw = RecordStore.loadSeries(src)
        val n = raw.size
        if (n == 0) return Built(emptyList(), 0)

        // 2) EMA：e0 = v0，其后 e += α(v - e)
        val ema = FloatArray(n)
        var e = raw[0]
        for (i in 0 until n) {
            e += EMA_ALPHA * (raw[i] - e)
            ema[i] = e
        }

        // 3) 重采样：桶边界用整数乘法，保证桶宽相差不超过 1 个样本
        val nOut = if (n <= POINTS) n else POINTS
        val bucket = FloatArray(nOut)
        for (j in 0 until nOut) {
            val lo = (j.toLong() * n / nOut).toInt()
            val hi = (((j + 1).toLong() * n + nOut - 1) / nOut).toInt()
                .coerceIn(lo + 1, n)
            var sum = 0f
            for (i in lo until hi) sum += ema[i]
            bucket[j] = sum / (hi - lo)
        }

        // 4) 3 点滑动平均（端点复制），彻底抹掉台阶
        val out = FloatArray(nOut)
        for (j in 0 until nOut) {
            val a = bucket[if (j > 0) j - 1 else 0]
            val b = bucket[j]
            val c = bucket[if (j < nOut - 1) j + 1 else nOut - 1]
            out[j] = (a + b + c) / 3f
        }

        // 5) m/s → km/h
        return Built(out.map { it * 3.6f }, n)
    }

    /** 缓存写入：首行元信息 + 逐行 `秒,速度km/h`。任何 IO 异常都静默（缓存非必需） */
    private fun writeCache(cache: File, key: String, built: Built) {
        runCatching {
            val series = built.points
            val spanSec = if (built.srcSec > 0) built.srcSec else series.size
            val sb = StringBuilder(series.size * 12 + 64)
            sb.append(HEADER_PREFIX).append(',').append(VERSION).append(',')
                .append(key).append(',').append(spanSec).append(',').append(series.size)
                .append('\n')
            for (i in series.indices) {
                // 时间取桶中心（毫秒级精度足够横轴标签用）
                val t = (i + 0.5f) * spanSec / series.size
                sb.append("%.2f,%.2f".format(Locale.US, t, series[i])).append('\n')
            }
            cache.writeText(sb.toString())
        }
    }

    /** 读缓存：版本/键不符或解析失败返回 null（调用方重建） */
    private fun readCache(cache: File, key: String): List<Float>? = runCatching {
        if (!cache.isFile) return null
        val lines = cache.readLines()
        if (lines.size < 2) return null
        val head = lines[0].split(',')
        if (head.size < 5 || head[0] != HEADER_PREFIX) return null
        if (head[1].toIntOrNull() != VERSION) return null
        if (head[2] != key) return null
        val expect = head[4].toIntOrNull() ?: return null
        val out = ArrayList<Float>(expect)
        for (i in 1 until lines.size) {
            val v = lines[i].substringAfter(',').toFloatOrNull() ?: continue
            out.add(v)
        }
        if (out.size != expect) return null
        out
    }.getOrNull()
}
