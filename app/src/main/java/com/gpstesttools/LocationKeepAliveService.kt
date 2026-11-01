package com.gpstesttools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.gps.RecordStore
import com.gpstesttools.ui.pages.RecordState

/**
 * 后台定位状态保活前台服务。
 *
 * 背景：MIUI/部分 ROM 在 app 进入后台约 1 分钟后会强制关闭 GPS provider（ProviderRequest[OFF]），
 * 导致卫星状态丢失、回前台冷启动重新搜星。
 *
 * 方案：进入后台（Activity ON_PAUSE）时启动本前台服务（系统不会冻结持有前台服务的进程）。
 * 通知 = **后台唯一常驻通知**（前台不显示），文案按记录状态动态显示且**点击可回到前台**（等同点击图标）：
 *  - 记录中（RECORDING）→ "正在记录，10 分钟未切换回前台将自动退出"
 *  - 未记录 / 暂停挂起        → "正在运行，将在 1 分钟后退出"
 * Activity 每次 ON_PAUSE 经 intent extra（EXTRA_RECORD_STATE）把状态带给服务用于文案与唤醒锁。
 *
 * 唤醒锁：仅 RECORDING 后台持有 PARTIAL_WAKE_LOCK 防 Doze（1s 高频 GPS 写 CSV 不能被休眠打断）；
 * 未记录/暂停已降频、无需锁。回前台（ON_RESUME 停服务）/ 停服务时释放，进程被杀系统自动回收。
 *
 * 引擎复用：RECORDING 时保持 1s 高频（不降频）；未记录/暂停已由 Activity ON_PAUSE 降频到 60s，
 * 本服务不再重复请求（引擎未启动时各调用均为 no-op）。
 *
 * **停止策略**：服务不会无限期运行。MainActivity 按状态排超时：空闲/暂停 1 分钟后调 stopService
 * 回收；记录中 10 分钟硬超时保存退出。因此本服务要么被 ON_RESUME 正常停止，要么被超时任务停止，
 * 要么随进程被系统回收，不会常驻后台。
 *
 * **重要**：Android 14+ (targetSdk 34+) 要求 startForeground 调用时**显式传 type 参数**，
 * 否则即使 manifest 声明了 `foregroundServiceType="location"`，FGS 也不被授予 location 访问权，
 * grace period（~10s）后会被系统撤销，导致 ProviderRequest[OFF]。必须用三参数版本。
 */
class LocationKeepAliveService : Service() {

    /** 关机/重启广播接收器（后台记录期间 Activity 层接收器可能已注销，由服务兜底保存） */
    private var shutdownReceiver: BroadcastReceiver? = null

    /** 记录中（RECORDING）持有的 CPU 唤醒锁，防 Doze 休眠打断 1s 高频定位采样 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerShutdownReceiver()
        // 必须传 FOREGROUND_SERVICE_TYPE_LOCATION：Android 14+ 才会给 FGS 授予 location 访问权。
        // 先以中性占位文案 startForeground，onStartCommand 随即按 extra 更新为准确文案。
        // 防御：定位权限被用户在系统设置里手动撤销/授权状态异常时，startForeground(location) 会抛
        // SecurityException —— 不让异常冒出崩溃进程，优雅停服（Activity 侧有权限守卫，正常到不了这）。
        if (!startForegroundWith(PLACEHOLDER_TEXT)) {
            stopSelf()
            return
        }
        // 引擎复用：RECORDING 保持 1s 高频（由上面唤醒锁保护）；
        // 非记录（含暂停）已由 Activity ON_PAUSE 降频，这里不重复请求
        if (RecordStore.isRecording() && !GpsEngine.isStarted()) {
            // 兜底：进程存活但引擎因异常停止时恢复（正常路径引擎由 Activity 管理）
            GpsEngine.start(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次进后台都会携带最新状态 → 更新通知文案 + 唤醒锁持有状态
        val state = intent?.getStringExtra(EXTRA_RECORD_STATE)
        updateNotification(state)
        updateWakeLock(state)
        return START_NOT_STICKY
    }

    /**
     * 最近任务划掉 / 系统一键清理：立即保存记录（finish 内部写停止时间行 + flush + close）
     * 并杀进程退出。RecordStore.finish 幂等（Activity onDestroy 可能已先保存）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (RecordStore.isRecording()) {
            runCatching { RecordStore.finish(System.currentTimeMillis()) }
        }
        super.onTaskRemoved(rootIntent)
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** 停止服务：释放唤醒锁、注销关机广播；引擎由 Activity 生命周期统一管理，回前台恢复高频 */
    override fun onDestroy() {
        releaseWakeLock()
        shutdownReceiver?.let { runCatching { unregisterReceiver(it) } }
        shutdownReceiver = null
        super.onDestroy()
    }

    // ===== 通知（后台唯一常驻，文案随记录状态，点击回前台）=====

    /** @return true = startForeground 成功；false = 权限/状态异常（调用方应 stopSelf） */
    private fun startForegroundWith(text: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, buildNotification(text))
            }
        }.onFailure {
            android.util.Log.w("KeepAlive", "startForeground failed: ${it.message}")
        }.isSuccess
    }

    /** 同 id 更新已 startForeground 的通知文案（Android 允许对前台通知 notify 更新） */
    private fun updateNotification(state: String?) {
        val text = when (state) {
            RecordState.RECORDING.name -> NOTIFY_TEXT_RECORDING
            else -> NOTIFY_TEXT_IDLE
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        // 点击通知 = 回到前台（等同点击应用图标：新建或复用既有 task，恢复上次停留页）
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS 工具箱")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定位状态保持",
                NotificationManager.IMPORTANCE_LOW,  // 低优先级：无声音无横幅
            ).apply { description = "后台保持卫星定位状态，回前台无需重新搜星" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ===== 唤醒锁（仅 RECORDING 持有，防 Doze）=====

    private fun updateWakeLock(state: String?) {
        val want = state == RecordState.RECORDING.name
        val lock = wakeLock
        if (want && (lock == null || !lock.isHeld)) {
            acquireWakeLock()
        } else if (!want && lock?.isHeld == true) {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gps_toolbox:recording")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    // ===== 关机广播（应急保存）=====

    private fun registerShutdownReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (RecordStore.isRecording()) {
                    runCatching { RecordStore.finish(System.currentTimeMillis()) }
                }
            }
        }
        shutdownReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
            addAction("android.intent.action.QUICKBOOT_SHUTDOWN")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    companion object {
        /** Activity ON_PAUSE 通过该 extra 传入当前记录状态（RecordState.name） */
        const val EXTRA_RECORD_STATE = "record_state"

        private const val CHANNEL_ID = "gps_keepalive"
        private const val NOTIFICATION_ID = 0x4B47  // "KEEP" 风格 ID，固定值
        private const val PLACEHOLDER_TEXT = "正在后台运行…"
        private const val NOTIFY_TEXT_RECORDING = "正在记录，10 分钟未切换回前台将自动退出"
        private const val NOTIFY_TEXT_IDLE = "正在运行，将在 1 分钟后退出"
    }
}
