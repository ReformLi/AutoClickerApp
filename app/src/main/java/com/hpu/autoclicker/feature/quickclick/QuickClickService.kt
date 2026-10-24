package com.hpu.autoclicker.feature.quickclick

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 悬浮快捷点击服务（新功能）
 *
 * 链路：用户手动点悬浮子按钮 → 无障碍文本匹配 → 拿到坐标 → dispatchGesture 模拟点击
 *
 * 三种状态：
 * - 折叠态：屏幕上只有一个总按钮，零编辑控件；单击 → 展开
 * - 展开态：总按钮 + 已配置子按钮纵向排列；再单击总按钮 → 折叠
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

        /** 按钮名称限长规则：含中文 ≤2 字，纯英文/数字 ≤3 字符 */
        private fun quickNameMaxLength(text: CharSequence): Int =
            if (text.any { it.code > 0x2E80 }) 2 else 3

        /** 输入时按语言截断，避免按钮上出现省略号 */
        private val QUICK_NAME_FILTER = InputFilter { source, start, end, dest, dstart, dend ->
            val result = dest.subSequence(0, dstart).toString() +
                source.subSequence(start, end).toString() +
                dest.subSequence(dend, dest.length).toString()
            val max = quickNameMaxLength(result)
            if (result.length <= max) {
                null
            } else {
                val keep = max - (dest.length - (dend - dstart))
                if (keep <= 0) "" else source.subSequence(start, start + keep)
            }
        }
    }

    private lateinit var windowManager: WindowManager

    /** 悬浮窗根视图：由 XML 的 LinearLayout 换成 ComposeView */
    private lateinit var root: ComposeView
    private lateinit var lp: WindowManager.LayoutParams

    /** ComposeView 在 Service 中所需的生命周期宿主（缺失会抛 ViewTreeLifecycleOwner not found） */
    private val composeHost = ServiceComposeHost()

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
    private var parentTopOnScreen = 0    // 展开区顶部屏幕绝对坐标
    private var dragIndex = -1           // 正在拖动的子按钮在 configs 中的索引
    private var expandedTopInRoot = 0f   // 展开区在根视图内的 Y 偏移（px），用于换算屏幕坐标

    // 当前按钮列表来自哪个脚本（从脚本管理加载后记录，保存时预填名称）
    private var currentScriptName: String? = null
    /** 最近一次保存/加载时的按钮快照，用于判断关闭前是否有未保存的改动 */
    private var savedSnapshot: List<QuickClickConfig>? = null
    private val lastClickAt = mutableMapOf<String, Long>()

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
    /** 当前正在显示的坐标拾取浮层 */
    private var pickOverlay: CoordinatePickOverlay? = null

    override fun onCreate() {
        super.onCreate()
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
                    onChildClick = { cfg -> onChildClicked(cfg) },
                    onChildTouch = { index, event -> onChildTouch(index, event) },
                    onExpandedPositioned = { top -> expandedTopInRoot = top },
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
        lp.gravity = Gravity.START or Gravity.TOP   // x/y 即屏幕绝对坐标
        lp.x = 0
        lp.y = (getScreenHeight() - dp(56)) / 2

        try {
            windowManager.addView(root, lp)
            rootAdded = true
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "addView 失败", e)
        }

        startForegroundIfNeeded()

        // 新功能启动即进入编辑态，用户无需双击主按钮即可直接添加按钮
        enterEditMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        // 通知 Activity 服务已停止
        sendBroadcast(Intent("com.hpu.autoclicker.SERVICE_STOPPED"))
        dragHandler.removeCallbacksAndMessages(null)
        matchHandler.removeCallbacksAndMessages(null)
        matchExecutor.shutdownNow()
        captureOverlay?.dismiss()
        captureOverlay = null
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
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX           // 屏幕绝对坐标，防多窗口偏移
                downRawY = event.rawY
                initWindowX = lp.x
                initWindowY = lp.y
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
                    lp.x = initWindowX + dx.toInt()
                    lp.y = (initWindowY + dy.toInt())
                        .coerceIn(0, (getScreenHeight() - root.height).coerceAtLeast(0))
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

    /** 拖动结束：吸附到最近的左/右屏幕边缘 */
    private fun snapToEdge() {
        val screenW = getScreenWidth()
        val viewW = root.width.coerceAtLeast(root.measuredWidth)
        lp.x = if (lp.x + viewW / 2 < screenW / 2) 0 else (screenW - viewW).coerceAtLeast(0)
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
        clampWindowY()
    }

    private fun collapse() {
        ui.isExpanded = false
        ui.isEditing = false   // 折叠即退出编辑态，编辑控件不常驻
    }

    private fun enterEditMode() {
        ui.isExpanded = true
        ui.isEditing = true
        lastMainTapAt = 0L   // 重置双击计时，避免刚进编辑态的下一次点击被误判为双击
        setMainAvailable(true)
        clampWindowY()
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
            clampWindowY()
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
        AlertDialog.Builder(this)
            .setTitle("关闭悬浮窗")
            .setMessage("当前有未保存的按钮，关闭前是否保存？")
            .setPositiveButton("保存并关闭") { _, _ -> saveAndClose() }
            .setNeutralButton("不保存关闭") { _, _ -> stopSelf() }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
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
        showInputDialog("保存脚本", "", "输入脚本名称", 12) { scriptName ->
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
     * 「确定」写回 SharedPreferences 供下次启动恢复，「取消」/返回键恢复打开前的值。
     */
    private fun showOpacityDialog() {
        try {
            val previousAlpha = root.alpha
            val state = OpacityDialogState().apply { alpha = previousAlpha }
            var confirmed = false
            val host = ServiceComposeHost()
            val content = ComposeView(this)
            host.attach(content)
            content.setContent {
                AutoClickerTheme {
                    OpacityDialogContent(
                        state = state,
                        onAlphaChange = { root.alpha = it },
                    )
                }
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("悬浮窗透明度")
                .setView(content)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create()
            dialog.window?.decorView?.let { host.attachWindowRoot(it) }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    confirmed = true
                    QuickClickStore.setWindowAlpha(this, state.alpha)
                    dialog.dismiss()
                }
            }
            dialog.setOnDismissListener {
                if (!confirmed) root.alpha = previousAlpha
                host.destroy()
            }
            showOverlayDialog(dialog, widthRatio = 0.85f)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开透明度弹框失败", e)
        }
    }

    // ==================== 子按钮点击编辑 / 长按拖动排序 ====================

    /**
     * 编辑态子按钮触摸（由 Compose pointerInteropFilter 回调）：
     * - 普通点击：打开「编辑快捷按钮」弹框（内含删除），与新建共用同一套表单；
     * - 长按后移动：拖动排序（长按原地松手不再触发编辑，避免与点击重复打开弹框）。
     * 未达长按时长的普通点击在非编辑态交给 [onChildClicked] 执行识别/点击。
     * 排序结果直接写回 ui.configs，因此不需要 syncConfigsFromViews()。
     */
    private fun onChildTouch(index: Int, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            dragStartRawX = event.rawX
            dragStartRawY = event.rawY
            dragMoved = false
            dragActive = false
            dragIndex = index
            ui.pressedChildIndex = index                                  // 按下反馈
            // 展开区顶部的屏幕绝对坐标 = 窗口 y + 展开区在根视图内的偏移
            parentTopOnScreen = lp.y + expandedTopInRoot.toInt()
            dragStartRunnable = Runnable {
                dragActive = true
                ui.draggingConfig = configs.getOrNull(index)               // 拖动中的按钮浮起
                ui.dragOffsetY = 0f
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
                ui.draggingConfig = null
                clampWindowY()                     // 落位后校正窗口位置（列表顺序已同步）
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

    /**
     * 拖动排序核心：子按钮跟随手指上下移动，中心越过相邻子按钮时交换位置；
     * 子按钮区间即为 configs 全部元素（UI 上它们始终位于 + 之下）
     */
    private fun performChildDrag(event: MotionEvent) {
        val idx = dragIndex
        if (idx < 0 || idx >= configs.size) return

        val dy = event.rawY - dragStartRawY
        ui.dragOffsetY = dy                    // 跟手位移

        val fingerY = event.rawY - parentTopOnScreen   // 手指相对展开区顶部的 Y
        val rowPx = dp(44)                     // 每行占位：40dp 高度 + 4dp 上间距
        val rowCenter = dp(24)                 // 行内容中心 = 行顶 + 4dp 间距 + 20dp 半高

        if (idx > 0 && fingerY < (idx - 1) * rowPx + rowCenter) {
            // 越过上一行中心：上移一格，并补偿位移保持视图跟手
            val item = configs.removeAt(idx)
            configs.add(idx - 1, item)
            dragIndex = idx - 1
            ui.dragOffsetY = dy + rowPx
            return
        }
        if (idx < configs.size - 1 && fingerY > (idx + 1) * rowPx + rowCenter) {
            // 越过下一行中心：下移一格，并补偿位移保持视图跟手
            val item = configs.removeAt(idx)
            configs.add(idx + 1, item)
            dragIndex = idx + 1
            ui.dragOffsetY = dy - rowPx
        }
    }

    // ==================== 识别 → 点击链路 ====================

    /** 子按钮点击：防抖 → 文本匹配 → dispatchGesture 点击 */
    private fun onChildClicked(cfg: QuickClickConfig) {
        if (ui.isEditing) return   // 编辑态不触发识别/点击

        // 防抖：间隔内不重复执行同一按钮（间隔可在“设置”中调整）
        val now = System.currentTimeMillis()
        val debounce = QuickClickStore.getDebounce(this)
        val last = lastClickAt[cfg.name] ?: 0L
        if (now - last < debounce) return
        lastClickAt[cfg.name] = now

        when (cfg.type) {
            QuickClickConfig.TYPE_TEXT -> executeTextClick(cfg)
            QuickClickConfig.TYPE_IMAGE -> executeImageClick(cfg)
            QuickClickConfig.TYPE_COORD -> executeCoordinateClick(cfg)
        }
    }

    /** 坐标点击：直接把配置里的屏幕绝对坐标交给无障碍服务点击 */
    private fun executeCoordinateClick(cfg: QuickClickConfig) {
        val svc = AutoClickerAccessibilityService.instance
        if (svc == null) {
            setMainAvailable(false)
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
            return
        }
        performClickAt(svc, cfg.x, cfg.y, cfg.autoCollapse)
    }

    /** 统一点击出口：duration 取全局设置，成功后按需自动收起悬浮窗 */
    private fun performClickAt(
        svc: AutoClickerAccessibilityService,
        x: Float,
        y: Float,
        autoCollapse: Boolean,
    ) {
        val duration = QuickClickStore.getClickDuration(this).toLong()
        svc.performClick(x, y, duration) { ok ->
            if (!ok) {
                Toast.makeText(this, "点击被系统取消（可能被 ROM 拦截）", Toast.LENGTH_SHORT).show()
            } else if (autoCollapse) {
                collapse()   // 点击后自动收起悬浮窗
            }
        }
    }

    /** 文本匹配 → 拿坐标 → 模拟点击（整条链路 try-catch，不崩溃） */
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
            performClickAt(svc, pt.x, pt.y, cfg.autoCollapse)
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
        hideSelfForScreenshot()
        hideConfigForCapture(dialog)
        // 淡出/隐藏需要一帧以上才真正反映到截图中，否则悬浮窗或弹框会残留在模板里
        matchHandler.postDelayed({
            if (destroyed) {
                capturePending = false
                return@postDelayed
            }
            svc.takeScreenshotAsync { screen ->
                capturePending = false
                if (screen == null) {
                    restoreAfterCapture(dialog)
                    Toast.makeText(this, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                    return@takeScreenshotAsync
                }
                val overlay = RegionCaptureOverlay(this, object : RegionCaptureOverlay.ResultListener {
                    override fun onConfirm(cropped: android.graphics.Bitmap, regionInScreen: android.graphics.Rect) {
                        val name = ImageStore.generateFileName("shot")
                        if (ImageStore.saveImage(this@QuickClickService, cropped, name)) {
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
        val template = ImageStore.loadImage(this, cfg.imageFileName)
        if (template == null) {
            Toast.makeText(this, "模板图片缺失，请重新编辑选择", Toast.LENGTH_SHORT).show()
            return
        }
        matchInProgress = true
        tryMatch(svc, cfg, template, parseRegionRect(cfg.imageRegionRect), attempt = 0)
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
                showSelfAfterScreenshot()
                if (screen == null) {
                    onMatchFailed(svc, cfg, template, region, attempt, "截屏失败，请重试")
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
            performClickAt(svc, result.point.x, result.point.y, cfg.autoCollapse)
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
     * 传入已有配置时是点击编辑：表单回填名称/目标文字/自动收起，确认后原地替换（保持顺序）。
     */
    private fun showConfigDialog(editTarget: QuickClickConfig? = null) {
        try {
            // === 原：inflate(dialog_quick_click_config.xml) + findViewById 取值 ===
            // === 现：ComposeView + QuickConfigDialogContent，取值改为读写表单状态 ===
            val form = QuickConfigFormState().apply {
                if (editTarget != null) {
                    name = editTarget.name
                    targetText = editTarget.text
                    autoCollapse = editTarget.autoCollapse
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
                }
            }
            // 弹框实例需在 Compose 内容之前声明：截图/取点回调要先隐藏弹框窗口
            var dialogRef: AlertDialog? = null
            var committed = false
            val host = ServiceComposeHost()
            val content = ComposeView(this)
            host.attach(content)
            content.setContent {
                AutoClickerTheme {
                    QuickConfigDialogContent(
                        state = form,
                        nameFilter = ::limitQuickName,   // 名称限长：含中文 ≤2 字、纯英文/数字 ≤3 字符
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

            val isEdit = editTarget != null
            val builder = AlertDialog.Builder(this)
                .setTitle(if (isEdit) "编辑快捷按钮" else "新建快捷按钮")
                .setView(content)
                .setPositiveButton(if (isEdit) "保存" else "新建", null)
                .setNegativeButton("取消", null)
            // 仅编辑弹框提供「删除」：点击后直接移除该子按钮并同步列表
            if (editTarget != null) {
                builder.setNeutralButton("删除", null)
            }
            val dialog = builder.create()
            dialogRef = dialog

            // 关键：把宿主 Owner 也挂到对话框窗口的 DecorView 上，且必须在 show() 之前。
            // 对话框内 ComposeView 的窗口 Recomposer 是从 AlertDialogLayout 往上找 Owner 的，
            // Service 场景没人给 DecorView 挂 Owner，漏掉这步会在弹框时崩。
            dialog.window?.decorView?.let { host.attachWindowRoot(it) }

            dialog.setOnShowListener {
                // 手动接管确认按钮：校验不通过时不关闭弹窗
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = limitQuickName(
                        form.name.trim().ifEmpty {
                            // 编辑时清空名称 → 保留原名；新建时清空 → 「按钮N」
                            editTarget?.name ?: "按钮${configs.size + 1}"
                        }
                    )
                    val newConfig = when (form.type) {
                        QuickClickConfig.TYPE_IMAGE -> {
                            if (form.imageFileName.isEmpty()) {
                                Toast.makeText(this, "请先截图或从相册选择模板图片", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            QuickClickConfig(
                                name = name,
                                type = QuickClickConfig.TYPE_IMAGE,
                                autoCollapse = form.autoCollapse,
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
                                return@setOnClickListener
                            }
                            val screenW = getScreenWidth()
                            val screenH = getScreenHeight()
                            if (x !in 0 until screenW || y !in 0 until screenH) {
                                Toast.makeText(
                                    this,
                                    "坐标需在屏幕范围内（0~${screenW - 1} / 0~${screenH - 1}）",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@setOnClickListener
                            }
                            QuickClickConfig(
                                name = name,
                                type = QuickClickConfig.TYPE_COORD,
                                x = x.toFloat(),
                                y = y.toFloat(),
                                autoCollapse = form.autoCollapse,
                            )
                        }
                        else -> {
                            val text = form.targetText.trim()
                            if (text.isEmpty()) {
                                Toast.makeText(this, "请输入目标文字", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            QuickClickConfig(
                                name = name,
                                type = QuickClickConfig.TYPE_TEXT,
                                text = text,
                                autoCollapse = form.autoCollapse,
                            )
                        }
                    }

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
                        clampWindowY()
                        Toast.makeText(this, "已新建「$name」", Toast.LENGTH_SHORT).show()
                    }
                    committed = true
                    dialog.dismiss()
                }
                // 「删除」：立即从列表移除该按钮（mutableStateListOf 自动同步悬浮窗），无需再点保存
                if (editTarget != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        // 二次确认（覆盖在编辑弹框之上，与脚本删除确认同一路径）：
                        // 确认 → 移除按钮并连同编辑弹框一起关闭；取消 → 仅关确认框，编辑弹框保留
                        AlertDialog.Builder(this)
                            .setTitle("删除按钮")
                            .setMessage("确定删除按钮「${editTarget.name}」？")
                            .setPositiveButton("删除") { _, _ ->
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
                                committed = true
                                clampWindowY()
                                Toast.makeText(this, "已删除「${editTarget.name}」", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                            .setNegativeButton("取消", null)
                            .create()
                            .also { showOverlayDialog(it) }
                    }
                }
            }
            dialog.setOnDismissListener {
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
            }
            // Service 弹窗必须挂到悬浮窗层，否则 BadTokenException（与旧功能处理一致）
            showOverlayDialog(dialog)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开配置弹框失败", e)
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
            // === 现：ComposeView + ScriptManagerDialogContent，列表由状态驱动 ===
            val state = ScriptManagerState()
            val host = ServiceComposeHost()
            val content = ComposeView(this)
            host.attach(content)

            val dialog = AlertDialog.Builder(this)
                .setTitle("脚本管理")
                .setView(content)
                .setNegativeButton("关闭", null)
                .create()

            // 把宿主 Owner 也挂到对话框窗口的 DecorView 上（必须在 show() 之前，原因见 ServiceComposeHost.attachWindowRoot）
            dialog.window?.decorView?.let { host.attachWindowRoot(it) }

            // 重新读取脚本列表 → 触发 Compose 刷新（等价原来的 removeAllViews + 逐行 addView）
            val refresh: () -> Unit = {
                state.scripts = QuickClickStore.loadScripts(this)
            }

            content.setContent {
                AutoClickerTheme {
                    ScriptManagerDialogContent(
                        state = state,
                        onSaveCurrent = { saveCurrentScript(refresh) },
                        onLoadScript = { script -> handleLoadScript(script, dialog, refresh) },
                        onRenameScript = { script -> handleRenameScript(script, refresh) },
                        onDeleteScript = { script -> handleDeleteScript(script, refresh) },
                    )
                }
            }

            dialog.setOnDismissListener { host.destroy() }
            refresh()
            showOverlayDialog(dialog)
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
        showInputDialog("保存脚本", currentScriptName ?: "", "输入脚本名称", 12) { scriptName ->
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
    private fun handleLoadScript(script: QuickScript, dialog: AlertDialog, onChanged: () -> Unit) {
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
        showInputDialog("重命名脚本", script.name, "输入新名称", 12) { newName ->
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
        AlertDialog.Builder(this)
            .setTitle("删除脚本")
            .setMessage("确定删除脚本「${script.name}」？")
            .setPositiveButton("删除") { _, _ ->
                QuickClickStore.deleteScript(this, script.name)
                // 当前列表来自被删脚本：此后视为未保存，关闭悬浮窗时会重新提示保存
                if (currentScriptName == script.name) {
                    currentScriptName = null
                    savedSnapshot = null
                }
                onChanged()
                Toast.makeText(this, "已删除脚本「${script.name}」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
    }

    /** 载入脚本：替换悬浮框上的按钮，并关闭脚本管理弹框 */
    private fun loadScript(script: QuickScript, dialog: AlertDialog) {
        configs.clear()
        configs.addAll(script.configs)
        currentScriptName = script.name
        savedSnapshot = configs.toList()
        clampWindowY()
        dialog.dismiss()
        Toast.makeText(this, "已加载脚本「${script.name}」", Toast.LENGTH_SHORT).show()
    }

    /** 悬浮框上已有按钮时，加载脚本前确认覆盖 */
    private fun confirmReplaceByScript(script: QuickScript, dialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("加载脚本")
            .setMessage("当前按键节点未保存，加载脚本「${script.name}」将覆盖它们，是否覆盖？")
            .setPositiveButton("覆盖") { _, _ -> loadScript(script, dialog) }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
    }

    /** 同名脚本覆盖确认 */
    private fun confirmOverwrite(scriptName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("覆盖脚本")
            .setMessage("脚本「$scriptName」已存在，是否覆盖？")
            .setPositiveButton("覆盖") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
    }

    /**
     * Service 内弹窗统一挂到悬浮窗层（否则 BadTokenException，与旧功能处理一致），
     * 并统一设置宽度。
     *
     * 这里还必须清掉系统给 TYPE_APPLICATION_OVERLAY 弹框附加的 FLAG_ALT_FOCUSABLE_IM：
     * 该 flag 对「可聚焦窗口」的含义是「本窗口不与输入法交互」——输入法会被压到本窗口
     * 下方、本窗口永远不会成为输入法的输入目标（InputMethodManager 的 mServedView 一直
     * 停在上一个 Activity 的 DecorView 上），表现为输入框有光标、能收到文本，但软键盘不弹。
     * 因此凡带输入框的弹框（新建按钮、保存/重命名脚本，以及连点器的设置弹框）都要走这里。
     */
    private fun showOverlayDialog(dialog: AlertDialog, widthRatio: Float = 0.9f) {
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setLayout(
            (getScreenWidth() * widthRatio).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 单行输入弹框（保存脚本 / 重命名共用）。
     * 此处仍用原生 EditText：该弹框不涉及 XML 布局文件，且原生输入框在该场景下更稳。
     */
    private fun showInputDialog(
        title: String,
        initial: String,
        hint: String,
        maxLen: Int,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(initial)
            setHint(hint)
            isSingleLine = true
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onConfirm(value)
                dialog.dismiss()
            }
        }
        showOverlayDialog(dialog)
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

    /** 展开区在主按钮下方，展开/编辑后校正窗口位置避免超出屏幕底部 */
    private fun clampWindowY() {
        // Compose 内容尺寸变化后需等一次布局完成才能拿到新高度，故 post 到下一帧读取
        root.post {
            if (!rootAdded) return@post
            val maxY = (getScreenHeight() - root.height).coerceAtLeast(0)
            val newY = lp.y.coerceIn(0, maxY)
            if (newY != lp.y) {
                lp.y = newY
                try {
                    windowManager.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    android.util.Log.e("QuickClick", "clampWindowY 失败", e)
                }
            }
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
