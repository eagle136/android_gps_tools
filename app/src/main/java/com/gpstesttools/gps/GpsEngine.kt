package com.gpstesttools.gps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定位 + GNSS 引擎（对应原版 GpsService 的静态单例）。
 *
 * 使用现代 GnssStatus.Callback API 采集卫星数据，LocationManager 采集定位数据，
 * 以 StateFlow 对外暴露，供四个 Compose 页面直接 collect。
 */
object GpsEngine {

    /** 前台位置更新间隔（1 秒） */
    private const val FIX_INTERVAL_FOREGROUND_MS = 1_000L

    /** 后台降频间隔（60 秒）：保持 GPS 芯片 warm 跟踪，避免回前台冷启动 */
    private const val FIX_INTERVAL_BACKGROUND_MS = 60_000L

    /**
     * 卫星回调停滞阈值（30 秒）。
     * 后台时若超过此时间未收到 `onSatelliteStatusChanged` 回调，说明 GNSS 引擎被系统悄悄停掉了
     * （典型场景：MIUI 后台 60s 后的 `remove gps listener`）。此时回前台需要强制 AGPS 刷新
     * 重新注入星历，否则会从冷启动（无星历预测）状态恢复，TTFF 显著拉长。
     * ColorOS 上后台引擎持续活跃，不会触发此分支，省流量。
     */
    private const val SATELLITE_STALL_THRESHOLD_MS = 30_000L

    /** 单颗卫星快照（对应原版 aS 封装类） */
    data class SatInfo(
        val svid: Int,
        val constellation: Int,   // GnssStatus.CONSTELLATION_*
        val snr: Float,           // 信噪比 dBHz
        val azimuth: Float,       // 方位角°
        val elevation: Float,     // 仰角°
        val usedInFix: Boolean,   // 是否参与定位
    )

    /** 定位快照（对应原版 service.l） */
    data class FixInfo(
        val lat: Double,
        val lon: Double,
        val altitude: Double,     // 海拔 m
        val speed: Float,         // 速度 m/s
        val bearing: Float,       // 航向°
        val accuracy: Float,      // 水平精度 m
        val time: Long,           // 定位时刻 ms
    )

    /** GPS 状态：0=OFF 1=ON 2=WAIT 3=AGPS（与原版一致） */
    val gpsState: StateFlow<Int> get() = _gpsState
    private val _gpsState = MutableStateFlow(0)

    /** 当前卫星列表（按 svid 排序） */
    val sats: StateFlow<List<SatInfo>> get() = _sats
    private val _sats = MutableStateFlow<List<SatInfo>>(emptyList())

    /** 最新定位（无 fix 为 null） */
    val fix: StateFlow<FixInfo?> get() = _fix
    private val _fix = MutableStateFlow<FixInfo?>(null)

    /** 可见卫星数 */
    val satInView: StateFlow<Int> get() = _satInView
    private val _satInView = MutableStateFlow(0)

    /** 在用卫星数 */
    val satInUse: StateFlow<Int> get() = _satInUse
    private val _satInUse = MutableStateFlow(0)

    private var locationManager: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var locationListener: LocationListener? = null
    private var agpsNetListener: LocationListener? = null
    private var providerReceiver: ProviderChangeReceiver? = null
    private var started = false

    /** 最后一次 `onSatelliteStatusChanged` 时间戳（用于前台恢复时检测引擎是否被系统悄悄暂停） */
    private var lastSatelliteUpdateMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已启动 */
    fun isStarted() = started

    /** 启动采集（幂等）。调用方需先确认已获得定位权限。 */
    fun start(context: Context) {
        if (started) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm

        // 1) GNSS 卫星状态回调（API 24+）
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                lastSatelliteUpdateMs = System.currentTimeMillis()
                val list = ArrayList<SatInfo>(status.satelliteCount)
                var inUse = 0
                for (i in 0 until status.satelliteCount) {
                    val s = SatInfo(
                        svid = status.getSvid(i),
                        constellation = status.getConstellationType(i),
                        snr = status.getCn0DbHz(i),
                        azimuth = status.getAzimuthDegrees(i),
                        elevation = status.getElevationDegrees(i),
                        usedInFix = status.usedInFix(i),
                    )
                    if (s.usedInFix) inUse++
                    list.add(s)
                }
                list.sortBy { it.svid }
                // 去重：同一 (constellation, svid) 可能因芯片驱动重复上报而多次出现，
                // 否则 LazyColumn 的 item key 冲突会在新版 Compose（A16+）抛异常崩溃
                val seen = mutableSetOf<Pair<Int, Int>>()
                val deduped = list.filter { seen.add(it.constellation to it.svid) }
                _sats.value = deduped
                _satInView.value = deduped.size
                _satInUse.value = deduped.count { it.usedInFix }
            }

            override fun onStarted() = updateGpsState()
            override fun onStopped() = updateGpsState()
            override fun onFirstFix(ttffMillis: Int) {
                _gpsState.value = 1
            }
        }
        gnssCallback = cb
        runCatching { lm.registerGnssStatusCallback(cb, mainHandler) }

        // 2) 位置更新（GPS Provider，1 秒一次）
        val ll = LocationListener { loc -> onLocation(loc) }
        locationListener = ll
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_FOREGROUND_MS, 0f, ll, Looper.getMainLooper(),
            )
        }

        // 3) GPS 开关变化广播
        val receiver = ProviderChangeReceiver { updateGpsState() }
        providerReceiver = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        }

        started = true
        updateGpsState()
        // AGPS 加速：注入 XTRA 星历预测 + 时间同步 + 网络粗定位，加快卫星搜索/首次定位
        agpsRefresh()
        agpsNetworkFix()
    }

    /** 停止采集并清理 */
    fun stop(context: Context) {
        if (!started) return
        locationManager?.let { lm ->
            gnssCallback?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
            locationListener?.let { runCatching { lm.removeUpdates(it) } }
            agpsNetListener?.let { runCatching { lm.removeUpdates(it) } }
        }
        providerReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        gnssCallback = null
        locationListener = null
        agpsNetListener = null
        providerReceiver = null
        locationManager = null
        started = false
    }

    /**
     * 后台暂停（降级模式）：把 GPS 位置更新从 1s 降频到 60s，**保留 GNSS 卫星回调**。
     * 目的是让芯片维持 warm/hot 跟踪状态（已捕获卫星、星历不丢），回前台秒回有数据。
     * 不注销任何 listener，比完全 stop() 更省电又能保住定位状态。
     */
    fun backgroundPause() {
        val lm = locationManager ?: return
        val ll = locationListener ?: return
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_BACKGROUND_MS, 0f, ll, Looper.getMainLooper(),
            )
        }
    }

    /** 前台恢复：位置更新恢复 1s 高频（引擎未启动时为 no-op，start() 会以高频初始化） */
    fun foregroundResume() {
        val lm = locationManager ?: return
        val ll = locationListener ?: return
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_FOREGROUND_MS, 0f, ll, Looper.getMainLooper(),
            )
        }
        // 健壮性增强：检测 GNSS 引擎是否在后台被系统悄悄停掉
        // - MIUI 后台 60s+ 会主动 `remove gps listener`，星历缓存可能被清
        // - 此时回前台需强制 AGPS 刷新，否则会从冷启动恢复（TTFF 拉长到数十秒）
        // - ColorOS 上后台引擎持续活跃，lastSatelliteUpdateMs 始终新鲜，跳过此分支
        val now = System.currentTimeMillis()
        if (lastSatelliteUpdateMs > 0 && now - lastSatelliteUpdateMs > SATELLITE_STALL_THRESHOLD_MS) {
            android.util.Log.w(
                "GpsEngine",
                "GNSS stall ${(now - lastSatelliteUpdateMs) / 1000}s, force AGPS refresh",
            )
            agpsRefresh()
            agpsNetworkFix()
        }
    }

    /**
     * AGPS 辅助定位：强制 GPS 芯片刷新辅助数据，显著加快初始化/TTFF。
     * 顺序：先清陈旧辅助数据 → 注入精确时间 → 注入 XTRA 星历预测。
     * 需联网；设备/厂商不支持时各命令返回 false 或抛异常，runCatching 静默忽略。
     */
    fun agpsRefresh() {
        val lm = locationManager ?: return
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null) }
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }
        runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }
    }

    /**
     * 拉一次网络定位（cell/WiFi 粗略坐标）注入 GPS 芯片作为 AGPS 粗位置。
     * Android 的 GnssLocationProvider 会把 network fix 自动注入 GPS 引擎，
     * 芯片在"大致位置 + 精确时间 + 预测星历"齐备时能更快锁定卫星。拿到即移除。
     */
    private fun agpsNetworkFix() {
        val lm = locationManager ?: return
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        if (agpsNetListener != null) return
        val ll = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                runCatching { lm.removeUpdates(this) }
                agpsNetListener = null
            }
        }
        agpsNetListener = ll
        runCatching {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, ll, Looper.getMainLooper())
        }
    }

    /** 刷新 GPS 状态字：OFF / ON / WAIT */
    private fun updateGpsState() {
        val lm = locationManager ?: return
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val last = _fix.value
        val recent = last != null && System.currentTimeMillis() - last.time < 10_000L
        _gpsState.value = when {
            !enabled -> 0          // OFF
            recent -> 1            // ON
            else -> 2              // WAIT
        }
    }

    private fun onLocation(loc: Location) {
        _fix.value = FixInfo(
            lat = loc.latitude,
            lon = loc.longitude,
            altitude = loc.altitude.toDouble(),
            speed = loc.speed,
            bearing = loc.bearing,
            accuracy = loc.accuracy.toFloat(),
            time = loc.time,
        )
        updateGpsState()
    }

    private class ProviderChangeReceiver(
        private val onChange: () -> Unit,
    ) : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = onChange()
    }

    /** 星座 → 中文名 */
    fun constellationName(constellation: Int): String = when (constellation) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "未知"
    }

    /**
     * 旧版 PRN 编号（按星座换算，匹配原版 aS.getPrn() 的编码约定）。
     *  - GPS/SBAS/QZSS：直接用 svid
     *  - GLONASS：64 + svid（原版 PRN 65~89）
     *  - 北斗：200 + svid（原版 PRN 201~237）
     *  - Galileo：300 + svid
     */
    fun legacyPrn(constellation: Int, svid: Int): Int = when (constellation) {
        GnssStatus.CONSTELLATION_GPS -> svid
        GnssStatus.CONSTELLATION_SBAS -> svid
        GnssStatus.CONSTELLATION_GLONASS -> 64 + svid
        GnssStatus.CONSTELLATION_QZSS -> svid
        GnssStatus.CONSTELLATION_BEIDOU -> 200 + svid
        GnssStatus.CONSTELLATION_GALILEO -> 300 + svid
        else -> svid
    }

    /**
     * 星座 → 国家 emoji（替代 drawable 国旗，更轻量 + 跨平台一致）
     *  - GPS      → 🇺🇸 美国
     *  - GLONASS  → 🇷🇺 俄罗斯
     *  - Galileo  → 🇪🇺 欧盟
     *  - 北斗     → 🇨🇳 中国
     *  - QZSS     → 🇯🇵 日本
     *  - IRNSS    → 🇮🇳 印度
     *  - SBAS     → 🛰️ 区域增强
     *  - UNKNOWN  → ❓
     */
    fun constellationEmoji(constellation: Int): String = when (constellation) {
        GnssStatus.CONSTELLATION_GPS -> "\uD83C\uDDFA\uD83C\uDDF8"      // 🇺🇸
        GnssStatus.CONSTELLATION_GLONASS -> "\uD83C\uDDF7\uD83C\uDDFA"  // 🇷🇺
        GnssStatus.CONSTELLATION_GALILEO -> "\uD83C\uDDEA\uD83C\uDDFA"  // 🇪🇺
        GnssStatus.CONSTELLATION_BEIDOU -> "\uD83C\uDDE8\uD83C\uDDF3"   // 🇨🇳
        GnssStatus.CONSTELLATION_QZSS -> "\uD83C\uDDEF\uD83C\uDDF5"      // 🇯🇵
        GnssStatus.CONSTELLATION_IRNSS -> "\uD83C\uDDEE\uD83C\uDDF3"     // 🇮🇳
        GnssStatus.CONSTELLATION_SBAS -> "\uD83D\uDEF0️"                  // 🛰️
        else -> "❓"
    }
}
