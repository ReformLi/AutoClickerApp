package com.hpu.autoclicker.feature.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.hpu.autoclicker.core.util.DebugLog
import com.hpu.autoclicker.core.util.ScriptNaming
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppConfirmMessage
import com.hpu.autoclicker.core.components.AppDialogActions
import com.hpu.autoclicker.core.components.AppDialogPanel
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppOpacityDialogContent
import com.hpu.autoclicker.core.components.AppOpacityDialogState
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.ui.FloatingWindowBounds
import com.hpu.autoclicker.core.ui.OpacityDragPreview
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.ui.ServiceDialogs
import com.hpu.autoclicker.core.ui.stripPlatformPanel
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import com.hpu.autoclicker.core.theme.Dimens
import kotlin.jvm.java

/**
 * 悬浮窗服务
 * 功能：
 * 1. 显示一个可拖动的悬浮按钮（初始吸附屏幕左侧或右侧）
 * 2. 按住主按钮拖动可移动整个悬浮窗，松手后自动吸附到左/右边缘
 * 3. 点击主按钮会在其下方展开/折叠操作栏
 * 4. 操作栏内的按钮可绑定自定义功能（示例中退出按钮可关闭服务）
 */
class FloatingWindowService : Service() {

    companion object {
        /** 双击两下的按压时长：不用 0，0 时长容易被系统判为无效手势 */
        private const val DOUBLE_TAP_PRESS_MS = 50L

        /** 两次按压之间的抬起间隔 */
        private const val DOUBLE_TAP_GAP_MS = 100L
    }

    // WindowManager 用于管理悬浮窗的显示、位置和参数
    private lateinit var windowManager: WindowManager

    // 悬浮窗的根布局（由原 activity_floating_window_service.xml 迁移为 Compose 内容）
    private lateinit var rootView: ComposeView

    /** ComposeView 在 Service 中所需的生命周期宿主（缺失会抛 ViewTreeLifecycleOwner not found） */
    private val composeHost = ServiceComposeHost()

    /** 悬浮窗 UI 状态：展开/运行，替代原来 findViewById + isEnabled/setImageResource 的手工刷新 */
    private val ui = FloatingServiceUiState()

    // 主按钮 / 停止按钮在「窗口内」的位置（屏幕坐标 = 窗口内坐标 + layoutParams 偏移）
    // 用于运行期判断触摸是否落在允许的按钮上（等价原 findViewById + getLocationOnScreen）
    private var mainBtnWindowRect = Rect()
    private var stopBtnWindowRect = Rect()

    // 悬浮窗的布局参数（位置、大小、类型等）
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ========== 拖动相关变量 ==========
    // 记录手指按下时的初始坐标（屏幕绝对坐标）
    private var initialX = 0f
    private var initialY = 0f

    // 记录手指按下时悬浮窗的位置
    private var initialWindowX = 0
    private var initialWindowY = 0

    // 是否正在拖动（用于区分点击和拖动）
    private var isDragging = false

    // 当前竖排吸附边缘：true = 左侧，false = 右侧
    private var isDockedLeft = true

    // 当前横排吸附边缘：true = 顶部，false = 底部
    private var isDockedTop = true

    // 当前吸附轴：true = 贴上 / 下边（横排或横屏），false = 贴左 / 右边
    private var dockTopBottom = false

    // 拖动期间固定的安全区，避免每帧用旧坐标重算原点偏移
    private val dragBounds = Rect()

    /** 上次已知的可见区域尺寸，用来区分「真的横竖屏切换」与深色模式 / 字体变化 */
    private var lastScreenW = 0
    private var lastScreenH = 0


    /**
     * 当前悬浮的点位标记。
     * 原来是 companion object 里的静态列表：服务被系统重建（START_STICKY / 快速关-开）时，
     * 旧实例残留的视图仍留在列表里，removeView 会对着已销毁的窗口报错，
     * 且新实例看不到真正挂在屏幕上的那些点。
     */
    private val clickPoints = mutableListOf<ClickPoint>()

    // 运行状态（读写统一落到 ui 状态，UI 自动刷新启动/停止图标与按钮可用性）
    private var isRunning: Boolean
        get() = ui.isRunning
        set(value) { ui.isRunning = value }
    private val runHandler = Handler(Looper.getMainLooper())

    /** 本轮运行中是否有手势被系统拒绝；下一轮开始时中断并提示 */
    private var gestureRejected = false

    // 用 pairId 映射管道视图及其参数
    private val pipeMap = mutableMapOf<Int, Pair<PipeLineView, WindowManager.LayoutParams>>()

    private var pointSettingsDialog: android.app.AlertDialog? = null

    private var settingsDialog: AlertDialog? = null
    private var maxLoopCount = 0   // 0或负数表示无限循环，>0 为固定次数

    /** 当前点位来自哪个已保存脚本（用于「保存当前点位」预填名称） */
    private var currentScriptName: String? = null

    /** 点击 / 滑动点位的不透明度（用户设置值；运行期会临时压到 ≤0.3） */
    private var pointAlpha = 1f

    /** 拖动透明度滑块时的 20% 预览条（弹框隐藏，只留进行条） */
    private var opacityPreview: OpacityDragPreview? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.syncFrom(this)
        DebugLog.d("FloatingWindow", "Service onCreate")

        // 获取 WindowManager 系统服务
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // === 原：LayoutInflater.inflate(R.layout.activity_floating_window_service) ===
        // === 现：ComposeView + Compose 内容（FloatingServiceContent） ===
        rootView = ComposeView(this)
        composeHost.attach(rootView)   // 注入生命周期宿主，否则 setContent 会抛异常
        // 主按钮触摸状态机 + 7 个操作按钮的回调（等价原 setupTouchAndClick 里的 findViewById 绑定）
        rootView.setContent {
            AutoClickerTheme {
                FloatingServiceContent(
                    state = ui,
                    onMainTouch = { event -> onMainTouch(event) },
                    onStart = { if (isRunning) stopRunning() else startRunning() },
                    onAdd = { addClickPoint(-1, true) },   // isStart:随意
                    onSlide = { addSwipePair() },
                    onReduce = { removeLastClickPoint() },
                    onSettings = { showGlobalSettingsDialog() },
                    onOpacity = { showOpacityDialog() },
                    onExit = { stopSelf() },
                    // 上报按钮在窗口内的位置，供运行期「触摸是否落在允许按钮上」的判定使用
                    onMainBounds = { rect -> mainBtnWindowRect = rect },
                    onStopBounds = { rect -> stopBtnWindowRect = rect },
                )
            }
        }

        // 配置悬浮窗的布局参数
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,   // 宽度自适应内容
            WindowManager.LayoutParams.WRAP_CONTENT,   // 高度自适应内容
            // 根据 Android 版本选择合适的窗口类型（Android 8.0 以上必须用 TYPE_APPLICATION_OVERLAY）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 窗口标志：
            // FLAG_NOT_FOCUSABLE：不获取焦点，避免影响底层应用触摸
            // FLAG_LAYOUT_NO_LIMITS：允许窗口超出屏幕边界（后续代码会限制垂直范围）
            // FLAG_NOT_TOUCH_MODAL：窗口外的触摸事件可以传递给下层
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT  // 半透明像素格式（实际背景由布局决定）
        )

        // Compose 内容在 addView 之前无法测量，折叠态高度固定为「8dp 间距 + 40dp 主按钮 + 8dp 间距」= 56dp，
        // 用它估算初始居中位置（后续布局完成后再由 toggleExpanded/snapToEdge 校正）
        val h = dp(56)
        DebugLog.d("FloatingWindow", "初始高度估算：高=$h")

        // 设置初始位置：左侧吸附，垂直居中
        layoutParams.gravity = Gravity.START or Gravity.TOP  // 以屏幕左上角为原点
        layoutParams.x = 0
        layoutParams.y = (getScreenHeight() - h) / 2

        lastScreenW = getScreenWidth()
        lastScreenH = getScreenHeight()
        syncLayoutMode()

        // 恢复用户上次保存的悬浮窗透明度（与代点器各存各的，互不干扰）
        rootView.alpha = ClickerScriptStore.getWindowAlpha(this)
        pointAlpha = ClickerScriptStore.getPointAlpha(this)

        // 将悬浮窗添加到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
            DebugLog.d("FloatingWindow", "addView 成功")
        } catch (e: Exception) {
            Log.e("FloatingWindow", "addView 失败", e)
        }

        // 内容尺寸变化（展开 / 折叠、横竖屏重排、运行态切换）后重新把窗口放回屏幕内；
        // 只在尺寸真的变化时处理，避免无色值等普通重组反复钳制。
        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                syncLayoutMode()
                rootView.post { if (::rootView.isInitialized) clampMainWindow() }
            }
        }

        // 开启前台服务，防止系统杀死后台 Service（需要 FOREGROUND_SERVICE 权限）
        startForegroundIfNeeded()

        // 注册广播
        val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        ContextCompat.registerReceiver(this, configReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // 获取屏幕宽度
    private fun getScreenWidth(): Int {
        val rect = android.graphics.Rect()
        windowManager.defaultDisplay.getRectSize(rect)
        return rect.width()
    }
    // 获取屏幕高度
    private fun getScreenHeight(): Int {
        val rect = android.graphics.Rect()
        windowManager.defaultDisplay.getRectSize(rect)
        return rect.height()
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                handleScreenSizeChanged()
            }
        }
    }

    /**
     * 横竖屏切换 / 分屏尺寸变化时系统不会重建 Service，窗口坐标仍是旧方向的，
     * 必须自己按新尺寸重排并钳制，否则悬浮窗会留在屏幕外甚至完全不可见。
     * 这里用 Service 的 ComponentCallbacks 回调，广播作为部分 ROM 的兜底，二者都做尺寸去重。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleScreenSizeChanged()
    }

    private fun handleScreenSizeChanged() {
        if (!::rootView.isInitialized || !::layoutParams.isInitialized) return
        val w = getScreenWidth()
        val h = getScreenHeight()
        if (w == lastScreenW && h == lastScreenH) return   // 深色模式 / 字体大小等，窗口几何未变
        lastScreenW = w
        lastScreenH = h
        syncLayoutMode()
        // 等 Compose 按新尺寸重排后再钳制，否则读到的还是旧方向的宽高
        rootView.post { if (::rootView.isInitialized) clampAllWindowsToScreen() }
        // 旋转动画期间视图尺寸/位置可能还在过渡，延迟再校一次
        rootView.postDelayed({
            if (::rootView.isInitialized) {
                syncLayoutMode()
                clampAllWindowsToScreen()
            }
        }, 350)
    }

    /** 竖排放不下时改用横向展开栏：横排长度相同，但占用的是更充裕的屏幕宽度 */
    private fun syncLayoutMode() {
        if (!::rootView.isInitialized || !::layoutParams.isInitialized) return
        ui.horizontal = shouldUseHorizontalLayout(currentExpandedRows())
        val topBottom = docksTopBottom()
        if (topBottom != dockTopBottom) {
            // 换吸附轴（进入横排 / 转横屏 / 转回竖屏）：按窗口左上角所在的半区选最近的那条边。
            // 不用宽高算中心——旋转瞬间拿到的还是旧方向的视口尺寸。
            val bounds = FloatingWindowBounds.safeBounds(windowManager, rootView, layoutParams, edgeMargin())
            if (topBottom) {
                isDockedTop = layoutParams.y < (bounds.top + bounds.bottom) / 2
            } else {
                isDockedLeft = layoutParams.x < (bounds.left + bounds.right) / 2
            }
            dockTopBottom = topBottom
        }
    }

    /** 横屏或横排时按钮条长边是水平的，吸附上下边；否则（竖排、竖屏折叠）贴左右边 */
    private fun docksTopBottom(): Boolean =
        ui.horizontal || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun currentExpandedRows(): Int = when {
        !ui.isExpanded -> 0
        ui.isRunning -> 1
        else -> 7
    }

    private fun shouldUseHorizontalLayout(rows: Int): Boolean {
        if (rows <= 0) return false
        // 主按钮 40dp + 上下各 8dp 内边距 + 每行 40dp 按钮与 4dp 间距
        val length = dp(56 + rows * 44)
        val size = FloatingWindowBounds.usableSize(windowManager, rootView, layoutParams)
        return length > size.y && length <= size.x
    }

    /** 主窗口按当前吸附轴贴边（上下或左右），另一轴只做安全区钳制，保留用户拖动的位置 */
    private fun clampMainWindow() {
        if (!::rootView.isInitialized || !::layoutParams.isInitialized) return
        val bounds = FloatingWindowBounds.safeBounds(windowManager, rootView, layoutParams, edgeMargin())
        if (docksTopBottom()) {
            layoutParams.y = if (isDockedTop) {
                bounds.top
            } else {
                (bounds.bottom - rootView.height).coerceAtLeast(bounds.top)
            }
            layoutParams.x = FloatingWindowBounds.clampInside(
                layoutParams.x, rootView.width, bounds.left, bounds.right
            )
        } else {
            layoutParams.x = if (isDockedLeft) {
                bounds.left
            } else {
                (bounds.right - rootView.width).coerceAtLeast(bounds.left)
            }
            layoutParams.y = FloatingWindowBounds.clampInside(
                layoutParams.y, rootView.height, bounds.top, bounds.bottom
            )
        }
        try {
            windowManager.updateViewLayout(rootView, layoutParams)
        } catch (e: Exception) {
            Log.e("FloatingWindow", "clampMainWindow 失败", e)
        }
    }

    private fun edgeMargin(): Int = dp(12)

    private fun clampAllWindowsToScreen() {
        // 主悬浮窗
        clampMainWindow()

        // 所有点击点
        for (point in clickPoints) {
            point.params?.let { params ->
                val size = FloatingWindowBounds.usableSize(windowManager, point.view, params)
                params.x = FloatingWindowBounds.clampOffset(params.x, point.view.width, size.x)
                params.y = FloatingWindowBounds.clampOffset(params.y, point.view.height, size.y)
                windowManager.updateViewLayout(point.view, params)
                point.x = params.x.toFloat()
                point.y = params.y.toFloat()
            }
            // 如果是滑动对，更新管道
            updatePipeLine(point.pairId)
        }

        // 管道使用全屏透明绘制层，屏幕尺寸变化时只需要更新绘制层尺寸
        val usable = FloatingWindowBounds.usableSize(windowManager, rootView, layoutParams)
        for ((pairId, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            params.x = 0
            params.y = 0
            params.width = usable.x
            params.height = usable.y
            windowManager.updateViewLayout(lineView, params)
            updatePipeLine(pairId)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // 发送广播通知 Activity 服务已停止
        val broadcastIntent = Intent("com.hpu.autoclicker.SERVICE_STOPPED").setPackage(packageName)
        sendBroadcast(broadcastIntent)
        // overlay 弹框窗口不随服务销毁自动移除，先统一收掉，避免留在桌面上点不掉
        ServiceDialogs.dismissAll(this)
        stopRunning()
        isRunning = false
        runHandler.removeCallbacksAndMessages(null)
        for (point in clickPoints) {
            releasePoint(point)
        }
        clickPoints.clear()
        if (::rootView.isInitialized) {
            removeWindowQuietly(rootView)
        }
        pipeMap.values.forEach { (lineView, _) -> removeWindowQuietly(lineView) }
        pipeMap.clear()
        pointSettingsDialog?.dismiss()
        pointSettingsDialog = null
        opacityPreview?.hide()
        opacityPreview = null

        composeHost.destroy()   // 释放 ComposeView 的生命周期宿主
        unregisterReceiver(configReceiver)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_CLICKING") {
            stopRunning()
            // 与代点器 / 录点器一致：由「停止」指令进来的这次启动不再粘回，
            // 否则服务被系统杀掉后会重建出一个用户认为已经关掉的悬浮窗
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * 主按钮触摸监听（按住拖动 + 点击展开）。
     * 由 Compose 的 pointerInteropFilter 回调进来，坐标同为屏幕绝对坐标；
     * 7 个操作按钮的点击回调已在 onCreate 的 setContent 中绑定。
     */
    private fun onMainTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下时的位置和窗口坐标，准备拖动
                initialX = event.rawX
                initialY = event.rawY
                initialWindowX = layoutParams.x
                initialWindowY = layoutParams.y
                dragBounds.set(FloatingWindowBounds.safeBounds(windowManager, rootView, layoutParams, edgeMargin()))
                isDragging = false   // 重置拖动状态
                ui.mainPressed = true   // 按下反馈（等价原 bg_floating_button 的 pressed 态）
                true  // 必须返回 true，否则后续事件收不到
            }

            MotionEvent.ACTION_MOVE -> {
                // 计算手指移动的距离
                val dx = event.rawX - initialX
                val dy = event.rawY - initialY

                // 移动超过 10 像素时，进入拖动模式
                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                    isDragging = true
                    ui.mainPressed = false
                }
                if (isDragging) {
                    // 只允许在安全区内拖动，避免拖出屏幕后松手收不回来
                    layoutParams.x = FloatingWindowBounds.clampInside(
                        initialWindowX + dx.toInt(), rootView.width, dragBounds.left, dragBounds.right
                    )
                    layoutParams.y = FloatingWindowBounds.clampInside(
                        initialWindowY + dy.toInt(), rootView.height, dragBounds.top, dragBounds.bottom
                    )
                    // 应用新位置
                    windowManager.updateViewLayout(rootView, layoutParams)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.mainPressed = false
                if (isDragging) {
                    // 拖动结束，吸附到左侧或右侧边缘
                    snapToEdge()
                } else {
                    // 没有拖动，视为点击，切换展开/折叠
                    toggleExpanded()
                }
                isDragging = false
                true
            }

            else -> false
        }
    }

    /**
     * 切换操作栏的展开/折叠状态，并重新校正悬浮窗位置确保不超出屏幕
     */
    private fun toggleExpanded() {
        // 切换展开/折叠（原为切换 expandedControls 的 visibility，现由 Compose 状态驱动）
        ui.isExpanded = !ui.isExpanded
        if (!ui.isExpanded) {
            // 折叠后按钮不再显示，其位置信息作废，避免运行期触摸判定误命中
            stopBtnWindowRect = Rect()
        }

        // 行数变化可能让竖排装不下：先决定横排 / 竖排，再等 Compose 重排后钳制窗口
        syncLayoutMode()
        rootView.doOnLayout { clampMainWindow() }
    }

    /**
     * 松手吸附：长边与吸附边缘平行，另一轴保留松手时的位置。
     * 横排 / 横屏贴上 / 下边（水平位置不强制），竖排贴左 / 右边。
     */
    private fun snapToEdge() {
        rootView.post {
            val bounds = FloatingWindowBounds.safeBounds(windowManager, rootView, layoutParams, edgeMargin())
            val viewWidth = rootView.width
            val viewHeight = rootView.height
            if (docksTopBottom()) {
                isDockedTop = layoutParams.y + viewHeight / 2 < (bounds.top + bounds.bottom) / 2
                layoutParams.y = if (isDockedTop) {
                    bounds.top
                } else {
                    (bounds.bottom - viewHeight).coerceAtLeast(bounds.top)
                }
                layoutParams.x = FloatingWindowBounds.clampInside(
                    layoutParams.x, viewWidth, bounds.left, bounds.right
                )
            } else {
                isDockedLeft = layoutParams.x + viewWidth / 2 < (bounds.left + bounds.right) / 2
                layoutParams.x = if (isDockedLeft) {
                    bounds.left
                } else {
                    (bounds.right - viewWidth).coerceAtLeast(bounds.left)
                }
                layoutParams.y = FloatingWindowBounds.clampInside(
                    layoutParams.y, viewHeight, bounds.top, bounds.bottom
                )
            }
            try {
                windowManager.updateViewLayout(rootView, layoutParams)
            } catch (e: Exception) {
                Log.e("FloatingWindow", "snapToEdge 失败", e)
            }
        }
    }

    /**
     * 开启前台服务（Android 8.0 以上必须）
     * 需要声明权限：android.permission.FOREGROUND_SERVICE
     * Android 14(API 34) 起还需与 foregroundServiceType 对应的
     * android.permission.FOREGROUND_SERVICE_DATA_SYNC，否则 startForeground 抛 SecurityException
     */
    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "floating"
            val channel = NotificationChannel(channelId, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            // 停止按钮的 Intent（发送到 Service 自身）
            val stopIntent = Intent(this, FloatingWindowService::class.java).apply {
                action = "STOP_CLICKING"
            }
            val pendingStop = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("连点器运行中")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)   // 可用你自己的图标
                .addAction(android.R.drawable.ic_media_pause, "停止", pendingStop)
                .build()
            startForeground(1, notification)
        }
    }

    /**
     * Android 15(API 35) 起 dataSync 类型前台服务有 6 小时 / 24 小时运行时长上限，
     * 超时后系统回调本方法，必须在几秒内停止服务，
     * 否则系统以 RemoteServiceException 结束应用进程。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            super.onTimeout(startId, fgsType)
        }
        Log.w("FloatingWindow", "dataSync 前台服务达到运行时长上限，主动停止")
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 下一个点位编号：当前最大 id + 1。
     * 原来用可回退的 pointCount 分配，滑动终点会占用一个 id 又把计数减回去，
     * 于是新增点常与已存在的「B」重号；而运行顺序与保存顺序都按 id 排，
     * 表现为点位顺序错乱（ClickPoint 里那句「id 并无唯一」说的就是这件事）。
     */
    private fun nextPointId(): Int = (clickPoints.maxOfOrNull { it.id } ?: 0) + 1

    /** 下一个滑动对编号：同样从已存在的 pairId 推导，删除后不会撞号 */
    private fun nextPairId(): Int =
        (clickPoints.filter { it.pairId != -1 }.maxOfOrNull { it.pairId } ?: 0) + 1

    private fun addClickPoint(pairId: Int,isStart: Boolean) : ClickPoint {
        val id = nextPointId()
        var label : String
        var xAxis : Float

        // 确定初始位置（相隔200px水平）
        val baseX = getScreenWidth() / 2f
        val yAxis = getScreenHeight() / 2f

        if (pairId == -1){
            label = id.toString()
            xAxis = baseX
        }
        else{
            if (isStart){
                label = "A$pairId"
                xAxis = baseX - 100
            }else{
                label = "B$pairId"
                xAxis = baseX + 100
            }
        }
        // 创建带编号的点位标记
        val pointView = createPointMarker(label, pairId, isStart) // 文字显示 label

        val initX = xAxis - pointView.layoutParams.width / 2
        val initY = yAxis - pointView.layoutParams.height / 2

        val pointParams = WindowManager.LayoutParams(
            pointView.layoutParams.width,
            pointView.layoutParams.height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = initX.toInt()
            y = initY.toInt()
        }

        val point = ClickPoint(
            id = id,
            x = initX,
            y = initY,
            view = pointView,
            name = label,
            type = if (pairId == -1) "点击" else {if (isStart) "滑动起点" else "滑动终点"},
            touchDuration = if (pairId == -1) 150 else 500,
            params = pointParams,
            pairId = pairId,
            isStart = isStart
        )

        pointView.setOnLongClickListener {
            if (point.pairId != -1) {
                // 滑动对：取起点作为编辑对象
                val startPoint = clickPoints.find { it.pairId == point.pairId && it.isStart }
                if (startPoint != null) showPointSettingsDialog(startPoint)
            } else {
                showPointSettingsDialog(point)
            }
            true
        }

        clickPoints.add(point)

        // 应用用户设置的点位透明度（运行期沿用同一数值）
        pointView.alpha = pointAlpha
        windowManager.addView(pointView, pointParams)
        setupPointDrag(pointView, pointParams, point)
        return point
    }



    /**
     * 移除最后一个添加的点击点（编号最大的点）
     * 如果没有任何点，则不执行操作
     */
    private fun removeLastClickPoint() {
        if (clickPoints.isEmpty()) {
            Toast.makeText(this, "没有可以删除的点击点", Toast.LENGTH_SHORT).show()
            return
        }
        val last = clickPoints.last()
        if (last.pairId != -1) {
            val pairId = last.pairId
            val pointsToRemove = clickPoints.filter { it.pairId == pairId }
            pointsToRemove.forEach { releasePoint(it) }
            clickPoints.removeAll(pointsToRemove)

            // 移除管道
            pipeMap[pairId]?.let { (lineView, _) ->
                removeWindowQuietly(lineView)
            }
            pipeMap.remove(pairId)
            Toast.makeText(this, "已删除滑动对", Toast.LENGTH_SHORT).show()
        } else {
            // 普通点，直接删除
            clickPoints.remove(last)
            releasePoint(last)
            Toast.makeText(this, "已删除点击点 ${last.id}", Toast.LENGTH_SHORT).show()
        }
        // 编号改成从现存点位推导，删除后不需要再回退计数
    }

    /** 摘掉一个点位窗口，同时取消它排队中的长按计时 */
    private fun releasePoint(point: ClickPoint) {
        point.dragHandler?.removeCallbacksAndMessages(null)
        point.dragHandler = null
        removeWindowQuietly(point.view)
    }

    /**
     * removeView 对「没挂着的视图」在部分系统版本上抛 IllegalArgumentException，
     * 而不是仅打印日志。服务销毁 / 批量删除时一旦被抛断，后面的窗口就都摘不掉了。
     */
    private fun removeWindowQuietly(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e("FloatingWindow", "移除悬浮视图失败", e)
        }
    }

    private fun setupPointDrag(view: View, params: WindowManager.LayoutParams, point: ClickPoint) {
        val longPressHandler = Handler(Looper.getMainLooper())
        // 交给点位保存：删除点位时要取消还在排队的长按，否则已删除的点仍会弹出设置框
        point.dragHandler = longPressHandler
        var longPressRunnable: Runnable? = null
        var isDragging = false
        var isLongPressed = false

        var downX = 0f
        var downY = 0f
        var downParamX = 0
        var downParamY = 0

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    isDragging = false
                    isLongPressed = false

                    // 开始 500ms 长按计时
                    longPressRunnable = Runnable {
                        isLongPressed = true
                        showPointSettingsDialog(point)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 500)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // 移动超过 10px，取消长按，进入拖动模式
                    if (!isDragging && !isLongPressed && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        isDragging = true
                    }
                    if (isDragging) {
                        val usable = FloatingWindowBounds.usableSize(windowManager, view, params)
                        params.x = FloatingWindowBounds.clampOffset(
                            (downParamX + dx).toInt(), view.width, usable.x
                        )
                        params.y = FloatingWindowBounds.clampOffset(
                            (downParamY + dy).toInt(), view.height, usable.y
                        )
                        windowManager.updateViewLayout(view, params)
                        point.x = params.x.toFloat()
                        point.y = params.y.toFloat()

                        // 安全更新管道
                        if (point.pairId != -1 && pipeMap.containsKey(point.pairId)) {
                            updatePipeLine(point.pairId)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按计时
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    isDragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun  updatePipeLine(pairId: Int) {
        if (pairId == -1) return
        val startPoint = clickPoints.find { it.pairId == pairId && it.isStart } ?: return
        val endPoint = clickPoints.find { it.pairId == pairId && !it.isStart } ?: return
        val (lineView, _) = pipeMap[pairId] ?: return

        // 两个圆形节点的中心坐标
        lineView.startX = getPointCenterX(startPoint)
        lineView.startY = getPointCenterY(startPoint)
        lineView.endX = getPointCenterX(endPoint)
        lineView.endY = getPointCenterY(endPoint)

        // 管道窗口固定为全屏透明层，拖动时只重绘坐标，不调整窗口尺寸，减少延迟
        lineView.invalidate()
    }

    private fun createPipeLineLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            getScreenWidth(),
            getScreenHeight(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // 管道仅作视觉提示，不能拦截真实触摸或无障碍滑动路径
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }
    }

    private fun addSwipePair() {
        val pairId = nextPairId()

        // 1. 创建管道视图和参数
        val lineView = PipeLineView(this)
        val lineParams = createPipeLineLayoutParams()
        // 存入 map
        pipeMap[pairId] = Pair(lineView, lineParams)
        lineView.alpha = pointAlpha
        lineView.markerRadius = PointMarkerView.RADIUS_DP * resources.displayMetrics.density
        // 2. 先添加管道到窗口（使其位于下层）
        windowManager.addView(lineView, lineParams)

        // 3. 添加起点和终点节点（后添加的节点会覆盖在管道上方）
        val startPoint = addClickPoint(pairId, true)
        addClickPoint(pairId, false)

        // 4. 更新管道位置和尺寸，使其连接两个节点 。等节点布局完成后再更新管道，避免 width == 0
        startPoint.view.post {
            updatePipeLine(pairId)
        }
    }

    private fun showGlobalSettingsDialog() {
        settingsDialog?.dismiss()

        // === 原：inflate(dialog_settings.xml) + findViewById 各控件 ===
        // === 现：统一弹框外框 + SettingsDialogContent（表单值存于 SettingsDialogState） ===
        val form = SettingsDialogState().apply {
            // 显示当前循环数值
            loopCount = if (maxLoopCount <= 0) "" else maxLoopCount.toString()
        }
        val host = ServiceComposeHost()
        var dialogRef: AlertDialog? = null
        // 重新读取脚本列表 → 触发 Compose 刷新
        val refresh: () -> Unit = {
            form.scripts = ClickerScriptStore.loadScripts(this)
        }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = host,
            widthRatio = 0.9f,
            onDismiss = { host.destroy() },
        ) {
            AppDialogPanel(
                title = "悬浮窗设置",
                actions = {
                    AppDialogActions(
                        confirmText = "确定",
                        onConfirm = {
                            // 确定：保存循环值后关闭
                            maxLoopCount = form.loopCount.toIntOrNull() ?: 0
                            if (maxLoopCount < 0) maxLoopCount = 0
                            Toast.makeText(
                                this@FloatingWindowService,
                                "循环次数已更新",
                                Toast.LENGTH_SHORT,
                            ).show()
                            dialogRef?.dismiss()
                        },
                        cancelText = "取消",
                        onCancel = { dialogRef?.dismiss() },
                    )
                },
            ) {
                SettingsDialogContent(
                    state = form,
                    onSaveCurrent = { saveCurrentScript(refresh) },
                    onLoadScript = { script -> handleLoadScript(script, dialogRef) },
                    onRenameScript = { script -> handleRenameScript(script, refresh) },
                    onDeleteScript = { script -> handleDeleteScript(script, refresh) },
                )
            }
        }
        settingsDialog = dialogRef
        refresh()
    }

    private fun createPointMarker(label: String, pairId: Int, isStart: Boolean): PointMarkerView {
        val sizePx = (PointMarkerView.SIZE_DP * resources.displayMetrics.density).toInt()
        return PointMarkerView(this).apply {
            style = when {
                pairId == -1 -> PointMarkerView.Style.CLICK
                isStart -> PointMarkerView.Style.START
                else -> PointMarkerView.Style.END
            }
            this.label = label
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }
    }

    private fun getPointWidth(point: ClickPoint): Int {
        return when {
            point.view.width > 0 -> point.view.width
            point.params?.width ?: 0 > 0 -> point.params!!.width
            point.view.layoutParams?.width ?: 0 > 0 -> point.view.layoutParams.width
            else -> (35 * resources.displayMetrics.density).toInt()
        }
    }

    private fun getPointHeight(point: ClickPoint): Int {
        return when {
            point.view.height > 0 -> point.view.height
            point.params?.height ?: 0 > 0 -> point.params!!.height
            point.view.layoutParams?.height ?: 0 > 0 -> point.view.layoutParams.height
            else -> (35 * resources.displayMetrics.density).toInt()
        }
    }

    private fun getPointCenterX(point: ClickPoint): Float {
        return point.x + getPointWidth(point) / 2f
    }

    private fun getPointCenterY(point: ClickPoint): Float {
        return point.y + getPointHeight(point) / 2f
    }

    private fun waitForClickPointWindowsReady(onReady: () -> Unit, retryCount: Int = 0) {
        val allReady = clickPoints.all { point ->
            point.view.width > 0 && point.view.height > 0 && point.view.isAttachedToWindow
        }
        if (allReady || retryCount >= 5) {
            onReady()
            return
        }
        runHandler.postDelayed({
            waitForClickPointWindowsReady(onReady, retryCount + 1)
        }, 80)
    }

    /**
     * 点位 / 通道的执行态强调：执行中的操作短暂变色，而不是消失。
     */
    private fun setHighlight(view: View?, on: Boolean) {
        when (view) {
            is PointMarkerView -> view.highlighted = on
            is PipeLineView -> view.highlighted = on
        }
    }

    private fun performPointClick(
        service: AutoClickerAccessibilityService,
        point: ClickPoint,
        x: Float,
        y: Float,
        duration: Long,
        onFinished: () -> Unit
    ) {
        val clickDuration = duration.coerceAtLeast(50L)

        // 点位保持可见，执行期间改为高亮强调；
        // 刷新不可触摸标记，避免首个坐标手势落到悬浮点自身
        setHighlight(point.view, true)
        point.params?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(point.view, params)
        }

        runHandler.postDelayed({
            if (!isRunning) {
                setHighlight(point.view, false)
                onFinished()
                return@postDelayed
            }

            // 手势结果异步回来，可能晚于下面的 postDelayed，所以只置标记、由下一轮开头中断
            service.performClick(x, y, clickDuration) { ok ->
                if (!ok) gestureRejected = true
            }

            runHandler.postDelayed({
                setHighlight(point.view, false)
                onFinished()
            }, clickDuration + 80)
        }, 80)
    }

    /**
     * 手势被系统取消 / 提交失败时中断整轮连点：
     * 继续跑只是对着点不动的界面空转，用户看到的却是「在运行却没反应」。
     */
    private fun breakOnRejectedGesture(): Boolean {
        if (!gestureRejected) return false
        gestureRejected = false
        stopRunning()
        Toast.makeText(
            this,
            "手势被系统取消，点击未生效。请检查目标界面的防误触设置或无障碍服务",
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    /** 双击手势：同一个 GestureDescription 里两条首尾相接的短按 stroke */
    private fun buildDoubleClickGesture(x: Float, y: Float): GestureDescription {
        fun tapPath() = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y)   // 极小位移，避免被识别成滑动
        }
        return GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(tapPath(), 0, DOUBLE_TAP_PRESS_MS)
            )
            .addStroke(
                GestureDescription.StrokeDescription(
                    tapPath(),
                    DOUBLE_TAP_PRESS_MS + DOUBLE_TAP_GAP_MS,
                    DOUBLE_TAP_PRESS_MS,
                )
            )
            .build()
    }

    private fun startRunning() {
        val service = AutoClickerAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (clickPoints.isEmpty()) {
            Toast.makeText(this, "请先添加点击点", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        gestureRejected = false
        syncLayoutMode()   // 运行态只剩停止按钮，行数变化后重新判断横排 / 竖排
        // 透明度沿用用户设置，不在这里强制半透明
        stopBtnWindowRect = Rect()
        // 运行中展开栏只保留「停止」按钮，其余按钮全部隐藏（由 ui.isRunning 派生，Compose 自动刷新）

        // 运行期间点击任何橙色圆形按钮（普通点或滑动点），连点不会停止，触摸完全穿透到背后应用
        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                // 如果触摸点落在任意一个点击点视图范围内，忽略（不停止）
                val isOnClickPoint = clickPoints.any { point ->
                    val view = point.view
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    val rect = android.graphics.Rect(
                        loc[0], loc[1],
                        loc[0] + view.width,
                        loc[1] + view.height
                    )
                    rect.contains(x, y)
                }
                if (!isOnClickPoint && !isTouchOnAllowedButton(x, y)) {
                    stopRunning()
                }
            }
            false
        }

        // 所有点击点保持正常显示（沿用用户设置的点位透明度）；
        // 手势穿透靠 FLAG_NOT_TOUCHABLE 实现，不再靠压暗视觉
        for (point in clickPoints) {
            point.view.alpha = pointAlpha
            point.params?.let { params ->
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(point.view, params)
            }
        }

        // 管道也必须穿透，否则滑动路径容易被悬浮管道窗口拦截
        for ((_, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            lineView.alpha = pointAlpha
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(lineView, params)
        }

        // 按编号排序执行
        val sortedPoints = clickPoints.sortedBy { it.id }
        var loopDone = 0   // 已经完成的轮数
        fun performRound(currentIndex: Int) {
            if (!isRunning) return
            if (breakOnRejectedGesture()) return
            // 无障碍服务可能在运行中途被关掉或被系统断开。握着旧实例继续
            // dispatchGesture 只会静默失败，表现为「在运行却毫无反应」，所以每轮重新取一次。
            val svc = AutoClickerAccessibilityService.instance
            if (svc == null) {
                stopRunning()
                Toast.makeText(this, "无障碍服务已断开，请到系统设置重新开启", Toast.LENGTH_LONG).show()
                return
            }
            if (currentIndex >= sortedPoints.size) {
                loopDone++
                if (maxLoopCount > 0 && loopDone >= maxLoopCount) {
                    stopRunning()
                    return
                }
                performRound(0) // 继续下一轮
                return
            }
            val point = sortedPoints[currentIndex]
            val centerX = getPointCenterX(point)
            val centerY = getPointCenterY(point)

            // 随机偏移（如果设置了随机距离）
            val randomX = if (point.randomDistance > 0) {
                (Math.random() * point.randomDistance * 2 - point.randomDistance).toFloat()
            } else 0f
            val randomY = if (point.randomDistance > 0) {
                (Math.random() * point.randomDistance * 2 - point.randomDistance).toFloat()
            } else 0f

            // 落在屏幕外的坐标会被系统直接判为无效手势，先在屏内夹一次
            val targetX = (centerX + randomX).coerceIn(1f, getScreenWidth() - 1f)
            val targetY = (centerY + randomY).coerceIn(1f, getScreenHeight() - 1f)

            // 根据类型执行点击（目前主要用点击）
            DebugLog.d("startRunning point.type", point.type)
            when (point.type) {
                "点击", "长按" -> {
                    var remaining = point.repeatCount
                    fun doAction() {
                        if (!isRunning || remaining <= 0) {
                            runHandler.postDelayed({
                                performRound(currentIndex + 1)
                            }, point.delay)
                            return
                        }
                        DebugLog.d("ClickDebug", "点击: x=$targetX, y=$targetY, duration=${point.touchDuration}")
                        performPointClick(svc, point, targetX, targetY, point.touchDuration) {
                            remaining--
                            runHandler.postDelayed({
                                doAction()
                            }, 50)
                        }
                    }
                    doAction()
                }
                "滑动起点" -> {
                    val endPoint = clickPoints.find { it.pairId == point.pairId && !it.isStart }
                    if (endPoint != null) {
                        val startView = point.view
                        val endView = endPoint.view
                        val startCx = getPointCenterX(point)
                        val startCy = getPointCenterY(point)
                        val endCx = getPointCenterX(endPoint)
                        val endCy = getPointCenterY(endPoint)

                        var remaining = point.repeatCount
                        fun doSwipe() {
                            if (!isRunning || remaining <= 0) {
                                runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                                return
                            }

                            // 起点、终点与管道保持可见，执行期间整体高亮强调
                            setHighlight(startView, true)
                            setHighlight(endView, true)
                            setHighlight(pipeMap[point.pairId]?.first, true)

                            runHandler.postDelayed({
                                // 调用优化后的 performSwipe
                                svc.performSwipe(startCx, startCy, endCx, endCy, point.touchDuration) { ok ->
                                    if (!ok) gestureRejected = true
                                }

                                // 手势执行后恢复显示状态
                                runHandler.postDelayed({
                                    setHighlight(startView, false)
                                    setHighlight(endView, false)
                                    setHighlight(pipeMap[point.pairId]?.first, false)
                                }, maxOf(point.touchDuration, 200))   // 等待手势完成

                                remaining--
                                runHandler.postDelayed({ doSwipe() }, point.touchDuration + 100)
                            }, 60)   // 等待隐藏生效
                        }
                        doSwipe()
                    } else {
                        runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                    }
                }
                "滑动终点" -> {
                    // 终点不执行，直接下一个
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                "双击" -> {
                    var remaining = point.repeatCount
                    fun doDouble() {
                        if (!isRunning || remaining <= 0) {
                            runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                            return
                        }
                        // 双击：两个快速点击，执行期间点位高亮
                        setHighlight(point.view, true)
                        point.params?.let { params ->
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            windowManager.updateViewLayout(point.view, params)
                        }
                        runHandler.postDelayed({
                            // 两次点击放进同一个 GestureDescription：
                            // 分两次 dispatch 会被系统当成两个独立手势，第二次常被丢弃，只剩单击效果
                            val gesture = buildDoubleClickGesture(targetX, targetY)
                            val submitted = svc.dispatchInjectedGesture(gesture) { ok ->
                                if (!ok) gestureRejected = true
                            }
                            if (!submitted) gestureRejected = true
                            remaining--
                            runHandler.postDelayed({
                                setHighlight(point.view, false)
                                doDouble()
                            }, point.touchDuration + 50)
                        }, 80)
                    }
                    doDouble()
                }
                "返回键" -> {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                "主页键" -> {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                "打开通知栏" -> {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                else -> {
                    runHandler.postDelayed({
                        performRound(currentIndex + 1)
                    }, point.delay)
                }
            }
        }
        // 首次启动悬浮窗后，点击点窗口需要完成 attach/layout 和触摸穿透刷新。
        // 等待窗口就绪后再开始，避免首次自动点击落到刚创建的悬浮点窗口上。
        waitForClickPointWindowsReady({
            runHandler.postDelayed({
                if (isRunning) {
                    performRound(0)
                }
            }, 250)
        })
    }

    private fun stopRunning() {
        isRunning = false
        syncLayoutMode()   // 恢复完整展开栏后重新判断横排 / 竖排
        runHandler.removeCallbacksAndMessages(null)

        // 恢复主悬浮窗
//        rootView.visibility = View.VISIBLE
//        windowManager.updateViewLayout(rootView, layoutParams)
        // 透明度保持用户设置，不在这里改回 1.0

        // 恢复其他按钮（原 setOtherButtonsEnabled(true)，现由 ui.isRunning=false 派生）
        // 启动图标恢复为“播放”同样由 ui.isRunning 派生自动完成

        // 移除空白区域触摸监听
        rootView.setOnTouchListener(null)

        // 恢复所有点击点视图
        for (point in clickPoints) {
            point.view.alpha = pointAlpha               // 恢复用户设置的点位透明度
            point.view.visibility = View.VISIBLE
            setHighlight(point.view, false)
            point.params?.let { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(point.view, params)
            }
        }

        // 恢复管道显示效果，但保持不可触摸，符合“仅作视觉提示”的需求
        for ((_, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            lineView.alpha = pointAlpha
            lineView.visibility = View.VISIBLE
            setHighlight(lineView, false)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(lineView, params)
        }
    }

    /**
     * 判断触摸点是否落在允许的按钮上（主按钮、停止按钮）。
     * 原实现用 findViewById + getLocationOnScreen；现按钮位置由 Compose 的
     * onGloballyPositioned 上报「窗口内坐标」，加上窗口偏移即屏幕绝对坐标。
     */
    private fun isTouchOnAllowedButton(rawX: Int, rawY: Int): Boolean {
        // 主按钮
        val main = Rect(mainBtnWindowRect).apply { offset(layoutParams.x, layoutParams.y) }
        if (main.contains(rawX, rawY)) return true
        // 停止按钮（运行中展开栏只剩它）
        val stop = Rect(stopBtnWindowRect).apply { offset(layoutParams.x, layoutParams.y) }
        return stop.contains(rawX, rawY)
    }

    /**
     * 显示给定点的设置对话框（全局悬浮）
     */
    private fun showPointSettingsDialog(point: ClickPoint) {
        pointSettingsDialog?.dismiss()

        // === 原：inflate(activity_point_settings.xml) + findViewById 各控件 + 互斥监听 ===
        // === 现：ComposeView + PointSettingsDialogContent（表单值存于 PointSettingsFormState） ===
        val form = PointSettingsFormState().apply {
            // 填充数据
            name = point.name
            // 滑动点只显示「滑动」；普通点按 point.type 回填，未知类型回落为「点击」
            type = when (point.type) {
                "双击", "返回键", "主页键", "打开通知栏", "滑动" -> point.type
                else -> "点击"
            }
            // 原实现未回填 clickPosition（该段代码被注释），此处保持 XML 默认选中「中间」
            clickPosition = "中间"
            delay = point.delay.toString()
            touchDuration = point.touchDuration.toString()
            repeatCount = point.repeatCount.toString()
            randomDistance = point.randomDistance.toString()
            isSwipePoint = point.pairId != -1
        }
        val host = ServiceComposeHost()
        val content = ComposeView(this)
        host.attach(content)
        content.setContent {
            AutoClickerTheme {
                AppDialogPanel(
                    title = "点击点设置",
                    actions = {
                        AppDialogActions(
                            confirmText = "保存",
                            onConfirm = { savePointSettings(point, form) },
                            cancelText = "取消",
                            onCancel = { pointSettingsDialog?.dismiss() },
                        )
                    },
                ) {
                    PointSettingsDialogContent(state = form)
                }
            }
        }
        // 弹框内部区域吞掉触摸，避免落到外层容器的「点空白关闭」监听上（等价原 dialogView.isClickable = true）
        content.setOnTouchListener { _, _ -> true }

        // 外层容器铺满屏幕，四周各留 6% 空隙；点击上下左右空白区域等同于取消
        val wrapper = FrameLayout(this).apply {
            val metrics = resources.displayMetrics
            val padH = (metrics.widthPixels * 0.06).toInt()
            val padV = (metrics.heightPixels * 0.06).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            setOnClickListener {
                pointSettingsDialog?.dismiss()
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        val builder = AlertDialog.Builder(this)
            .setView(wrapper)
            .setCancelable(true)

        pointSettingsDialog = builder.create()

        // 把宿主 Owner 也挂到对话框窗口的 DecorView 上（必须在 show() 之前，原因见 ServiceComposeHost.attachWindowRoot）
        pointSettingsDialog?.window?.decorView?.let { host.attachWindowRoot(it) }

        pointSettingsDialog?.apply {
            window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            window?.setGravity(Gravity.CENTER)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)   // 点击外部空白关闭
            setOnDismissListener { host.destroy() }
            show()
            // 与设置/快捷弹框一致：清掉系统给 TYPE_APPLICATION_OVERLAY 弹框附加的 FLAG_ALT_FOCUSABLE_IM，
            // 否则本弹框内的输入框（延迟/时长/重复次数/随机距离）点了不弹软键盘（原因见 QuickClickService.showOverlayDialog）
            window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            // show 之后设置全屏窗口，外层 wrapper 的 padding 区域才能完整接收上下左右空白点击
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 去掉平台面板底（9-patch 描边），只保留自绘面板
            stripPlatformPanel()
        }
    }

    /** 点击点设置保存（原 btnSave.setOnClickListener 内的逻辑，逐条保留） */
    private fun savePointSettings(point: ClickPoint, form: PointSettingsFormState) {
        point.name = form.name.ifBlank { "点 ${point.id}" }
        // 输入框可以留空或填出离谱值：0 延迟会让循环变成忙等，超大时长会整轮卡在同一个手势上
        point.delay = ClickerPointData.clampDelay(form.delay.toLongOrNull() ?: 0)
        point.touchDuration =
            ClickerPointData.clampTouchDuration(form.touchDuration.toLongOrNull() ?: 300)
        point.repeatCount = ClickerPointData.clampRepeatCount(form.repeatCount.toIntOrNull() ?: 1)
        point.randomDistance =
            ClickerPointData.clampRandomDistance(form.randomDistance.toIntOrNull() ?: 0)
        if (point.pairId == -1) {
            point.type = form.type   // 普通点的类型由单选决定
        }
        // 滑动点的类型保持不变
        point.clickPosition = if (form.clickPosition == "随机") "随机" else "中间"

        if (point.pairId != -1) {
            clickPoints.filter { it.pairId == point.pairId }.forEach { p ->
                p.name = point.name
                p.delay = point.delay
                p.touchDuration = point.touchDuration
                p.repeatCount = point.repeatCount
                p.randomDistance = point.randomDistance
            }
        }
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        pointSettingsDialog?.dismiss()
    }

    // ==================== 脚本管理（保存 / 加载 / 改名 / 删除） ====================

    /** 「保存当前点位」：把内存中的点位存成命名脚本（同名则先确认覆盖） */
    private fun saveCurrentScript(onChanged: () -> Unit) {
        if (clickPoints.isEmpty()) {
            Toast.makeText(this, "当前没有点位，请先添加", Toast.LENGTH_SHORT).show()
            return
        }
        // 从脚本加载来的点位，预填该脚本名（可改可直接保存）
        showInputDialog("保存脚本", currentScriptName ?: "", "输入脚本名称", ScriptNaming.MAX_NAME_LENGTH) { scriptName ->
            if (ClickerScriptStore.loadScripts(this).any { it.name == scriptName }) {
                showConfirmDialog(
                    title = "覆盖脚本",
                    message = "脚本「$scriptName」已存在，是否覆盖？",
                    confirmText = "覆盖",
                ) {
                    ClickerScriptStore.saveScript(this, scriptName, collectPoints())
                    currentScriptName = scriptName
                    onChanged()
                    Toast.makeText(this, "已覆盖脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                }
            } else {
                ClickerScriptStore.saveScript(this, scriptName, collectPoints())
                currentScriptName = scriptName
                onChanged()
                Toast.makeText(this, "已保存脚本「$scriptName」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 点击脚本名称：加载/切换；悬浮框上已有点位时先确认覆盖 */
    private fun handleLoadScript(script: ClickerScript, dialog: AlertDialog?) {
        when {
            script.points.isEmpty() ->
                Toast.makeText(this, "脚本「${script.name}」没有点位", Toast.LENGTH_SHORT).show()
            // 悬浮框上已有点位：先确认覆盖，避免未保存的节点被直接替换掉
            clickPoints.isNotEmpty() -> showConfirmDialog(
                title = "加载脚本",
                message = "当前点位未保存，加载脚本「${script.name}」将覆盖它们，是否覆盖？",
                confirmText = "覆盖",
            ) {
                loadScript(script, dialog)
            }
            else -> loadScript(script, dialog)
        }
    }

    /** 「改名」：重命名脚本（新名已存在则提示） */
    private fun handleRenameScript(script: ClickerScript, onChanged: () -> Unit) {
        showInputDialog("重命名脚本", script.name, "输入新名称", ScriptNaming.MAX_NAME_LENGTH) { newName ->
            if (ClickerScriptStore.renameScript(this, script.name, newName)) {
                if (currentScriptName == script.name) currentScriptName = newName
                onChanged()
                Toast.makeText(this, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "脚本名「$newName」已存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 「删除」：确认后删除脚本 */
    private fun handleDeleteScript(script: ClickerScript, onChanged: () -> Unit) {
        showConfirmDialog(
            title = "删除脚本",
            message = "确定删除脚本「${script.name}」？",
            confirmText = "删除",
            danger = true,
        ) {
            ClickerScriptStore.deleteScript(this, script.name)
            if (currentScriptName == script.name) currentScriptName = null
            onChanged()
            Toast.makeText(this, "已删除脚本「${script.name}」", Toast.LENGTH_SHORT).show()
        }
    }

    /** 载入脚本：替换悬浮框上的点位，并关闭设置弹框 */
    private fun loadScript(script: ClickerScript, dialog: AlertDialog?) {
        stopRunning()
        removeAllPoints()
        restorePoints(script.points)
        currentScriptName = script.name
        dialog?.dismiss()
        Toast.makeText(this, "已加载脚本「${script.name}」", Toast.LENGTH_SHORT).show()
    }

    /** 当前点位快照（按 id 升序，滑动对中起点在前，与旧文件顺序一致） */
    private fun collectPoints(): List<ClickerPointData> =
        clickPoints.sortedWith(
            compareBy<ClickPoint> { it.id }
                .thenBy { if (it.isStart) 0 else 1 }  // 滑动对中起点优先
        ).map { p ->
            ClickerPointData(
                id = p.id,
                x = p.x,
                y = p.y,
                type = p.type,
                name = p.name,
                delay = p.delay,
                touchDuration = p.touchDuration,
                repeatCount = p.repeatCount,
                randomDistance = p.randomDistance,
                clickPosition = p.clickPosition,
                pairId = p.pairId,
                isStart = p.isStart,
                endX = p.endX,
                endY = p.endY,
            )
        }

    /** 移除所有点位与管道视图 */
    private fun removeAllPoints() {
        for (p in clickPoints) {
            releasePoint(p)
        }
        clickPoints.clear()
        for ((_, pipePair) in pipeMap) {
            removeWindowQuietly(pipePair.first)
        }
        pipeMap.clear()
    }

    /** 按保存顺序重建点位（还原流程与旧「打开点击文件」保持一致） */
    private fun restorePoints(points: List<ClickerPointData>) {
        // 滑动对按「起点 + 紧随其后的终点」成对还原，pairId 由当前点位推导，不复用存档里的编号
        var pendingPairId = -1
        for (data in points) {
            if (data.pairId != -1 && (data.isStart || pendingPairId != -1)) {
                val pairId = if (data.isStart) nextPairId().also { pendingPairId = it } else pendingPairId
                if (data.isStart) {
                    // 先创建管道并加入窗口（位于节点下层）
                    val lineView = PipeLineView(this)
                    val lineParams = createPipeLineLayoutParams()
                    pipeMap[pairId] = Pair(lineView, lineParams)
                    lineView.alpha = pointAlpha
                    lineView.markerRadius = PointMarkerView.RADIUS_DP * resources.displayMetrics.density
                    windowManager.addView(lineView, lineParams)
                }
                val point = addClickPoint(pairId, data.isStart)
                applyLoadedPoint(point, data)
                movePoint(point, data.x, data.y, !data.isStart)
                if (!data.isStart) pendingPairId = -1
            } else {
                // 普通点击点（缺起点的孤立终点也按普通点还原，避免空指针）
                pendingPairId = -1
                val point = addClickPoint(-1, true)
                applyLoadedPoint(point, data)
                movePoint(point, data.x, data.y, false)
            }
        }
    }

    /** 把脚本里的点位参数回填到新建的 ClickPoint */
    private fun applyLoadedPoint(point: ClickPoint, data: ClickerPointData) {
        point.x = data.x
        point.y = data.y
        point.type = data.type
        point.name = data.name
        point.isStart = data.isStart
        point.delay = data.delay
        point.touchDuration = data.touchDuration
        point.repeatCount = data.repeatCount
        point.randomDistance = data.randomDistance
        point.clickPosition = data.clickPosition
        point.endX = data.endX
        point.endY = data.endY
    }

    /**
     * 将指定编号的点击节点移动到新的屏幕坐标（中心点）
     * @param point 节点
     * @param centerX 目标中心 X 坐标（像素）
     * @param centerY 目标中心 Y 坐标（像素）
     */
    fun movePoint(point: ClickPoint, centerX: Float, centerY: Float,isupdatePipeLine: Boolean) {
        val params = point.params ?: return
        val left = centerX - point.view.width / 2f
        val top = centerY - point.view.height / 2f
        params.x = left.toInt()
        params.y = top.toInt()
        windowManager.updateViewLayout(point.view, params)
        point.x = left
        point.y = top
        if (point.pairId != -1 && isupdatePipeLine)
            updatePipeLine(point.pairId)
    }

    // ==================== 透明度 / 通用弹框 ====================

    /**
     * 透明度弹框（与代点器同一页面，但透明度各存各的）：
     * 两个滑块分别控制「悬浮窗」与「点击 / 滑动点位」的不透明度，拖动即实时生效；
     * 拖动期间弹框整体隐藏、底部显示 20% 进行条，方便观察真实效果；
     * 「确定」写回本功能的 SharedPreferences，「取消」/返回键恢复打开前的值。
     */
    private fun showOpacityDialog() {
        try {
            val previousAlpha = rootView.alpha
            val previousPointAlpha = pointAlpha
            val state = AppOpacityDialogState().apply {
                alpha = previousAlpha
                pointAlpha = previousPointAlpha
            }
            var confirmed = false
            val host = ServiceComposeHost()
            val preview = opacityPreview ?: OpacityDragPreview(this).also { opacityPreview = it }
            var dialogRef: AlertDialog? = null
            dialogRef = ServiceDialogs.show(
                service = this,
                host = host,
                widthRatio = 0.85f,
                onDismiss = {
                    preview.hide()
                    host.destroy()
                    if (!confirmed) {
                        rootView.alpha = previousAlpha
                        applyPointAlpha(previousPointAlpha)
                    }
                },
            ) {
                AppDialogPanel(
                    title = "透明度",
                    actions = {
                        AppDialogActions(
                            confirmText = "确定",
                            onConfirm = {
                                confirmed = true
                                ClickerScriptStore.setWindowAlpha(this@FloatingWindowService, state.alpha)
                                ClickerScriptStore.setPointAlpha(this@FloatingWindowService, state.pointAlpha)
                                dialogRef?.dismiss()
                            },
                            cancelText = "取消",
                            onCancel = { dialogRef?.dismiss() },
                        )
                    },
                ) {
                    AppOpacityDialogContent(
                        state = state,
                        minAlpha = ClickerScriptStore.MIN_WINDOW_ALPHA,
                        onAlphaChange = {
                            rootView.alpha = it
                            preview.update(it)
                        },
                        onPointAlphaChange = {
                            applyPointAlpha(it)
                            preview.update(it)
                        },
                        onDraggingChange = { dragging, value, anchor ->
                            if (dragging && anchor != null) {
                                preview.show(
                                    dialogRef,
                                    value,
                                    anchor,
                                    ClickerScriptStore.MIN_WINDOW_ALPHA,
                                )
                            } else {
                                preview.hide()
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FloatingWindow", "打开透明度弹框失败", e)
        }
    }

    /** 应用点位透明度；运行期点位被临时压到 ≤0.3，停不下来时等 stopRunning 恢复 */
    private fun applyPointAlpha(alpha: Float) {
        pointAlpha = alpha
        if (isRunning) return
        for (point in clickPoints) {
            point.view.alpha = alpha
        }
        for ((_, pipePair) in pipeMap) {
            pipePair.first.alpha = alpha
        }
    }

    /** 单行输入弹框（保存脚本 / 重命名脚本共用） */
    private fun showInputDialog(
        title: String,
        initial: String,
        hint: String,
        maxLen: Int,
        onConfirm: (String) -> Unit,
    ) {
        val state = ClickerInputDialogState().apply { value = initial }
        val host = ServiceComposeHost()
        var dialogRef: AlertDialog? = null
        dialogRef = ServiceDialogs.show(
            service = this,
            host = host,
            widthRatio = 0.85f,
            onDismiss = { host.destroy() },
        ) {
            AppDialogPanel(
                title = title,
                actions = {
                    AppDialogActions(
                        confirmText = "确定",
                        onConfirm = {
                            val value = state.value.trim()
                            if (value.isEmpty()) {
                                Toast.makeText(
                                    this@FloatingWindowService,
                                    "名称不能为空",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                onConfirm(value)
                                dialogRef?.dismiss()
                            }
                        },
                        cancelText = "取消",
                        onCancel = { dialogRef?.dismiss() },
                    )
                },
            ) {
                AppFieldLabel(text = "名称")
                AppTextField(
                    value = state.value,
                    onValueChange = { state.value = it },
                    modifier = Modifier.padding(top = Dimens.fieldGap),
                    hint = hint,
                    maxLength = maxLen,
                )
            }
        }
    }

    /** 统一确认弹框：标题 + 说明 + 底部按钮（取消 / 主操作） */
    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        danger: Boolean = false,
        onConfirm: () -> Unit = {},
    ) {
        val host = ServiceComposeHost()
        var dialogRef: AlertDialog? = null
        dialogRef = ServiceDialogs.show(
            service = this,
            host = host,
            widthRatio = 0.85f,
            onDismiss = { host.destroy() },
        ) {
            AppDialogPanel(
                title = title,
                actions = {
                    AppDialogActions(
                        confirmText = confirmText,
                        onConfirm = {
                            onConfirm()
                            dialogRef?.dismiss()
                        },
                        cancelText = "取消",
                        onCancel = { dialogRef?.dismiss() },
                        confirmVariant = if (danger) AppButtonVariant.Danger else AppButtonVariant.Filled,
                    )
                },
            ) {
                AppConfirmMessage(text = message)
            }
        }
    }
}


// 点击点数据类
data class ClickPoint(
    val id: Int,  // 悬浮窗内唯一编号，运行顺序与保存顺序都按它排
    var x: Float,
    var y: Float,
    var view: View,
    var name: String = "点 $id", // 点击按钮名称
    var type: String = "点击",  // 点击按钮类型：默认点击
    var clickPosition: String = "中间",
    var delay: Long = 50, // 延迟
    var touchDuration: Long = 150,//触摸时长
    var repeatCount: Int = 1, //循环次数
    var randomDistance: Int = 5,//随机距离
    var endX: Float = x,
    var endY: Float = y,
    var params: WindowManager.LayoutParams? = null,
    // 新字段：滑动对ID和角色
    var pairId: Int = -1,        // -1 表示不属于滑动对
    var isStart: Boolean = false, // 滑动对中的起点 true，终点 false
    /** 拖动 / 长按计时用的 Handler，删除点位时必须取消，否则已删点位仍会弹出设置框 */
    var dragHandler: Handler? = null
)
