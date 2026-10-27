package com.hpu.autoclicker.feature.quickclick

import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppConfirmMessage
import com.hpu.autoclicker.core.components.AppDialogActions
import com.hpu.autoclicker.core.components.AppDialogPanel
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppOpacityDialogContent
import com.hpu.autoclicker.core.components.AppOpacityDialogState
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.ui.FloatingWindowBounds
import com.hpu.autoclicker.core.ui.OpacityDragPreview
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.ui.ServiceDialogs
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import com.hpu.autoclicker.core.util.DebugLog
import com.hpu.autoclicker.core.util.ScriptNaming
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 悬浮快捷点击服务（新功能）
 *
 * 链路：用户手动点悬浮子按钮 → 识别（文本 / 图像 / 坐标）拿到坐标
 *      → 随机偏移 → 按动作类型（点击 / 双击 / 长按 / 滑动）dispatchGesture 注入
 *
 * 三种状态：
 * - 折叠态：屏幕上只有一个总按钮，零编辑控件；单击 → 展开
 * - 展开态（运行）：总按钮 + 已配置子按钮纵向排列；再单击总按钮 → 折叠
 *   子按钮单击执行配置动作；长按 / 滑动不算点击，会回放给下层应用
 *   （按钮遮挡的区域照样能长按、滚动），总按钮不参与透传。
 * - 编辑态：双击总按钮进入；展开区显示 [+][脚本管理][透明度][关闭]；
 *   “+”弹框新建按钮，单击子按钮打开修改/删除弹框，长按拖动调整顺序；
 *   “关闭”关闭悬浮窗（有未保存按钮时先提示保存）
 *
 * 手势状态机：总按钮统一在 OnTouchListener 内处理单击/拖动/双击，
 * 禁止混用三套事件；位移 > touchSlop 判定为拖动，拖动过程中绝不触发识别/点击；
 * 松手边缘吸附（左/右），坐标始终用屏幕绝对坐标。
 *
 * 【UI 层迁移说明】原 `quick_floating_root.xml` 已改为 Compose：
 * - 悬浮窗根视图由 LinearLayout 换成 [ComposeView]，内容为 [QuickFloatingContent]；
 * - 展开/折叠、编辑态、子按钮列表不再靠 addView/removeView 手动刷新，
 *   而由 [QuickFloatingUiState] 驱动 Compose 重组（业务逻辑不变）；
 * - 触摸状态机仍直接消费 MotionEvent 原始坐标（rawX/rawY），
 *   因此拖动跟手、边缘吸附、长按排序的行为与原来完全一致。
 */
class QuickClickService : Service() {

    companion object {
        const val ACTION_STOP = "com.hpu.autoclicker.STOP_QUICK_CLICK"

        /** 截屏前等待悬浮窗/弹框真正从画面消失的时间 */
        private const val HIDE_BEFORE_SCREENSHOT_MS = 150L

        /**
         * 截图请求兜底超时。takeScreenshot 在部分机型上会既不回调也不报错
         * （无障碍刚断开 / 系统截屏服务被限流），没有兜底则 capturePending 永久为真，
         * 之后所有「屏幕截图」入口都点不动，悬浮窗也保持隐藏状态回不来。
         */
        private const val CAPTURE_WATCHDOG_MS = 8000L

        /** 点击 / 双击的手势按压时长：固定 50ms，不用 0（0 容易被系统判为无效手势） */
        private const val ACTION_TAP_DURATION_MS = 50L

        /** 透传注入前等一帧多，让 NOT_TOUCHABLE 生效，否则注入手势会落回本悬浮窗 */
        private const val PASS_THROUGH_SWITCH_DELAY_MS = 24L

        /** 透传长按的注入时长：需长于系统长按阈值，下层应用才会识别成长按 */
        private const val PASS_THROUGH_LONG_PRESS_MS = 800L

        /** 透传滑动的注入时长上限：再长的真实滑动也按该上限回放 */
        private const val PASS_THROUGH_MAX_SWIPE_MS = 5000L
    }

    private lateinit var windowManager: WindowManager

    /** 悬浮窗根视图：由 XML 的 LinearLayout 换成 ComposeView */
    private lateinit var root: ComposeView
    private lateinit var lp: WindowManager.LayoutParams

    /** ComposeView 在 Service 中所需的生命周期宿主（缺失会抛 ViewTreeLifecycleOwner not found） */
    private val composeHost = ServiceComposeHost()

    /** 拖动透明度滑块时的 20% 预览条（弹框隐藏，只留进行条） */
    private var opacityPreview: OpacityDragPreview? = null

    /** 悬浮窗 UI 状态（展开/编辑态、子按钮列表、按下/拖动反馈），替代原来的 View 手工刷新 */
    private val ui = QuickFloatingUiState()

    /** 子按钮配置列表（原内存态 configs，现由 Compose 状态持有，增删改自动刷新 UI） */
    private val configs: MutableList<QuickClickConfig> get() = ui.configs

    private var rootAdded = false

    // ===== 手势状态机变量（总按钮） =====
    private var touchSlop = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var initWindowX = 0
    private var initWindowY = 0
    private var isDragging = false
    private var lastMainTapAt = 0L   // 双击判定：两次单击间隔 < 300ms 进入编辑态

    // ===== 子按钮拖动排序变量（编辑态） =====
    private val dragHandler = Handler(Looper.getMainLooper())
    private var dragStartRunnable: Runnable? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragMoved = false        // 长按前超 touchSlop 取消长按计时
    private var dragActive = false       // 长按触发，进入拖动排序
    private var parentTopOnScreen = 0    // 竖排展开区顶部屏幕绝对坐标
    private var parentLeftOnScreen = 0   // 横排展开区左侧屏幕绝对坐标
    private var dragIndex = -1           // 正在拖动的子按钮在 configs 中的索引
    private var expandedTopInRoot = 0f   // 展开区在根视图内的 Y 偏移（px），用于换算屏幕坐标
    private var expandedLeftInRoot = 0f  // 展开区在根视图内的 X 偏移（px），横排排序用

    // ===== 运行态子按钮手势透传（长按 / 滑动交给下层应用） =====
    /** 透传注入进行中：窗口已置为不可触摸，吞掉期间落回本窗口的触摸 */
    private var passThroughInjecting = false
    /** 本次运行态手势按下的子按钮索引（-1 表示没有进行中的手势） */
    private var passThroughIndex = -1
    private var passThroughDownX = 0f
    private var passThroughDownY = 0f
    private var passThroughSwipe = false        // 位移已超 touchSlop → 按滑动透传
    private var passThroughInjected = false     // 本次手势已转成透传（长按先触发）
    /** 透传滑动轨迹：xy 成对存放 + 对应事件时间（用于回放同一条滑动） */
    private val passThroughPoints = ArrayList<Float>()
    private val passThroughTimes = ArrayList<Long>()
    private var passThroughTimer: Runnable? = null
    /** 每次透传注入的代号：丢弃超时/迟到的回调，避免旧回调恢复新一次注入的窗口状态 */
    private var passThroughGeneration = 0
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 当前竖排吸附边缘：true = 左侧，false = 右侧。横竖屏切换后按它重新贴边 */
    private var dockedLeft = true

    /** 当前横排吸附边缘：true = 顶部，false = 底部 */
    private var dockedTop = true

    /** 当前吸附轴：true = 贴上 / 下边（横排或横屏），false = 贴左 / 右边 */
    private var dockTopBottom = false

    /** 拖动期间固定的安全区，避免每帧用旧坐标重算原点偏移 */
    private val dragBounds = Rect()

    /** 上次已知的屏幕尺寸，用来区分「真的横竖屏切换」与深色模式 / 字体变化 */
    private var lastScreenW = 0
    private var lastScreenH = 0

    // 当前按钮列表来自哪个脚本（从脚本管理加载后记录，保存时预填名称）
    private var currentScriptName: String? = null
    /** 最近一次保存/加载时的按钮快照，用于判断关闭前是否有未保存的改动 */
    private var savedSnapshot: List<QuickClickConfig>? = null
    /** 每个子按钮槽位上次执行时间，key 为列表下标（下标数量受脚本按钮上限约束） */
    private val lastClickAt = mutableMapOf<Int, Long>()

    /** 图像识别执行用的主线程 Handler（截图调度、重试、结果回主线程） */
    private val matchHandler = Handler(Looper.getMainLooper())
    /** 模板匹配工作线程：全屏 NCC 计算不能占用主线程，否则会卡 UI / ANR */
    private val matchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** 同一时刻只允许一条「截图 → 匹配」链路，避免并发截图互相干扰 */
    private var matchInProgress = false
    /** 服务已销毁标记：异步匹配回调回来时不再执行点击 */
    private var destroyed = false
    /** 当前正在显示的模板截图框选浮层，确定/返回键取消后清空 */
    private var captureOverlay: RegionCaptureOverlay? = null
    /** 请求截图后、浮层出现前的等待期（防止连点重复截屏） */
    private var capturePending = false
    /** 截图请求的兜底超时任务；请求正常回落后取消 */
    private var captureWatchdog: Runnable? = null
    /** 当前正在显示的坐标拾取浮层 */
    private var pickOverlay: CoordinatePickOverlay? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.syncFrom(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // 启动为空白编辑态：不自动恢复上次的按钮列表，已保存脚本需在「设置」中手动加载
        configs.clear()

        // === 原：LayoutInflater.inflate(R.layout.quick_floating_root) ===
        // === 现：ComposeView + Compose 内容 ===
        root = ComposeView(this)
        composeHost.attach(root)   // 注入生命周期宿主，否则 setContent 会抛异常
        root.alpha = QuickClickStore.getWindowAlpha(this)   // 恢复上次保存的不透明度
        root.setContent {
            AutoClickerTheme {
                QuickFloatingContent(
                    state = ui,
                    onMainTouch = { event -> onMainTouch(event) },
                    onAdd = { showConfigDialog() },
                    onOpenSettings = { showScriptManagerDialog() },
                    onOpenOpacity = { showOpacityDialog() },
                    onCloseWindow = { requestCloseWindow() },
                    onChildRunTouch = { index, event -> onChildRunTouch(index, event) },
                    onChildTouch = { index, event -> onChildTouch(index, event) },
                    onExpandedPositioned = { left, top ->
                        expandedLeftInRoot = left
                        expandedTopInRoot = top
                    },
                )
            }
        }

        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE：不抢目标应用输入焦点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        // 折叠态高度固定为「8dp 上间距 + 40dp 主按钮 + 8dp 下间距」= 56dp，
        // Compose 内容在 addView 之前无法测量，故用该固定值估算初始居中位置
        val visible = FloatingWindowBounds.displaySize(windowManager)
        lp.gravity = Gravity.START or Gravity.TOP   // x/y 即屏幕绝对坐标
        lp.x = 0
        lp.y = (visible.y - dp(56)) / 2

        lastScreenW = getScreenWidth()
        lastScreenH = getScreenHeight()
        syncLayoutMode()

        try {
            windowManager.addView(root, lp)
            rootAdded = true
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "addView 失败", e)
        }

        // 内容尺寸变化（展开 / 折叠 / 进出编辑态 / 增删按钮 / 横竖屏重排）后重新钳制窗口
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (rootAdded) clampWindow()
            }
        }

        startForegroundIfNeeded()

        // 启动只显示总按钮的折叠态，由用户单击总按钮展开/折叠；
        // 尚无子按钮时单击会直接进入编辑态，方便先添加按钮或加载脚本
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 横竖屏切换 / 分屏尺寸变化时系统不会重建 Service，窗口坐标仍是旧方向的，
     * 必须按新尺寸重排并钳制，否则悬浮窗会留在屏幕外甚至完全不可见。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!rootAdded) return
        val w = getScreenWidth()
        val h = getScreenHeight()
        if (w == lastScreenW && h == lastScreenH) return   // 深色模式 / 字体大小等，窗口几何未变
        lastScreenW = w
        lastScreenH = h
        clampWindow()
        // 旋转动画期间视图尺寸/位置可能还在过渡，延迟再校一次
        root.postDelayed({ if (rootAdded) clampWindow() }, 350)
        warnIfExpandedTooLarge()
    }

    override fun onDestroy() {
        destroyed = true
        // 通知 Activity 服务已停止
        sendBroadcast(Intent("com.hpu.autoclicker.SERVICE_STOPPED").setPackage(packageName))
        // overlay 弹框窗口不随服务销毁自动移除，先统一收掉，避免留在桌面上点不掉
        ServiceDialogs.dismissAll(this)
        dragHandler.removeCallbacksAndMessages(null)
        matchHandler.removeCallbacksAndMessages(null)
        matchExecutor.shutdownNow()
        captureOverlay?.dismiss()
        captureOverlay = null
        opacityPreview?.hide()
        opacityPreview = null
        capturePending = false
        pickOverlay?.dismiss()
        pickOverlay = null
        // 相册中转 Activity 若仍在前台，丢弃回调，避免回到已销毁的服务
        ImagePickActivity.pendingResult = null
        if (rootAdded) {
            try {
                windowManager.removeView(root)
            } catch (e: Exception) {
                android.util.Log.e("QuickClick", "removeView 失败", e)
            }
            rootAdded = false
        }
        composeHost.destroy()   // 释放 ComposeView 的生命周期宿主
        super.onDestroy()
    }

    // ==================== 总按钮（样式与旧功能一致） ====================

    /**
     * 总按钮手势状态机：单击展开/折叠、双击进编辑态、位移超阈值判定为拖动。
     * 不使用 onClick/LongClick，避免事件互相吞。
     * 由 Compose 的 pointerInteropFilter 回调进来，事件坐标同为屏幕绝对坐标。
     */
    private fun onMainTouch(event: MotionEvent): Boolean {
        if (passThroughInjecting) return true   // 透传注入期间不响应（避免回放手势点中主按钮）
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX           // 屏幕绝对坐标，防多窗口偏移
                downRawY = event.rawY
                initWindowX = lp.x
                initWindowY = lp.y
                dragBounds.set(FloatingWindowBounds.safeBounds(windowManager, root, lp, edgeMargin()))
                isDragging = false
                ui.mainPressed = true           // 按下反馈（等价原 bg_floating_button 的 pressed 态）
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                    isDragging = true           // 超过 touchSlop 立即进入拖动态
                    ui.mainPressed = false      // 进入拖动即取消按下反馈
                }
                if (isDragging) {
                    lp.x = FloatingWindowBounds.clampInside(
                        initWindowX + dx.toInt(), root.width, dragBounds.left, dragBounds.right
                    )
                    lp.y = FloatingWindowBounds.clampInside(
                        initWindowY + dy.toInt(), root.height, dragBounds.top, dragBounds.bottom
                    )
                    try {
                        windowManager.updateViewLayout(root, lp)  // 跟手移动，不重建 View
                    } catch (e: Exception) {
                        android.util.Log.e("QuickClick", "updateViewLayout 失败", e)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.mainPressed = false
                if (isDragging) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        snapToEdge()            // 拖动结束：吸附最近左/右边缘，y 保持
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handleMainTap()             // 未拖动且非取消 → 单击/双击判定
                }
                isDragging = false
                true
            }
            else -> false
        }
    }

    /** 单击/双击判定：双击（<300ms）进编辑态；单击按状态展开/折叠/退出编辑 */
    private fun handleMainTap() {
        val now = System.currentTimeMillis()
        if (now - lastMainTapAt < ViewConfiguration.getDoubleTapTimeout()) {
            lastMainTapAt = 0
            enterEditMode()                         // 双击 → 编辑态
        } else {
            lastMainTapAt = now
            when {
                ui.isEditing -> exitEditMode()      // 编辑态单击总按钮 → 退出编辑（保持展开）
                ui.isExpanded -> collapse()         // 展开态单击 → 折叠
                else -> expand()                    // 折叠态单击 → 展开
            }
        }
    }

    /**
     * 松手吸附：长边与吸附边缘平行，另一轴保留松手时的位置。
     * 横排 / 横屏贴上 / 下边（水平位置不强制），竖排贴左 / 右边。
     */
    private fun snapToEdge() {
        val bounds = FloatingWindowBounds.safeBounds(windowManager, root, lp, edgeMargin())
        val viewW = root.width.coerceAtLeast(root.measuredWidth)
        val viewH = root.height.coerceAtLeast(root.measuredHeight)
        if (docksTopBottom()) {
            dockedTop = lp.y + viewH / 2 < (bounds.top + bounds.bottom) / 2
            lp.y = if (dockedTop) bounds.top else (bounds.bottom - viewH).coerceAtLeast(bounds.top)
            lp.x = FloatingWindowBounds.clampInside(lp.x, viewW, bounds.left, bounds.right)
        } else {
            dockedLeft = lp.x + viewW / 2 < (bounds.left + bounds.right) / 2
            lp.x = if (dockedLeft) bounds.left else (bounds.right - viewW).coerceAtLeast(bounds.left)
            lp.y = FloatingWindowBounds.clampInside(lp.y, viewH, bounds.top, bounds.bottom)
        }
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "snapToEdge 失败", e)
        }
    }

    // ==================== 展开 / 折叠 / 编辑态 ====================
    // 说明：原来这三态靠 expandedControls 的 addView/removeView + visibility 手工刷新，
    // 现在只改 ui 状态，由 Compose 自动重组（对外行为一致）。

    private fun expand() {
        // 无障碍服务未开启/被系统杀掉：总按钮变灰并提示
        val available = AutoClickerAccessibilityService.instance != null
        setMainAvailable(available)
        if (!available) {
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
        }
        // 尚无子按钮时展开无可展示内容，直接进入编辑态让用户先添加按钮
        if (configs.isEmpty()) {
            enterEditMode()
            return
        }
        ui.isExpanded = true
        clampWindow()
        warnIfExpandedTooLarge()
    }

    private fun collapse() {
        ui.isExpanded = false
        ui.isEditing = false   // 折叠即退出编辑态，编辑控件不常驻
        clampWindow()
    }

    private fun enterEditMode() {
        ui.isExpanded = true
        ui.isEditing = true
        lastMainTapAt = 0L   // 重置双击计时，避免刚进编辑态的下一次点击被误判为双击
        setMainAvailable(true)
        clampWindow()
        warnIfExpandedTooLarge()
    }

    /**
     * 退出编辑态：重置双击计时避免下一次点击被误判为双击；
     * 无子按钮时回到折叠态（只剩总按钮），保证编辑态始终可退出。
     */
    private fun exitEditMode() {
        ui.isEditing = false
        lastMainTapAt = 0L
        if (configs.isEmpty()) {
            collapse()
        } else {
            clampWindow()
        }
    }

    // ==================== 关闭悬浮窗 / 透明度 ====================

    /** 与最近一次保存/加载的快照对比，判断是否有未保存的按钮改动 */
    private fun hasUnsavedButtons(): Boolean =
        configs.isNotEmpty() && configs.toList() != savedSnapshot

    /** “关闭”（X）：无未保存按钮直接关闭；否则先询问是否保存 */
    private fun requestCloseWindow() {
        if (!hasUnsavedButtons()) {
            stopSelf()
            return
        }
        val host = ServiceComposeHost()
        var dialogRef: AlertDialog? = null
        dialogRef = ServiceDialogs.show(
            service = this,
            host = host,
            widthRatio = 0.85f,
            onDismiss = { host.destroy() },
        ) {
            AppDialogPanel(
                title = "关闭悬浮窗",
                actions = {
                    AppButton(
                        text = "不保存关闭",
                        onClick = {
                            dialogRef?.dismiss()
                            stopSelf()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = AppButtonVariant.Outlined,
                    )
                    Spacer(Modifier.height(Dimens.space8))
                    AppDialogActions(
                        confirmText = "保存并关闭",
                        onConfirm = {
                            dialogRef?.dismiss()
                            saveAndClose()
                        },
                        cancelText = "取消",
                        onCancel = { dialogRef?.dismiss() },
                    )
                },
            ) {
                AppConfirmMessage(text = "当前有未保存的按钮，关闭前是否保存？")
            }
        }
    }

    /**
     * 保存当前按钮后关闭：已有脚本名直接覆盖保存；
     * 未命名/新建的列表先弹框输入脚本名（同名需二次确认），取消输入则不关闭。
     */
    private fun saveAndClose() {
        val existingName = currentScriptName
        if (existingName != null) {
            QuickClickStore.saveScript(this, existingName, configs)
            savedSnapshot = configs.toList()
            Toast.makeText(this, "已保存脚本「$existingName」", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        showInputDialog("保存脚本", "", "输入脚本名称", ScriptNaming.MAX_NAME_LENGTH) { scriptName ->
            val save: () -> Unit = {
                QuickClickStore.saveScript(this, scriptName, configs)
                currentScriptName = scriptName
                savedSnapshot = configs.toList()
                Toast.makeText(this, "已保存脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            if (QuickClickStore.loadScripts(this).any { it.name == scriptName }) {
                confirmOverwrite(scriptName, save)
            } else {
                save()
            }
        }
    }

    /**
     * 透明度弹框：滑块拖动实时改 root.alpha（立即看到效果），
     * 拖动期间弹框整体隐藏、底部显示 20% 进行条，方便观察真实效果；
     * 「确定」写回 SharedPreferences 供下次启动恢复，「取消」/返回键恢复打开前的值。
     */
    private fun showOpacityDialog() {
        try {
            val previousAlpha = root.alpha
            val state = AppOpacityDialogState().apply { alpha = previousAlpha }
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
                    if (!confirmed) root.alpha = previousAlpha
                },
            ) {
                AppDialogPanel(
                    title = "悬浮窗透明度",
                    actions = {
                        AppDialogActions(
                            confirmText = "确定",
                            onConfirm = {
                                confirmed = true
                                QuickClickStore.setWindowAlpha(this@QuickClickService, state.alpha)
                                dialogRef?.dismiss()
                            },
                            cancelText = "取消",
                            onCancel = { dialogRef?.dismiss() },
                        )
                    },
                ) {
                    AppOpacityDialogContent(
                        state = state,
                        minAlpha = QuickClickStore.MIN_WINDOW_ALPHA,
                        onAlphaChange = {
                            root.alpha = it
                            preview.update(it)
                        },
                        onDraggingChange = { dragging, value, anchor ->
                            if (dragging && anchor != null) {
                                preview.show(
                                    dialogRef,
                                    value,
                                    anchor,
                                    QuickClickStore.MIN_WINDOW_ALPHA,
                                )
                            } else {
                                preview.hide()
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开透明度弹框失败", e)
        }
    }

    // ==================== 子按钮点击编辑 / 长按拖动排序 ====================

    /**
     * 编辑态子按钮触摸（由 Compose pointerInteropFilter 回调）：
     * - 普通点击：打开「编辑快捷按钮」弹框（内含删除），与新建共用同一套表单；
     * - 长按后移动：拖动排序（长按原地松手不再触发编辑，避免与点击重复打开弹框）。
     * 运行态触摸不经过这里，由 [onChildRunTouch] 处理（点击 / 长按与滑动透传）。
     * 排序结果直接写回 ui.configs，因此不需要 syncConfigsFromViews()。
     */
    private fun onChildTouch(index: Int, event: MotionEvent): Boolean {
        if (passThroughInjecting) return true
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragMoved = false
                dragActive = false
                dragIndex = index
                ui.pressedChildIndex = index                                  // 按下反馈
                // 展开区左上角的屏幕绝对坐标 = 窗口偏移 + 展开区在根视图内的偏移
                parentTopOnScreen = lp.y + expandedTopInRoot.toInt()
                parentLeftOnScreen = lp.x + expandedLeftInRoot.toInt()
                dragStartRunnable = Runnable {
                    dragActive = true
                    ui.draggingConfig = configs.getOrNull(index)               // 拖动中的按钮浮起
                    ui.dragOffsetY = 0f
                    ui.dragOffsetX = 0f
                }.also { dragHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragActive && !dragMoved && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragMoved = true                   // 一移动就取消长按/拖动判定，本次手势不当作点击
                    ui.pressedChildIndex = -1
                    dragStartRunnable?.let { dragHandler.removeCallbacks(it) }
                }
                if (dragActive) {
                    performChildDrag(event)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragStartRunnable?.let { dragHandler.removeCallbacks(it) }
                dragStartRunnable = null
                ui.pressedChildIndex = -1
                if (dragActive) {
                    ui.dragOffsetY = 0f
                    ui.dragOffsetX = 0f
                    ui.draggingConfig = null
                    clampWindow()                      // 落位后校正窗口位置（列表顺序已同步）
                } else if (!dragMoved && event.actionMasked == MotionEvent.ACTION_UP) {
                    // 编辑态点击：打开该子按钮的修改/删除弹框
                    configs.getOrNull(dragIndex)?.let { showConfigDialog(it) }
                }
                dragActive = false
                dragMoved = false
                dragIndex = -1
                true
            }
            else -> false
        }
    }

    // ==================== 运行态子按钮手势透传 ====================

    /**
     * 运行态子按钮触摸：点击执行配置动作；长按 / 滑动不属于点击，
     * 把这段操作回放到下层应用（悬浮窗临时不可触摸），让被按钮遮住的区域仍能长按、滑动。
     *
     * 系统不允许把进行中的触摸流转交给下层窗口，所以只能「识别后重放」：
     * 长按一达到长按阈值就立即注入，滑动在抬手后按采样轨迹注入。
     */
    private fun onChildRunTouch(index: Int, event: MotionEvent): Boolean {
        if (ui.isEditing) return true           // 编辑态走 onChildTouch（拖动排序）
        if (passThroughInjecting) return true   // 回放期间吞掉落回本窗口的事件

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPassThroughTimer()
                passThroughIndex = index
                passThroughDownX = event.rawX
                passThroughDownY = event.rawY
                passThroughSwipe = false
                passThroughInjected = false
                passThroughPoints.clear()
                passThroughTimes.clear()
                passThroughPoints.add(event.rawX)
                passThroughPoints.add(event.rawY)
                passThroughTimes.add(event.eventTime)
                ui.pressedChildIndex = index
                armPassThroughLongPress()
            }
            MotionEvent.ACTION_MOVE -> {
                if (passThroughIndex < 0) return true
                val dx = event.rawX - passThroughDownX
                val dy = event.rawY - passThroughDownY
                if (!passThroughSwipe && dx * dx + dy * dy > touchSlop * touchSlop) {
                    passThroughSwipe = true
                    ui.pressedChildIndex = -1        // 已确认是滑动，取消按下反馈
                    cancelPassThroughTimer()         // 不再按长按透传，等抬手后整段回放
                }
                if (passThroughSwipe && !passThroughInjected &&
                    event.eventTime - passThroughTimes.last() >= 8L
                ) {
                    passThroughPoints.add(event.rawX)
                    passThroughPoints.add(event.rawY)
                    passThroughTimes.add(event.eventTime)
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelPassThroughTimer()
                ui.pressedChildIndex = -1
                val pressedIndex = passThroughIndex
                passThroughIndex = -1
                if (pressedIndex < 0) return true
                when {
                    passThroughInjected -> Unit      // 长按已透传
                    passThroughSwipe -> forwardPassThroughSwipe()
                    else -> configs.getOrNull(pressedIndex)?.let { onChildClicked(pressedIndex, it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPassThroughTimer()
                ui.pressedChildIndex = -1
                passThroughIndex = -1
            }
        }
        return true
    }

    /** 按下后开始计时：按住不动到长按阈值，就按下层应用的长按处理 */
    private fun armPassThroughLongPress() {
        val runnable = Runnable {
            passThroughTimer = null
            if (passThroughIndex < 0 || passThroughSwipe || passThroughInjected) return@Runnable
            passThroughInjected = true
            ui.pressedChildIndex = -1
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        tapPath(clampGestureX(passThroughDownX), clampGestureY(passThroughDownY)),
                        0,
                        PASS_THROUGH_LONG_PRESS_MS,
                    )
                )
                .build()
            dispatchPassThrough(gesture, PASS_THROUGH_LONG_PRESS_MS)
        }
        passThroughTimer = runnable
        dragHandler.postDelayed(runnable, longPressTimeout)
    }

    private fun cancelPassThroughTimer() {
        passThroughTimer?.let { dragHandler.removeCallbacks(it) }
        passThroughTimer = null
    }

    /** 把抬手时收集到的轨迹原样回放成一条滑动，让下层列表跟着滚 */
    private fun forwardPassThroughSwipe() {
        if (passThroughPoints.size < 4) return   // 至少两个点才构成滑动
        val duration = (passThroughTimes.last() - passThroughTimes.first())
            .coerceIn(50L, PASS_THROUGH_MAX_SWIPE_MS)
        val startX = clampGestureX(passThroughPoints[0])
        val startY = clampGestureY(passThroughPoints[1])
        val endX = clampGestureX(passThroughPoints[passThroughPoints.size - 2])
        val endY = clampGestureY(passThroughPoints[passThroughPoints.size - 1])
        val path = Path().apply {
            moveTo(startX, startY)
            for (i in 2 until passThroughPoints.size step 2) {
                lineTo(clampGestureX(passThroughPoints[i]), clampGestureY(passThroughPoints[i + 1]))
            }
            // 轨迹没动（只触发过一次 MOVE）时补一点位移，避免空路径被判为无效手势
            if (startX == endX && startY == endY) lineTo(startX + 0.5f, startY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchPassThrough(gesture, duration)
    }

    /**
     * 注入透传手势：先把悬浮窗设为不可触摸（否则注入的手势会被本窗口吞掉），
     * 等一帧再提交；回调或兜底超时后恢复，期间本窗口的触摸一律忽略。
     */
    private fun dispatchPassThrough(gesture: GestureDescription, durationMs: Long) {
        val svc = AutoClickerAccessibilityService.instance
        if (svc == null) {
            setMainAvailable(false)
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
            resetPassThroughGesture()
            return
        }
        passThroughInjecting = true
        setWindowTouchable(false)
        val generation = ++passThroughGeneration
        val timeout = Runnable {
            if (generation == passThroughGeneration) endPassThroughInjection()
        }
        dragHandler.postDelayed(timeout, PASS_THROUGH_SWITCH_DELAY_MS + durationMs + 900L)
        dragHandler.postDelayed({
            if (!passThroughInjecting || generation != passThroughGeneration) return@postDelayed
            svc.dispatchQuickGesture(gesture) { ok ->
                dragHandler.post {
                    if (generation != passThroughGeneration) return@post
                    dragHandler.removeCallbacks(timeout)
                    endPassThroughInjection()
                    if (!ok) {
                        Toast.makeText(this, "手势被系统取消，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, PASS_THROUGH_SWITCH_DELAY_MS)
    }

    private fun endPassThroughInjection() {
        if (!passThroughInjecting) return
        passThroughInjecting = false
        resetPassThroughGesture()
        setWindowTouchable(true)
    }

    private fun resetPassThroughGesture() {
        cancelPassThroughTimer()
        passThroughIndex = -1
        passThroughSwipe = false
        passThroughInjected = false
        passThroughPoints.clear()
        passThroughTimes.clear()
        ui.pressedChildIndex = -1
    }

    /** 透传回放期间让悬浮窗不可触摸，注入的手势才能落到下层应用 */
    private fun setWindowTouchable(touchable: Boolean) {
        if (!rootAdded) return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "切换悬浮窗可触摸状态失败", e)
        }
    }

    /**
     * 拖动排序核心：子按钮跟随手指移动，中心越过相邻子按钮时交换位置。
     * 竖排看 Y、横排看 X；编辑态展开区最前面是 [+]，所以格子下标要整体后移一格。
     */
    private fun performChildDrag(event: MotionEvent) {
        val idx = dragIndex
        if (idx < 0 || idx >= configs.size) return

        val cellPx = dp(44)                 // 每格占位：40dp 按钮 + 4dp 间距
        val cellCenter = dp(24)             // 格内容中心 = 4dp 间距 + 20dp 半高
        val firstChild = if (ui.isEditing) 1 else 0

        if (ui.horizontal) {
            val dx = event.rawX - dragStartRawX
            ui.dragOffsetX = dx             // 跟手位移
            ui.dragOffsetY = 0f
            val fingerX = event.rawX - parentLeftOnScreen   // 手指相对展开区左侧的 X

            if (idx > 0 && fingerX < (firstChild + idx - 1) * cellPx + cellCenter) {
                // 越过前一格中心：前移一格，并补偿位移保持视图跟手
                val item = configs.removeAt(idx)
                configs.add(idx - 1, item)
                dragIndex = idx - 1
                ui.dragOffsetX = dx + cellPx
                return
            }
            if (idx < configs.size - 1 && fingerX > (firstChild + idx + 1) * cellPx + cellCenter) {
                // 越过下一格中心：后移一格
                val item = configs.removeAt(idx)
                configs.add(idx + 1, item)
                dragIndex = idx + 1
                ui.dragOffsetX = dx - cellPx
            }
        } else {
            val dy = event.rawY - dragStartRawY
            ui.dragOffsetY = dy             // 跟手位移
            ui.dragOffsetX = 0f
            val fingerY = event.rawY - parentTopOnScreen    // 手指相对展开区顶部的 Y

            if (idx > 0 && fingerY < (firstChild + idx - 1) * cellPx + cellCenter) {
                // 越过上一行中心：上移一格，并补偿位移保持视图跟手
                val item = configs.removeAt(idx)
                configs.add(idx - 1, item)
                dragIndex = idx - 1
                ui.dragOffsetY = dy + cellPx
                return
            }
            if (idx < configs.size - 1 && fingerY > (firstChild + idx + 1) * cellPx + cellCenter) {
                // 越过下一行中心：下移一格
                val item = configs.removeAt(idx)
                configs.add(idx + 1, item)
                dragIndex = idx + 1
                ui.dragOffsetY = dy - cellPx
            }
        }
    }

    // ==================== 识别 → 动作链路 ====================

    /** 子按钮点击：防抖 → 识别坐标 → 按动作类型构造并注入手势 */
    private fun onChildClicked(index: Int, cfg: QuickClickConfig) {
        if (ui.isEditing) return   // 编辑态不触发识别/动作

        // 防抖：间隔内不重复执行同一按钮（间隔可在“设置”中调整）。
        // 按列表下标而非名称计次：名称可重复（默认名相同、重命名后重名），
        // 用名称会让两个不同按钮互相压制，也会让 map 随改名无限增长。
        val now = System.currentTimeMillis()
        val debounce = QuickClickStore.getDebounce(this)
        val last = lastClickAt[index] ?: 0L
        if (now - last < debounce) return
        lastClickAt[index] = now

        when (cfg.type) {
            QuickClickConfig.TYPE_TEXT -> executeTextClick(cfg)
            QuickClickConfig.TYPE_IMAGE -> executeImageClick(cfg)
            QuickClickConfig.TYPE_COORD -> executeCoordinateClick(cfg)
        }
    }

    /** 坐标动作：直接把配置里的屏幕绝对坐标交给手势执行 */
    private fun executeCoordinateClick(cfg: QuickClickConfig) {
        val svc = AutoClickerAccessibilityService.instance
        if (svc == null) {
            setMainAvailable(false)
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
            return
        }
        performActionAt(svc, cfg.x, cfg.y, cfg)
    }

    /**
     * 统一动作出口：识别坐标 → 随机偏移（整条链路只加一次）→ 按动作类型构造手势 → 注入。
     * 点击 / 双击 / 长按以该点为中心；滑动以该点为起点，终点 = 起点 + 方向 × 距离。
     */
    private fun performActionAt(
        svc: AutoClickerAccessibilityService,
        x: Float,
        y: Float,
        cfg: QuickClickConfig,
    ) {
        val start = applyRandomOffset(x, y, cfg)
        val gesture = buildActionGesture(start.x, start.y, cfg)
        val submitted = svc.dispatchQuickGesture(gesture) { ok ->
            if (!ok) {
                Toast.makeText(this, "手势被系统取消，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        if (!submitted) {
            setMainAvailable(false)
            Toast.makeText(this, "手势提交失败，请到系统设置检查无障碍服务", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 识别坐标加随机偏移：每次动作只加一次。
     * 双击两次点击共用同一坐标；滑动只偏移起点，终点由方向向量算出，不再二次随机。
     * 结果夹在屏幕内并留 1dp 边距，避免贴边坐标让 dispatchGesture 失败。
     */
    private fun applyRandomOffset(x: Float, y: Float, cfg: QuickClickConfig): PointF {
        var px = x
        var py = y
        val radius = if (cfg.randomOffsetEnabled) {
            cfg.randomOffsetRadius.coerceIn(0, QuickClickConfig.MAX_RANDOM_RADIUS)
        } else {
            0
        }
        if (radius > 0) {
            px += Random.nextInt(-radius, radius + 1)
            py += Random.nextInt(-radius, radius + 1)
        }
        return PointF(clampGestureX(px), clampGestureY(py))
    }

    /** 按动作类型构造手势；传入坐标已夹进屏幕安全区 */
    private fun buildActionGesture(x: Float, y: Float, cfg: QuickClickConfig): GestureDescription {
        val builder = GestureDescription.Builder()
        when (cfg.actionType) {
            QuickClickConfig.ACTION_DOUBLE_CLICK -> {
                // 两次点击放进同一个 GestureDescription，避免连续 dispatch 被系统丢弃
                val interval = cfg.doubleClickInterval.coerceIn(
                    QuickClickConfig.MIN_DOUBLE_INTERVAL,
                    QuickClickConfig.MAX_DOUBLE_INTERVAL,
                )
                repeat(2) { index ->
                    builder.addStroke(
                        GestureDescription.StrokeDescription(
                            tapPath(x, y),
                            index * interval,
                            ACTION_TAP_DURATION_MS,
                        )
                    )
                }
            }
            QuickClickConfig.ACTION_LONG_PRESS -> {
                val duration = cfg.longPressDuration.coerceIn(
                    QuickClickConfig.MIN_LONG_PRESS,
                    QuickClickConfig.MAX_LONG_PRESS,
                )
                builder.addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0, duration))
            }
            QuickClickConfig.ACTION_SWIPE -> {
                val (dx, dy) = swipeVector(cfg.swipeDirection)
                val maxDistance = QuickClickConfig.maxSwipeDistance(getScreenWidth(), getScreenHeight())
                val distance = cfg.swipeDistance
                    .coerceIn(QuickClickConfig.MIN_SWIPE_DISTANCE, maxDistance)
                    .toFloat()
                var startX = x
                var startY = y
                var endX = clampGestureX(x + dx * distance)
                var endY = clampGestureY(y + dy * distance)
                // 贴着边沿时终点会被夹回起点，零长度路径系统直接判为无效手势（表现为「手势被系统取消」）。
                // 改成从起点反向滑同样距离，落点仍是这个按钮对应的位置。
                if (startX == endX && startY == endY) {
                    startX = clampGestureX(x - dx * distance)
                    startY = clampGestureY(y - dy * distance)
                    endX = clampGestureX(x)
                    endY = clampGestureY(y)
                }
                // 两个方向都被夹平（屏幕比滑动距离还窄）时补一点位移，保证手势非零长度
                if (startX == endX && startY == endY) endX = startX + 0.5f
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val duration = cfg.swipeDuration.coerceIn(
                    QuickClickConfig.MIN_SWIPE_DURATION,
                    QuickClickConfig.MAX_SWIPE_DURATION,
                )
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            }
            else -> builder.addStroke(
                GestureDescription.StrokeDescription(tapPath(x, y), 0, ACTION_TAP_DURATION_MS)
            )
        }
        return builder.build()
    }

    /** 单点路径：0.5px 的极小位移避免被系统识别成滑动 */
    private fun tapPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + 0.5f, y)
    }

    private fun swipeVector(direction: String): Pair<Float, Float> = when (direction) {
        QuickClickConfig.SWIPE_DOWN -> 0f to 1f
        QuickClickConfig.SWIPE_LEFT -> -1f to 0f
        QuickClickConfig.SWIPE_RIGHT -> 1f to 0f
        else -> 0f to -1f   // 默认上滑
    }

    /** 手势坐标安全边距：1dp */
    private val gestureMargin: Float
        get() = resources.displayMetrics.density.coerceAtLeast(1f)

    private fun clampGestureX(x: Float): Float =
        x.coerceIn(gestureMargin, (getScreenWidth() - gestureMargin).coerceAtLeast(gestureMargin))

    private fun clampGestureY(y: Float): Float =
        y.coerceIn(gestureMargin, (getScreenHeight() - gestureMargin).coerceAtLeast(gestureMargin))

    /** 文本匹配 → 拿坐标 → 执行动作（整条链路 try-catch，不崩溃） */
    private fun executeTextClick(cfg: QuickClickConfig) {
        try {
            val svc = AutoClickerAccessibilityService.instance
            if (svc == null) {
                setMainAvailable(false)
                Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
                return
            }
            // rootInActiveWindow 为 null 直接兜底提示，不狂点
            if (svc.rootInActiveWindow == null) {
                Toast.makeText(this, "无法获取当前界面内容，请重试", Toast.LENGTH_SHORT).show()
                return
            }

            val pt = svc.findTargetByText(cfg.text)
            if (pt == null) {
                Toast.makeText(this, "未找到目标「${cfg.text}」", Toast.LENGTH_SHORT).show()
                return
            }

            // 坐标用屏幕绝对坐标（findTargetByText 返回 getBoundsInScreen 中心点）
            performActionAt(svc, pt.x, pt.y, cfg)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "点击链路异常", e)
            Toast.makeText(this, "执行失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 无障碍断连时总按钮变灰（半透明） */
    private fun setMainAvailable(available: Boolean) {
        ui.mainAlpha = if (available) 1f else 0.4f
    }

    // ==================== 图像识别：截图捕获 / 相册选图 / 匹配点击 ====================

    /**
     * 隐藏悬浮窗自身，避免出现在截图里，或被系统相册等页面遮挡触摸。
     * 仅设 INVISIBLE 不够：窗口仍在，触摸仍可能落到它覆盖的区域。
     */
    private fun hideSelfForScreenshot() {
        if (!rootAdded) return
        root.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "隐藏悬浮窗失败", e)
        }
    }

    private fun showSelfAfterScreenshot() {
        if (!rootAdded) return
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "恢复悬浮窗失败", e)
        }
        root.visibility = View.VISIBLE
    }

    /**
     * 截屏/选图期间隐藏「配置弹框」：弹框是独立窗口，仅隐藏悬浮窗时它会被截进模板图，
     * 也会盖在系统相册上方挡住点击。隐藏时同时置为不可触摸，否则不可见窗口仍可能吞触摸。
     */
    private fun hideConfigForCapture(dialog: AlertDialog?) {
        val window = dialog?.window ?: return
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Exception) {
        }
        window.decorView.visibility = View.INVISIBLE
        // 不可触摸 + 不可聚焦：否则隐藏的弹框仍会抢相册的按键焦点/吞触摸
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
    }

    private fun showConfigAfterCapture(dialog: AlertDialog?) {
        val window = dialog?.window ?: return
        window.decorView.visibility = View.VISIBLE
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
    }

    /** 截图/选图结束：恢复悬浮窗与配置弹框 */
    private fun restoreAfterCapture(dialog: AlertDialog?) {
        showConfigAfterCapture(dialog)
        showSelfAfterScreenshot()
    }

    /** 「屏幕截图」：截当前屏 → 全屏区域选择器框选 → 存图并回填表单 */
    private fun startScreenshotCapture(form: QuickConfigFormState, dialog: AlertDialog?) {
        val svc = AutoClickerAccessibilityService.instance
        if (svc == null || !svc.isScreenshotSupported()) {
            Toast.makeText(this, "屏幕截图需 Android 11 及以上且无障碍已连接", Toast.LENGTH_LONG).show()
            return
        }
        if (capturePending || captureOverlay != null) return
        capturePending = true
        armCaptureWatchdog(dialog)
        hideSelfForScreenshot()
        hideConfigForCapture(dialog)
        // 淡出/隐藏需要一帧以上才真正反映到截图中，否则悬浮窗或弹框会残留在模板里
        matchHandler.postDelayed({
            if (destroyed) {
                capturePending = false
                return@postDelayed
            }
            svc.takeScreenshotAsync { screen ->
                cancelCaptureWatchdog()
                capturePending = false
                if (destroyed) {
                    screen?.recycle()      // 服务已销毁，图不会再有人用
                    return@takeScreenshotAsync
                }
                if (screen == null) {
                    restoreAfterCapture(dialog)
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                    return@takeScreenshotAsync
                }
                val overlay = RegionCaptureOverlay(this, object : RegionCaptureOverlay.ResultListener {
                    override fun onConfirm(cropped: android.graphics.Bitmap, regionInScreen: android.graphics.Rect) {
                        val name = ImageStore.generateFileName("shot")
                        val saved = ImageStore.saveImage(this@QuickClickService, cropped, name)
                        cropped.recycle()   // 裁剪图用完即回收，不留到 GC
                        if (saved) {
                            form.imageFileName = name
                            form.type = QuickClickConfig.TYPE_IMAGE
                            form.imageRegionRect =
                                "${regionInScreen.left},${regionInScreen.top},${regionInScreen.right},${regionInScreen.bottom}"
                            Toast.makeText(this@QuickClickService, "已截取模板图", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@QuickClickService, "保存截图失败", Toast.LENGTH_SHORT).show()
                        }
                        captureOverlay = null
                        restoreAfterCapture(dialog)
                    }

                    override fun onCancel() {
                        captureOverlay = null
                        restoreAfterCapture(dialog)
                    }
                })
                captureOverlay = overlay
                overlay.show(screen)
                // 框选浮层覆盖全屏，弹框/悬浮窗保持隐藏到确定或返回键取消后再恢复
            }
        }, HIDE_BEFORE_SCREENSHOT_MS)
    }

    /**
     * 截图请求兜底：无障碍的 takeScreenshot 在部分机型上会被静默丢弃（服务刚断开、
     * 系统限流），既不回调也不报错。没有兜底时悬浮窗与弹框一直藏着，
     * capturePending 也永久卡住，之后所有截图入口都点不动。
     */
    private fun armCaptureWatchdog(dialog: AlertDialog?) {
        cancelCaptureWatchdog()
        val runnable = Runnable {
            captureWatchdog = null
            if (destroyed || !capturePending) return@Runnable
            android.util.Log.w("QuickClick", "截图请求超时未回调，已恢复悬浮窗")
            capturePending = false
            restoreAfterCapture(dialog)
            Toast.makeText(this, "截图超时，请重试", Toast.LENGTH_SHORT).show()
        }
        captureWatchdog = runnable
        matchHandler.postDelayed(runnable, CAPTURE_WATCHDOG_MS)
    }

    private fun cancelCaptureWatchdog() {
        captureWatchdog?.let { matchHandler.removeCallbacks(it) }
        captureWatchdog = null
    }

    /** 「屏幕取点」：全屏置灰网格浮层 → 点选位置 → 回填 X/Y（触摸不会穿透到下层应用） */
    private fun startCoordinatePick(form: QuickConfigFormState, dialog: AlertDialog?) {
        pickOverlay?.dismiss()
        hideSelfForScreenshot()
        hideConfigForCapture(dialog)
        val overlay = CoordinatePickOverlay(this, object : CoordinatePickOverlay.Listener {
            override fun onPicked(x: Int, y: Int) {
                pickOverlay = null
                form.type = QuickClickConfig.TYPE_COORD
                form.coordXText = x.toString()
                form.coordYText = y.toString()
                restoreAfterCapture(dialog)
            }

            override fun onCancel() {
                pickOverlay = null
                restoreAfterCapture(dialog)
            }
        })
        pickOverlay = overlay
        overlay.show()
    }

    /**
     * 「从相册选」：先隐藏悬浮窗与弹框（它们是系统级浮层，会盖住相册并挡住点击），
     * 再拉起透明中转 Activity 选图 + 裁剪；取消、返回、选图完成后都回到配置弹框。
     */
    private fun startGalleryPick(form: QuickConfigFormState, dialog: AlertDialog?) {
        if (capturePending || captureOverlay != null) return
        capturePending = true
        hideSelfForScreenshot()
        hideConfigForCapture(dialog)
        ImagePickActivity.pendingResult = { fileName, _, _ ->
            capturePending = false
            restoreAfterCapture(dialog)
            if (fileName != null) {
                form.imageFileName = fileName
                form.type = QuickClickConfig.TYPE_IMAGE
                // 相册图无屏幕框选坐标，匹配退回全屏
                form.imageRegionRect = ""
                Toast.makeText(this, "已选择模板图", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            startActivity(
                Intent(this, ImagePickActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "拉起选图失败", e)
            capturePending = false
            ImagePickActivity.pendingResult = null
            restoreAfterCapture(dialog)
            Toast.makeText(this, "无法打开相册选择", Toast.LENGTH_SHORT).show()
        }
    }

    /** 图像识别点击：截屏 → 匹配模板 → dispatchGesture 点击中心；未找到按参数重试 */
    private fun executeImageClick(cfg: QuickClickConfig) {
        val svc = AutoClickerAccessibilityService.instance
        if (svc == null) {
            setMainAvailable(false)
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
            return
        }
        if (!svc.isScreenshotSupported()) {
            Toast.makeText(this, "图像识别需 Android 11 及以上", Toast.LENGTH_LONG).show()
            return
        }
        if (matchInProgress) {
            Toast.makeText(this, "正在识别，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        // 锁在解码前占上：解码期间再点一次不应开出第二条链路
        matchInProgress = true
        if (matchExecutor.isShutdown) {
            matchInProgress = false
            return
        }
        val region = parseRegionRect(cfg.imageRegionRect)
        // 模板是磁盘 IO + 位图解码，放到匹配线程；放主线程会让大图在点按钮时卡顿
        matchExecutor.execute {
            val template = ImageStore.loadImage(this, cfg.imageFileName)
            matchHandler.post {
                if (destroyed) {
                    template?.takeIf { !it.isRecycled }?.recycle()
                    matchInProgress = false
                    return@post
                }
                if (template == null) {
                    matchInProgress = false
                    Toast.makeText(this, "模板图片缺失，请重新编辑选择", Toast.LENGTH_SHORT).show()
                    return@post
                }
                tryMatch(svc, cfg, template, region, attempt = 0)
            }
        }
    }

    /**
     * 单次「隐藏悬浮窗 → 截屏 → 后台匹配」。
     * 匹配放在 [matchExecutor] 上执行，避免全屏 NCC 计算阻塞主线程；
     * 结果再回到主线程做点击/重试。
     */
    private fun tryMatch(
        svc: AutoClickerAccessibilityService,
        cfg: QuickClickConfig,
        template: android.graphics.Bitmap,
        region: android.graphics.Rect?,
        attempt: Int,
    ) {
        hideSelfForScreenshot()
        matchHandler.postDelayed({
            svc.takeScreenshotAsync { screen ->
                if (destroyed) {
                    // 服务已销毁：不再恢复窗口 / 提交匹配任务，图也没人用
                    screen?.recycle()
                    matchInProgress = false
                    return@takeScreenshotAsync
                }
                showSelfAfterScreenshot()
                if (screen == null) {
                    onMatchFailed(svc, cfg, template, region, attempt, "截屏失败，请重试")
                    return@takeScreenshotAsync
                }
                // onDestroy 会 shutdownNow；这里与回调同在主线程，检查后再提交不会被拒
                if (matchExecutor.isShutdown) {
                    screen.recycle()
                    matchInProgress = false
                    return@takeScreenshotAsync
                }
                matchExecutor.execute {
                    val result = try {
                        if (cfg.imageRegion == "last" && region != null) {
                            ImageMatcher.matchInRegion(
                                screen, template,
                                region = region,
                                threshold = cfg.imageThreshold,
                                scale = cfg.imageScale,
                                grayMode = cfg.imageGrayMode,
                            )
                        } else {
                            ImageMatcher.match(
                                screen, template,
                                threshold = cfg.imageThreshold,
                                scale = cfg.imageScale,
                                grayMode = cfg.imageGrayMode,
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("QuickClick", "模板匹配异常", e)
                        ImageMatcher.MatchResult(0f, android.graphics.PointF(0f, 0f))
                    } finally {
                        screen.recycle()
                    }
                    matchHandler.post { onMatchResult(svc, cfg, template, region, attempt, result) }
                }
            }
        }, HIDE_BEFORE_SCREENSHOT_MS)
    }

    private fun onMatchResult(
        svc: AutoClickerAccessibilityService,
        cfg: QuickClickConfig,
        template: android.graphics.Bitmap,
        region: android.graphics.Rect?,
        attempt: Int,
        result: ImageMatcher.MatchResult,
    ) {
        if (destroyed || svc !== AutoClickerAccessibilityService.instance) {
            finishImageMatch(template)
            return
        }
        if (result.confidence >= cfg.imageThreshold) {
            finishImageMatch(template)
            performActionAt(svc, result.point.x, result.point.y, cfg)
        } else {
            onMatchFailed(
                svc, cfg, template, region, attempt,
                "未匹配到目标图片（最高相似度 ${"%.2f".format(result.confidence)}）",
            )
        }
    }

    /** 单次失败：未用尽重试次数则重新截屏匹配，否则提示并释放模板图 */
    private fun onMatchFailed(
        svc: AutoClickerAccessibilityService,
        cfg: QuickClickConfig,
        template: android.graphics.Bitmap,
        region: android.graphics.Rect?,
        attempt: Int,
        message: String,
    ) {
        if (destroyed) {
            finishImageMatch(template)
            return
        }
        if (attempt < cfg.imageRetryCount) {
            matchHandler.postDelayed(
                { tryMatch(svc, cfg, template, region, attempt + 1) },
                cfg.imageRetryDelay,
            )
        } else {
            finishImageMatch(template)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 结束一次识别链路：释放模板图并解除并发锁 */
    private fun finishImageMatch(template: android.graphics.Bitmap) {
        matchInProgress = false
        if (!template.isRecycled) template.recycle()
    }

    private fun parseRegionRect(s: String): android.graphics.Rect? {
        val parts = s.split(",")
        if (parts.size != 4) return null
        val l = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        val r = parts[2].toIntOrNull() ?: return null
        val b = parts[3].toIntOrNull() ?: return null
        return android.graphics.Rect(l, t, r, b)
    }

    // ==================== “+”新建 / 点击编辑 配置弹框 ====================

    /**
     * 配置弹框。[editTarget] 为 null 时是「+」新建：填表后追加到列表末尾；
     * 传入已有配置时是点击编辑：表单回填名称/目标文字/动作参数，确认后原地替换（保持顺序）。
     */
    private fun showConfigDialog(editTarget: QuickClickConfig? = null) {
        try {
            // === 原：inflate(dialog_quick_click_config.xml) + findViewById 取值 ===
            // === 现：ComposeView + QuickConfigDialogContent，取值改为读写表单状态 ===
            val form = QuickConfigFormState().apply {
                if (editTarget != null) {
                    name = editTarget.name
                    targetText = editTarget.text
                    type = editTarget.type
                    coordXText = if (editTarget.type == QuickClickConfig.TYPE_COORD) {
                        editTarget.x.toInt().toString()
                    } else ""
                    coordYText = if (editTarget.type == QuickClickConfig.TYPE_COORD) {
                        editTarget.y.toInt().toString()
                    } else ""
                    imageFileName = editTarget.imageFileName
                    imageRegionRect = editTarget.imageRegionRect
                    thresholdText = editTarget.imageThreshold.toString()
                    scaleText = editTarget.imageScale.toString()
                    regionFull = editTarget.imageRegion != "last"
                    grayMode = editTarget.imageGrayMode
                    retryText = editTarget.imageRetryCount.toString()
                    retryDelayText = editTarget.imageRetryDelay.toString()
                    // 编辑图像按钮时默认展开高级设置，便于直接调参
                    if (editTarget.type == QuickClickConfig.TYPE_IMAGE) showAdvanced = true
                    actionType = editTarget.actionType
                    doubleClickIntervalText = editTarget.doubleClickInterval.toString()
                    longPressDurationText = editTarget.longPressDuration.toString()
                    swipeDirection = editTarget.swipeDirection
                    swipeDistanceText = editTarget.swipeDistance.toString()
                    swipeDurationText = editTarget.swipeDuration.toString()
                    randomOffsetEnabled = editTarget.randomOffsetEnabled
                    randomOffsetRadiusText = editTarget.randomOffsetRadius.toString()
                }
            }
            val isEdit = editTarget != null
            // 弹框实例需在 Compose 内容之前声明：截图/取点回调要先隐藏弹框窗口
            var dialogRef: AlertDialog? = null
            var committed = false
            val host = ServiceComposeHost()
            // 「删除」只在编辑态出现：先收窄空安全，避免在 Compose lambda 里反复判空
            val deleteAction: (@Composable RowScope.() -> Unit)? = if (editTarget != null) {
                val target = editTarget
                {
                    AppButton(
                        text = "删除",
                        onClick = { confirmDeleteConfig(target, form, dialogRef) },
                        variant = AppButtonVariant.DangerText,
                    )
                }
            } else null

            dialogRef = ServiceDialogs.show(
                service = this,
                host = host,
                widthRatio = 0.9f,
                onDismiss = {
                    host.destroy()
                    // 取消/关闭时清理本次新截取但未保存的模板图
                    if (!committed) {
                        val pendingImage = form.imageFileName
                        val originalImage = editTarget?.takeIf { it.type == QuickClickConfig.TYPE_IMAGE }
                            ?.imageFileName.orEmpty()
                        if (pendingImage.isNotEmpty() && pendingImage != originalImage) {
                            ImageStore.deleteImage(this, pendingImage)
                        }
                    }
                },
            ) {
                AppDialogPanel(
                    title = if (isEdit) "编辑快捷按钮" else "新建快捷按钮",
                    actions = {
                        AppDialogActions(
                            confirmText = if (isEdit) "保存" else "新建",
                            onConfirm = {
                                if (commitConfigForm(form, editTarget)) {
                                    committed = true
                                    dialogRef?.dismiss()
                                }
                            },
                            cancelText = "取消",
                            onCancel = { dialogRef?.dismiss() },
                            leading = deleteAction,
                        )
                    },
                ) {
                    QuickConfigDialogContent(
                        state = form,
                        nameFilter = ::limitQuickName,   // 名称限长：含中文 ≤3 字、纯英文/数字 ≤6 字符
                        screenshotSupported = AutoClickerAccessibilityService.instance
                            ?.isScreenshotSupported() == true,
                        onCaptureScreenshot = { startScreenshotCapture(form, dialogRef) },
                        onPickFromGallery = { startGalleryPick(form, dialogRef) },
                        onPickCoordinate = { startCoordinatePick(form, dialogRef) },
                        screenWidth = getScreenWidth(),
                        screenHeight = getScreenHeight(),
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开配置弹框失败", e)
        }
    }

    /**
     * 提交「新建 / 编辑快捷按钮」表单：校验并写入按钮列表。
     * 校验不通过时弹 Toast 并返回 false（弹框保持打开，用户可继续修改）。
     */
    private fun commitConfigForm(form: QuickConfigFormState, editTarget: QuickClickConfig?): Boolean {
        val name = limitQuickName(
            form.name.trim().ifEmpty {
                // 编辑时清空名称 → 保留原名；新建时清空 → 「按钮N」
                editTarget?.name ?: "按钮${configs.size + 1}"
            }
        )
        val maxSwipeDistance = QuickClickConfig.maxSwipeDistance(getScreenWidth(), getScreenHeight())
        // 超范围输入按上限保存并明确提示，避免用户误以为输入值已生效
        if (form.actionType == QuickClickConfig.ACTION_LONG_PRESS && form.longPressExceedsLimit()) {
            Toast.makeText(
                this,
                "长按时长上限 ${QuickClickConfig.MAX_LONG_PRESS}ms，已按上限保存",
                Toast.LENGTH_SHORT,
            ).show()
        }
        if (form.actionType == QuickClickConfig.ACTION_SWIPE &&
            form.swipeDistanceExceedsLimit(maxSwipeDistance)
        ) {
            Toast.makeText(
                this,
                "滑动距离上限 ${maxSwipeDistance}px（屏幕短边一半），已按上限保存",
                Toast.LENGTH_SHORT,
            ).show()
        }
        val newConfig = when (form.type) {
            QuickClickConfig.TYPE_IMAGE -> {
                if (form.imageFileName.isEmpty()) {
                    Toast.makeText(this, "请先截图或从相册选择模板图片", Toast.LENGTH_SHORT).show()
                    return false
                }
                QuickClickConfig(
                    name = name,
                    type = QuickClickConfig.TYPE_IMAGE,
                    imageFileName = form.imageFileName,
                    imageThreshold = form.thresholdOrDefault(),
                    imageScale = form.scaleOrDefault(),
                    imageRegion = if (form.regionFull) "full" else "last",
                    imageGrayMode = form.grayMode,
                    imageRetryCount = form.retryOrDefault(),
                    imageRetryDelay = form.retryDelayOrDefault(),
                    imageRegionRect = form.imageRegionRect,
                )
            }
            QuickClickConfig.TYPE_COORD -> {
                val x = form.coordXText.trim().toIntOrNull()
                val y = form.coordYText.trim().toIntOrNull()
                if (x == null || y == null) {
                    Toast.makeText(this, "请输入有效的屏幕坐标", Toast.LENGTH_SHORT).show()
                    return false
                }
                val screenW = getScreenWidth()
                val screenH = getScreenHeight()
                if (x !in 0 until screenW || y !in 0 until screenH) {
                    Toast.makeText(
                        this,
                        "坐标需在屏幕范围内（0~${screenW - 1} / 0~${screenH - 1}）",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return false
                }
                QuickClickConfig(
                    name = name,
                    type = QuickClickConfig.TYPE_COORD,
                    x = x.toFloat(),
                    y = y.toFloat(),
                )
            }
            else -> {
                val text = form.targetText.trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "请输入目标文字", Toast.LENGTH_SHORT).show()
                    return false
                }
                QuickClickConfig(
                    name = name,
                    type = QuickClickConfig.TYPE_TEXT,
                    text = text,
                )
            }
        }.copy(
            // 动作字段与识别方式正交：三种识别方式共用同一套动作参数
            actionType = form.actionType,
            doubleClickInterval = form.doubleClickIntervalOrDefault(),
            longPressDuration = form.longPressDurationOrDefault(),
            swipeDirection = form.swipeDirection,
            swipeDistance = form.swipeDistanceOrDefault(maxSwipeDistance),
            swipeDuration = form.swipeDurationOrDefault(),
            randomOffsetEnabled = form.randomOffsetEnabled,
            randomOffsetRadius = form.randomOffsetRadiusOrDefault(),
        )

        // 清理不再被任何按钮引用的模板图：换图时删旧图；改类型后删临时新图
        val oldImage = editTarget?.takeIf { it.type == QuickClickConfig.TYPE_IMAGE }
            ?.imageFileName.orEmpty()
        if (oldImage.isNotEmpty() &&
            (newConfig.type != QuickClickConfig.TYPE_IMAGE || newConfig.imageFileName != oldImage)
        ) {
            ImageStore.deleteImage(this, oldImage)
        }
        if (form.imageFileName.isNotEmpty() &&
            newConfig.type != QuickClickConfig.TYPE_IMAGE &&
            form.imageFileName != oldImage
        ) {
            ImageStore.deleteImage(this, form.imageFileName)
        }

        if (editTarget != null) {
            // 按引用定位原条目，原地替换保持顺序
            val idx = configs.indexOfFirst { it === editTarget }
            if (idx >= 0) configs[idx] = newConfig
            Toast.makeText(this, "已更新「$name」", Toast.LENGTH_SHORT).show()
        } else {
            // 新按钮插到列表末尾：编辑态视图中即位于 + 之后
            configs.add(newConfig)
            clampWindow()
            Toast.makeText(this, "已新建「$name」", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * 「删除」二次确认：确认后移除该按钮并清理模板图，同时关闭编辑弹框。
     * 确认框盖在编辑弹框之上，取消只关确认框，编辑弹框保留。
     */
    private fun confirmDeleteConfig(
        editTarget: QuickClickConfig,
        form: QuickConfigFormState,
        parentDialog: AlertDialog?,
    ) {
        showConfirmDialog(
            title = "删除按钮",
            message = "确定删除按钮「${editTarget.name}」？",
            confirmText = "删除",
            danger = true,
        ) {
            val idx = configs.indexOfFirst { it === editTarget }
            if (idx >= 0) configs.removeAt(idx)
            // 删除图像按钮时清理其模板图（含本次新截但尚未保存的图）
            val oldImage = editTarget.imageFileName
            if (editTarget.type == QuickClickConfig.TYPE_IMAGE && oldImage.isNotEmpty()) {
                ImageStore.deleteImage(this, oldImage)
            }
            if (form.imageFileName.isNotEmpty() && form.imageFileName != oldImage) {
                ImageStore.deleteImage(this, form.imageFileName)
            }
            clampWindow()
            Toast.makeText(this, "已删除「${editTarget.name}」", Toast.LENGTH_SHORT).show()
            parentDialog?.dismiss()
        }
    }

    // ==================== “设置” → 脚本管理弹框 ====================

    /**
     * 脚本管理：另存为 / 加载切换 / 重命名 / 删除。
     * 悬浮框上的按钮列表只存在于内存中（启动为空白编辑态），
     * 只有另存为命名后的脚本才会被保存，下次启动可在本面板加载。
     */
    private fun showScriptManagerDialog() {
        try {
            // === 原：inflate(dialog_script_manager.xml) + buildScriptRow 动态添加行 ===
            // === 现：统一弹框外框 + ScriptManagerDialogContent，列表由状态驱动 ===
            val state = ScriptManagerState()
            val host = ServiceComposeHost()
            var dialogRef: AlertDialog? = null
            // 重新读取脚本列表 → 触发 Compose 刷新（等价原来的 removeAllViews + 逐行 addView）
            val refresh: () -> Unit = {
                state.scripts = QuickClickStore.loadScripts(this)
            }

            dialogRef = ServiceDialogs.show(
                service = this,
                host = host,
                widthRatio = 0.9f,
                onDismiss = { host.destroy() },
            ) {
                AppDialogPanel(
                    title = "脚本管理",
                    actions = {
                        AppButton(
                            text = "关闭",
                            onClick = { dialogRef?.dismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            variant = AppButtonVariant.Outlined,
                        )
                    },
                ) {
                    ScriptManagerDialogContent(
                        state = state,
                        onSaveCurrent = { saveCurrentScript(refresh) },
                        onLoadScript = { script -> handleLoadScript(script, dialogRef, refresh) },
                        onRenameScript = { script -> handleRenameScript(script, refresh) },
                        onDeleteScript = { script -> handleDeleteScript(script, refresh) },
                    )
                }
            }
            refresh()
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开脚本管理失败", e)
        }
    }

    /** 「保存当前按钮」：把内存中的按钮列表存成命名脚本（同名则先确认覆盖） */
    private fun saveCurrentScript(onChanged: () -> Unit) {
        if (configs.isEmpty()) {
            Toast.makeText(this, "当前没有按钮，请先添加", Toast.LENGTH_SHORT).show()
            return
        }
        // 从脚本加载来的按钮，预填该脚本名（可改可直接保存）
        showInputDialog("保存脚本", currentScriptName ?: "", "输入脚本名称", ScriptNaming.MAX_NAME_LENGTH) { scriptName ->
            if (QuickClickStore.loadScripts(this).any { it.name == scriptName }) {
                // 名称已存在：确认是否覆盖
                confirmOverwrite(scriptName) {
                    QuickClickStore.saveScript(this, scriptName, configs)
                    currentScriptName = scriptName
                    savedSnapshot = configs.toList()
                    Toast.makeText(this, "已覆盖脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            } else {
                QuickClickStore.saveScript(this, scriptName, configs)
                currentScriptName = scriptName
                savedSnapshot = configs.toList()
                Toast.makeText(this, "已保存脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                onChanged()
            }
        }
    }

    /** 点击脚本名称：加载/切换；悬浮框上已有按钮时先确认覆盖 */
    private fun handleLoadScript(script: QuickScript, dialog: AlertDialog?, onChanged: () -> Unit) {
        when {
            script.configs.isEmpty() ->
                Toast.makeText(this, "脚本「${script.name}」没有按钮", Toast.LENGTH_SHORT).show()
            // 悬浮框上已有按钮：先确认覆盖，避免未保存的节点被直接替换掉
            configs.isNotEmpty() -> confirmReplaceByScript(script, dialog)
            else -> loadScript(script, dialog)
        }
    }

    /** 「改名」：重命名脚本（新名已存在则提示） */
    private fun handleRenameScript(script: QuickScript, onChanged: () -> Unit) {
        showInputDialog("重命名脚本", script.name, "输入新名称", ScriptNaming.MAX_NAME_LENGTH) { newName ->
            if (QuickClickStore.renameScript(this, script.name, newName)) {
                if (currentScriptName == script.name) currentScriptName = newName
                onChanged()
                Toast.makeText(this, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "脚本名「$newName」已存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 「删除」：确认后删除脚本 */
    private fun handleDeleteScript(script: QuickScript, onChanged: () -> Unit) {
        showConfirmDialog(
            title = "删除脚本",
            message = "确定删除脚本「${script.name}」？",
            confirmText = "删除",
            danger = true,
        ) {
            QuickClickStore.deleteScript(this, script.name)
            // 当前列表来自被删脚本：此后视为未保存，关闭悬浮窗时会重新提示保存
            if (currentScriptName == script.name) {
                currentScriptName = null
                savedSnapshot = null
            }
            onChanged()
            Toast.makeText(this, "已删除脚本「${script.name}」", Toast.LENGTH_SHORT).show()
        }
    }

    /** 载入脚本：替换悬浮框上的按钮，并关闭脚本管理弹框 */
    private fun loadScript(script: QuickScript, dialog: AlertDialog?) {
        configs.clear()
        configs.addAll(script.configs)
        currentScriptName = script.name
        savedSnapshot = configs.toList()
        clampWindow()
        dialog?.dismiss()
        Toast.makeText(this, "已加载脚本「${script.name}」", Toast.LENGTH_SHORT).show()
    }

    /** 悬浮框上已有按钮时，加载脚本前确认覆盖 */
    private fun confirmReplaceByScript(script: QuickScript, dialog: AlertDialog?) {
        showConfirmDialog(
            title = "加载脚本",
            message = "当前按键节点未保存，加载脚本「${script.name}」将覆盖它们，是否覆盖？",
            confirmText = "覆盖",
        ) {
            loadScript(script, dialog)
        }
    }

    /** 同名脚本覆盖确认 */
    private fun confirmOverwrite(scriptName: String, onConfirm: () -> Unit) {
        showConfirmDialog(
            title = "覆盖脚本",
            message = "脚本「$scriptName」已存在，是否覆盖？",
            confirmText = "覆盖",
            onConfirm = onConfirm,
        )
    }

    /**
     * 统一确认弹框：标题 + 说明 + 底部按钮（取消 / 主操作）。
     * 危险操作（删除）把主按钮换成 Danger 变体（红色实心）。
     */
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

    /**
     * 单行输入弹框（保存脚本 / 重命名共用）。
     * 输入框用 AppTextField（保留原生 EditText 内核，保证 overlay 内软键盘可弹）。
     */
    private fun showInputDialog(
        title: String,
        initial: String,
        hint: String,
        maxLen: Int,
        onConfirm: (String) -> Unit
    ) {
        val state = InputDialogState().apply { value = initial }
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
                                Toast.makeText(this@QuickClickService, "名称不能为空", Toast.LENGTH_SHORT).show()
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

    /** 兜底截断按钮名称（防止绕过输入过滤器） */
    private fun limitQuickName(name: String): String {
        val max = quickNameMaxLength(name)
        return if (name.length > max) name.substring(0, max) else name
    }

    // ==================== 前台服务 / 工具 ====================

    /** 前台服务：维持悬浮窗与识别链路存活 */
    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "quick_click"
            val channel = NotificationChannel(
                channelId, "快捷点击", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

            val stopIntent = Intent(this, QuickClickService::class.java).apply {
                action = ACTION_STOP
            }
            val pendingStop = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("悬浮快捷点击运行中")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pendingStop)
                .build()
            startForeground(2, notification)
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
        android.util.Log.w("QuickClick", "dataSync 前台服务达到运行时长上限，主动停止")
        stopSelf(startId)
    }

    /** 展开 / 折叠 / 编辑态变化后：重新决定横排竖排，并按当前吸附轴贴边 + 夹回安全区 */
    private fun clampWindow() {
        syncLayoutMode()
        // Compose 内容尺寸变化后需等一次布局完成才能拿到新宽高，故 post 到下一帧读取
        root.post {
            if (!rootAdded) return@post
            val bounds = FloatingWindowBounds.safeBounds(windowManager, root, lp, edgeMargin())
            if (docksTopBottom()) {
                lp.y = if (dockedTop) {
                    bounds.top
                } else {
                    (bounds.bottom - root.height).coerceAtLeast(bounds.top)
                }
                lp.x = FloatingWindowBounds.clampInside(lp.x, root.width, bounds.left, bounds.right)
            } else {
                lp.x = if (dockedLeft) bounds.left else (bounds.right - root.width).coerceAtLeast(bounds.left)
                lp.y = FloatingWindowBounds.clampInside(lp.y, root.height, bounds.top, bounds.bottom)
            }
            try {
                windowManager.updateViewLayout(root, lp)
            } catch (e: Exception) {
                android.util.Log.e("QuickClick", "clampWindow 失败", e)
            }
        }
    }

    /** 竖排放不下时改用横向展开栏：横排长度相同，但占用的是更充裕的屏幕宽度 */
    private fun syncLayoutMode() {
        ui.horizontal = shouldUseHorizontalLayout(currentExpandedRows())
        val topBottom = docksTopBottom()
        if (topBottom != dockTopBottom) {
            // 换吸附轴（进入横排 / 转横屏 / 转回竖屏）：按窗口左上角所在的半区选最近的那条边。
            // 不用宽高算中心——旋转瞬间拿到的还是旧方向的视口尺寸。
            val bounds = FloatingWindowBounds.safeBounds(windowManager, root, lp, edgeMargin())
            if (topBottom) {
                dockedTop = lp.y < (bounds.top + bounds.bottom) / 2
            } else {
                dockedLeft = lp.x < (bounds.left + bounds.right) / 2
            }
            dockTopBottom = topBottom
        }
    }

    /** 横屏或横排时按钮条长边是水平的，吸附上下边；否则（竖排、竖屏折叠）贴左右边 */
    private fun docksTopBottom(): Boolean =
        ui.horizontal || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun edgeMargin(): Int = dp(12)

    private fun currentExpandedRows(): Int {
        if (!ui.isExpanded) return 0
        // 编辑态展开区还有 [+]/[脚本管理]/[透明度]/[关闭] 四格
        return configs.size + if (ui.isEditing) 4 else 0
    }

    private fun shouldUseHorizontalLayout(rows: Int): Boolean {
        if (rows <= 0) return false
        val length = dp(56 + rows * 44)   // 主按钮 40dp + 上下各 8dp 内边距 + 每格 40dp 按钮与 4dp 间距
        val size = FloatingWindowBounds.usableSize(windowManager, root, lp)
        return length > size.y && length <= size.x
    }

    /** 横竖都放不下（按钮特别多）时明确提示，而不是让尾部按钮悄悄跑出屏幕 */
    private fun warnIfExpandedTooLarge() {
        val rows = currentExpandedRows()
        if (rows <= 0) return
        val length = dp(56 + rows * 44)
        val size = FloatingWindowBounds.usableSize(windowManager, root, lp)
        if (length > size.x && length > size.y) {
            Toast.makeText(this, "当前屏幕放不下全部按钮，请旋转设备或减少按钮数量", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getScreenWidth(): Int {
        val p = Point()
        windowManager.defaultDisplay.getRealSize(p)
        return p.x
    }

    private fun getScreenHeight(): Int {
        val p = Point()
        windowManager.defaultDisplay.getRealSize(p)
        return p.y
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
