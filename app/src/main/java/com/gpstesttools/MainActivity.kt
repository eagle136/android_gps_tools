package com.gpstesttools

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gpstesttools.gps.CompassEngine
import com.gpstesttools.gps.GpsEngine
import com.gpstesttools.gps.RecordBackup
import com.gpstesttools.gps.RecordStore
import com.gpstesttools.ui.components.IDLE_ENTER_MS
import com.gpstesttools.ui.components.SpeedScreenSaver
import com.gpstesttools.ui.components.TOUCH_POLL_MS
import com.gpstesttools.ui.pages.CompassPage
import com.gpstesttools.ui.pages.HistogramPage
import com.gpstesttools.ui.pages.NavigationPage
import com.gpstesttools.ui.pages.RecordState
import com.gpstesttools.ui.ScreenScaling
import com.gpstesttools.ui.theme.GPS工具箱Theme
import com.gpstesttools.ui.theme.MenuBackground
import com.gpstesttools.ui.theme.MenuSelected
import com.gpstesttools.ui.theme.MenuUnselected
import com.gpstesttools.ui.theme.TopbarBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    /** 关机/重启广播接收器：记录中收到信号 → 立即保存 + 刷写（进程随系统关闭，无需再退出） */
    private var shutdownReceiver: BroadcastReceiver? = null

    /**
     * 视为"系统清理/关闭"的 onTrimMemory 档位。
     * 刻意排除 RUNNING_LOW：本应用占用内存很小，系统低内存该找短视频/游戏等大户，
     * RUNNING_LOW 常在前台记录中触发，误杀正在进行的会话得不偿失。
     * 也排除 UI_HIDDEN（仅表示切后台，记录中允许后台运行）。
     */
    private val trimExitLevels = intArrayOf(
        TRIM_MEMORY_RUNNING_CRITICAL, TRIM_MEMORY_COMPLETE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerShutdownReceiver()
        // 纯黑背景 → 强制深色背景风格（状态栏/导航栏图标用浅色，黑色顶栏/底栏上可见）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            GPS工具箱Theme {
                // 屏幕等比缩放：以当前真机 393dp 宽为基准（scale=1），
                // 其它宽度的设备整体按比例放大/缩小，显示效果基本一致
                ScreenScaling {
                    GpsToolboxApp()
                }
            }
        }
    }

    /** 注册关机/重启广播（部分 ROM 使用 QuickBoot 变体 action） */
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

    /**
     * 系统内存清理类通知：收到临界/彻底清理（清理后台）信号时，
     * 记录中 → 立即保存数据 + 刷写缓存并退出应用。
     * 排除 RUNNING_LOW（本应用内存占用小，低内存该由大户应用承担）与 UI_HIDDEN（仅切后台）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (RecordStore.isRecording() && trimExitLevels.contains(level)) {
            saveRecordingAndExit()
        }
    }

    /**
     * Activity 销毁（用户返回键退出 / 最近任务划掉 / 系统回收 Activity）：
     * 记录中且非屏幕旋转等配置变更 → 立即保存 + 刷写并退出。
     * 保存为幂等操作：saveAndExitApp 已 finish 过时这里是 no-op。
     */
    override fun onDestroy() {
        val changed = isChangingConfigurations
        shutdownReceiver?.let { runCatching { unregisterReceiver(it) } }
        shutdownReceiver = null
        super.onDestroy()
        if (RecordStore.isRecording() && !changed) {
            saveRecordingAndExit()
        }
    }

    /** 无 Activity 状态依赖的应急保存退出（onTrimMemory / onDestroy 用） */
    private fun saveRecordingAndExit() {
        runCatching { RecordStore.finish(System.currentTimeMillis()) }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** 底部 tab（按参考截图：仅居中文字，无图标） */
private val TABS = listOf("指南针", "信号强度", "速度测量")

@Composable
fun GpsToolboxApp() {
    val context = LocalContext.current

    // ===== 页面/记录状态（rememberSaveable：切页、旋转、进程重建均保留）=====
    // pageIndex 额外持久化到 SharedPreferences：rememberSaveable 覆盖进程内存重建，
    // 但冷启动（系统回收进程后再次点击桌面图标）会丢回 0。
    // 持久化到 sp 保证无论冷/热启动都能回到用户上次停留的 tab。
    val sp = remember { context.getSharedPreferences("gps_toolbox", Context.MODE_PRIVATE) }
    var pageIndex by rememberSaveable { mutableIntStateOf(sp.getInt(KEY_LAST_TAB, 0)) }
    var recordStateName by rememberSaveable { mutableStateOf(RecordState.STOPPED.name) }
    var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var distanceM by rememberSaveable { mutableFloatStateOf(0f) }
    var maxSpeedMs by rememberSaveable { mutableFloatStateOf(0f) }
    var lastFixTime by rememberSaveable { mutableLongStateOf(0L) }
    var speedLimit by rememberSaveable { mutableIntStateOf(200) }

    // ===== 首次启动权限声明（确认后才申请，可退出）=====
    // consent 持久化到 SharedPreferences，仅首次启动弹一次
    var consentGranted by remember { mutableStateOf(sp.getBoolean(KEY_PERMISSION_CONSENT, false)) }
    var showConsentDialog by remember { mutableStateOf(!consentGranted) }

    // ===== 运行时权限：定位（含 GNSS 状态回调）=====
    var hasPermission by rememberSaveable { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
    ) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    /** 申请运行时权限：定位（必需）+ 低版本存储（Android 10- 保存行驶记录；11+ 无需）+ 通知（33+ 保活通知） */
    fun launchRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    // 首次启动：用户确认声明 → 持久化 consent → 申请权限
    fun onConsentConfirm() {
        consentGranted = true
        sp.edit().putBoolean(KEY_PERMISSION_CONSENT, true).apply()
        showConsentDialog = false
        launchRuntimePermissions()
    }

    /** 用户选择退出：结束整个任务栈并杀进程 */
    fun onConsentExit() {
        (context as? Activity)?.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ===== 应急"保存并退出"（12h 上限到达 / 系统清理·关闭信号触发）=====
    // RecordStore.finish 内部：写停止时间行 + flush 缓冲 + close（未开始记录时为 no-op，
    // 重复调用安全）。last_tab 已随切页持久化到 SharedPreferences → 下次启动回到上次主页面。
    fun saveAndExitApp() {
        runCatching { RecordStore.finish(System.currentTimeMillis()) }
        (context as? Activity)?.finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ===== 引擎生命周期 =====
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            GpsEngine.start(context.applicationContext)
            CompassEngine.start(context.applicationContext)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            GpsEngine.stop(context.applicationContext)
            CompassEngine.stop(context.applicationContext)
            // 进程/页面销毁时同时停止保活服务
            context.stopService(Intent(context, LocationKeepAliveService::class.java))
        }
    }

    // ===== 后台策略（前台服务保活 + 状态区分超时，定位状态不丢）=====
    // ON_PAUSE（app 仍可见）：立即启动前台服务 → 此时启动的 location FGS 拥有后台定位权
    //   （Android 12+ 限制：后台启动的 FGS 无权访问 location；可见时启动则豁免）。
    //   通知：后台保活 FGS 通知是唯一常驻通知，文案按状态动态显示并**可点击回前台**：
    //     - 记录中 RECORDING → "正在记录，10 分钟未切换回前台将自动退出"
    //     - 未记录 / 暂停挂起     → "正在运行，将在 1 分钟后退出"
    //   唤醒：RECORDING 后台由服务持 PARTIAL_WAKE_LOCK 防 Doze（1s 高频 GPS 写 CSV）；
    //   未记录/暂停已降频无需锁。回前台/停服务即释放。
    //   超时：
    //     - RECORDING → 10 分钟硬超时（RECORDING_BACKGROUND_AUTO_EXIT_MS）：连续后台未回前台
    //       → 保存数据并停止记录 + 退出应用，防"忘了关"导致 1s 高频 GPS 空转耗电。
    //     - 未记录 / 暂停挂起 → 1 分钟（IDLE_BACKGROUND_AUTO_STOP_MS）：停保活服务 + GPS + 指南针，
    //       进程可回收（不主动杀进程；暂停会话数据已刷盘、上下文保留在内存，进程活着回前台可继续）。
    // ON_RESUME（回前台）：取消所有定时器，停止保活服务（通知消失、唤醒锁释放）；
    //   引擎若已被超时停止则全量重启，否则恢复 1s 高频 + 指南针重启
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(lifecycleOwner) {
        val keepAliveIntent = Intent(context, LocationKeepAliveService::class.java)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleHandler.removeCallbacksAndMessages(null)
                    // 前置守卫：系统权限对话框（首启申请/后台被拒重申请）弹出时本 Activity 也会收到
                    // ON_PAUSE，但此刻定位权限尚未授予 —— 启动 location 型前台服务会直接抛
                    // SecurityException（Android 14+ 硬校验）导致闪退。因此未授权时跳过 FGS 启动。
                    val grantedNow = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!grantedNow) return@LifecycleEventObserver
                    // 跳系统设置页（备份授权"所有文件访问"等）引发的 ON_PAUSE：此刻 app 已处于
                    // 后台，location 型 startForeground 会被 eligible-state 校验拒绝，服务无法
                    // 在时限内前台化 → ForegroundServiceDidNotStartInTimeException 闪退。
                    // 本次 ON_PAUSE 跳过 FGS 启动（降频/超时逻辑照常走，见下方）。
                    val suppressKeepAlive = RecordBackup.suppressKeepAliveOnce
                    RecordBackup.suppressKeepAliveOnce = false
                    // 可见期启动 FGS：location 访问权有效；extra 携带记录状态供服务设置通知文案 + 唤醒锁
                    if (!suppressKeepAlive) {
                        val si = Intent(context, LocationKeepAliveService::class.java)
                            .putExtra(LocationKeepAliveService.EXTRA_RECORD_STATE, recordStateName)
                        runCatching { context.startForegroundService(si) }
                    }
                    // 未记录 / 暂停挂起：后台无需 1s 采样 → 主动降频 60s 省电
                    //（RECORDING 由服务保持 1s 高频写 CSV，此处不降频）
                    if (recordStateName != RecordState.RECORDING.name) {
                        GpsEngine.backgroundPause()
                    }
                    // 60s：停指南针（传感器省电；记录中后台同样不需要指南针，GPS 保活由服务维持）
                    lifecycleHandler.postDelayed({
                        CompassEngine.stop(context.applicationContext)
                    }, COMPASS_STOP_DELAY_MS)
                    if (recordStateName == RecordState.RECORDING.name) {
                        // 记录中：10 分钟硬超时。回前台（ON_RESUME）会 removeCallbacks 取消，
                        // 能走到这里 = 连续后台 10 分钟未回前台 → 保存数据并退出，防忘关空转耗电。
                        // RecordStore.finish 幂等：tick 若同时到 12h 上限，重复调用安全。
                        lifecycleHandler.postDelayed({
                            saveAndExitApp()
                        }, RECORDING_BACKGROUND_AUTO_EXIT_MS)
                    } else {
                        // 未记录 / 暂停挂起：1 分钟后停保活服务 + GPS + 指南针，进程可回收
                        //（不杀进程：暂停会话上下文保留在内存，进程活着回前台点"继续记录"仍可续）
                        lifecycleHandler.postDelayed({
                            runCatching { context.stopService(keepAliveIntent) }
                            GpsEngine.stop(context.applicationContext)
                            CompassEngine.stop(context.applicationContext)
                        }, IDLE_BACKGROUND_AUTO_STOP_MS)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleHandler.removeCallbacksAndMessages(null)
                    context.stopService(keepAliveIntent)
                    // 兜底清零：后台超时停引擎 / 会话已结束后，NavigationPage 的 tick 协程
                    // 可能受 Compose 后台重组延迟影响残留几秒累计；ON_RESUME 时若已无活动会话
                    // 且 state=STOPPED 但 elapsed>0，统一清零让 UI 显示干净的"开始记录"。
                    if (recordStateName == RecordState.STOPPED.name
                        && elapsedMs > 0
                        && !RecordStore.isRecording()
                    ) {
                        elapsedMs = 0; distanceM = 0f; maxSpeedMs = 0f
                    }
                    // 用实时权限检查而非闭包捕获的 hasPermission（Observer 不会因权限变化重建）
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        // 后台超时已彻底停止 → 全量重启（含 AGPS 刷新，加速重新搜星）；
                        // 未超时（60s 降频保活中）→ 仅恢复 1s 高频
                        if (GpsEngine.isStarted()) {
                            GpsEngine.foregroundResume()
                        } else {
                            GpsEngine.start(context.applicationContext)
                        }
                        CompassEngine.start(context.applicationContext)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleHandler.removeCallbacksAndMessages(null)
        }
    }

    // ===== Pager 状态：pageIndex 作为 source of truth，冷/热启动都从 sp 恢复 =====
    // 关键修复：避免 pagerState 初始化时 currentPage=0 立即被 collect 捕获，导致 sp 被错误写入 0
    val pagerState = rememberPagerState { TABS.size }  // 不用 initialPage（首次默认 0）
    val scope = rememberCoroutineScope()
    var pagerInitialized by remember { mutableStateOf(false) }

    // 启动时把 pager 同步到 pageIndex（仅首次），避免 collect 误触发
    LaunchedEffect(Unit) {
        if (pagerState.currentPage != pageIndex) {
            pagerState.scrollToPage(pageIndex)
        }
        pagerInitialized = true
    }

    // 滑动 pager → 更新 pageIndex（仅在初始化完成后，避免 init 时 currentPage=0 污染）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { idx ->
                if (pagerInitialized && idx != pageIndex) {
                    pageIndex = idx
                }
            }
    }

    // pageIndex 变化（用户点 tab 或初始化）→ 写 sp + 平滑滚动 pager
    LaunchedEffect(pageIndex) {
        sp.edit().putInt(KEY_LAST_TAB, pageIndex).apply()
        if (pagerInitialized && pagerState.currentPage != pageIndex) {
            scope.launch { pagerState.animateScrollToPage(pageIndex) }
        }
    }

    // ===== 记录中护屏（防烧屏）：无触摸计时 → 全屏只留速度图表/速度值/时间 =====
    // 启用条件：记录中（RECORDING）且连续 IDLE_ENTER_MS 无任何触摸动作；
    // 任意一次触摸（全局 Initial 通道捕获，不受下层控件是否消费事件影响）立即退出并重新计时。
    // 记录本身不受影响：护屏只是叠加层，记录页仍在组合中，CSV 每秒采样照常写入。
    val recordingNow = recordStateName == RecordState.RECORDING.name
    val lastTouchMs = remember { AtomicLong(System.currentTimeMillis()) }
    var screenSaverOn by remember { mutableStateOf(false) }
    LaunchedEffect(recordingNow, screenSaverOn) {
        if (!recordingNow) {
            screenSaverOn = false
            return@LaunchedEffect
        }
        while (isActive) {
            delay(TOUCH_POLL_MS)
            val idleMs = System.currentTimeMillis() - lastTouchMs.get()
            val want = idleMs >= IDLE_ENTER_MS
            if (want != screenSaverOn) screenSaverOn = want
        }
    }

    // ===== 记录中屏幕常亮（FLAG_KEEP_SCREEN_ON）=====
    // 只是解除系统"自动息屏"锁：窗口 flag，仅在窗口可见（应用在前台）期间生效；
    // 停止记录 / 切后台 / 离开组合时 onDispose 自动清除，系统息屏策略立刻恢复，无需手动管理。
    // （不用 WakeLock：无需任何权限，也不存在忘记释放导致常亮的隐患。）
    val hostWindow = (context as? Activity)?.window
    DisposableEffect(recordingNow) {
        if (recordingNow) {
            hostWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            hostWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ===== 开始记录提示（10s 自动消失，置顶不挡速度表）=====
    val showAwakeHint = remember { mutableStateOf(false) }
    LaunchedEffect(recordingNow) {
        if (recordingNow) {
            showAwakeHint.value = true
            delay(10_000)
            showAwakeHint.value = false
        } else {
            showAwakeHint.value = false
        }
    }

    // 根 Box：承载页面内容 + 记录中护屏叠加层（护屏需盖住顶栏与底部 tab，故必须同级）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 全局触摸时间戳：Initial 通道是整棵树里最先收到事件的时机，
            // 子控件消费与否都能记录到，作为"有触摸动作"的唯一依据
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouchMs.set(System.currentTimeMillis())
                    }
                }
            },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 顶部占位：保留原顶栏高度，防止页面内容被系统状态栏/通知覆盖
        // statusBarsPadding：让顶栏自动避开系统状态栏（enableEdgeToEdge 下状态栏透明覆盖在 app 顶部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TopbarBackground)
                .statusBarsPadding()
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
        }

        // 页面内容（可左右滑动）
        Box(Modifier.weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 0.dp,
                key = { idx -> idx },
            ) { idx ->
                when (idx) {
                    0 -> CompassPage()
                    1 -> HistogramPage()
                    else -> NavigationPage(
                        recordState = RecordState.valueOf(recordStateName),
                        elapsedMs = elapsedMs,
                        distanceM = distanceM,
                        maxSpeedMs = maxSpeedMs,
                        lastFixTime = lastFixTime,
                        speedLimit = speedLimit,
                        onSpeedLimit = { speedLimit = it },
                        onRecordState = { recordStateName = it.name },
                        onElapsed = { elapsedMs = it },
                        onDistance = { distanceM = it },
                        onMaxSpeed = { maxSpeedMs = it },
                        onLastFixTime = { lastFixTime = it },
                        onRecordingLimitReached = { saveAndExitApp() },
                    )
                }
            }
        }

        // 底部 tab（居中纯文本，参考截图风格；加 navigationBarsPadding 避免被系统导航栏遮挡）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MenuBackground)
                .navigationBarsPadding()
                .padding(vertical = 14.dp),
        ) {
            TABS.forEachIndexed { idx, label ->
                val selected = idx == pageIndex
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MenuSelected else MenuUnselected,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { pageIndex = idx }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }

        // 记录中护屏：全屏（隐藏状态栏/导航栏），只显示速度图表 + 放大的速度值 + 当前时间
        if (screenSaverOn) {
            SpeedScreenSaver(speedLimit = speedLimit)
        }

        // 开始记录提示条（10s 自动消失）：置顶悬浮、不拦截触摸（无 pointer 修饰符的
        // Box/Column/Text 不参与命中测试，点击全部穿透到下层），护屏期间不显示
        if (showAwakeHint.value && !screenSaverOn) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6141414))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "推荐打开自动亮度，以提高使用舒适度",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "2小时内必须点击下屏幕，否则自动息屏",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp,
                )
            }
        }
    }

    // 已确认过声明但权限被拒/未授（如用户在系统弹窗点了拒绝）→ 重新弹权限请求
    if (consentGranted && !hasPermission) {
        LaunchedEffect(Unit) {
            launchRuntimePermissions()
        }
    }

    // ===== 返回键退出确认：拦截系统返回，弹出确认框；确认后彻底退出（不留后台）=====
    // 首次启动的权限声明弹窗显示时不拦截（该弹窗自身已处理返回键 = 退出）
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = !showConsentDialog) {
        showExitDialog = true
    }
    if (showExitDialog) {
        val recording = recordStateName == RecordState.RECORDING.name
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (recording) {
                        "确定退出 GPS 工具箱吗？\n\n正在记录中：退出将立即保存当前记录并停止。"
                    } else {
                        "确定退出 GPS 工具箱吗？退出后应用不会在后台运行。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    // RecordStore.finish 未记录时为 no-op，重复调用安全；
                    // 记录中则写停止行 + flush + close 后退出（与 onTrimMemory/onDestroy 同路径）
                    saveAndExitApp()
                }) {
                    Text("退出", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // ===== 首次启动权限声明弹窗（确认后才申请对应权限；可选退出）=====
    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { onConsentExit() },  // 返回键/点外部 = 退出
            title = { Text("权限声明", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    ConsentRow("📡", "定位权限", "获取定位以显示卫星数据、速度测量")
                    Spacer(Modifier.height(10.dp))
                    ConsentRow("🌐", "网络权限", "用于 AGPS 辅助定位，下载星历加速定位速度")
                    Spacer(Modifier.height(10.dp))
                    ConsentRow("💾", "存储权限", "备份、还原记录需要存储权限才能正常工作")
                    Spacer(Modifier.height(10.dp))
                    ConsentRow(
                        "🔔", "通知权限",
                        "后台保活常驻通知，避免记录中进程被系统休眠" +
                            "（Android 13 及以上需手动授权）",
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "不在记录状态时，应用会主动退出，不驻留后台、不占用内存。",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConsentConfirm() }) {
                    Text("同意并继续", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { onConsentExit() }) {
                    Text("退出")
                }
            },
        )
    }
}

/** 权限声明弹窗的一行说明（icon + 权限名 + 用途） */
@Composable
private fun ConsentRow(icon: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(desc, fontSize = 13.sp, color = Color(0xFF888888))
        }
    }
}

/** 权限声明已确认标志（SharedPreferences key） */
private const val KEY_PERMISSION_CONSENT = "permission_consent"

/** 上次停留的 tab 索引（SharedPreferences key），冷启动恢复用 */
private const val KEY_LAST_TAB = "last_tab_index"

/** 后台 60s：停指南针（传感器省电），定位状态由保活前台服务继续维持 */
private const val COMPASS_STOP_DELAY_MS = 60_000L

/**
 * 空闲后台回收超时（1 分钟，无活动记录会话或暂停挂起）。
 * 超过此时长未回前台 → 停止保活服务 + 彻底停掉 GPS/指南针，进程可被系统回收。
 * 不主动杀进程：暂停会话的上下文保留在内存、数据已刷盘，进程活着回前台仍可"继续记录"。
 * 与后台通知文案"正在运行，将在 1 分钟后退出"保持一致。
 */
private const val IDLE_BACKGROUND_AUTO_STOP_MS = 60_000L

/**
 * 记录中（RECORDING）后台硬超时（10 分钟）。
 * 连续后台超过此时长未切回前台 → 保存数据并停止记录 + 退出应用。
 * 防"忘了关"导致 1s 高频 GPS + 唤醒锁在后台无限空转耗电（正常使用记录时 app 在前台）。
 * 回前台（ON_RESUME）会取消该定时器。与后台通知文案"10 分钟未切换回前台将自动退出"保持一致。
 */
private const val RECORDING_BACKGROUND_AUTO_EXIT_MS = 10 * 60_000L