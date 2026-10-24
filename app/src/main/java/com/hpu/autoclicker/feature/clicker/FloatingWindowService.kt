package com.hpu.autoclicker.feature.clicker

import android.accessibilityservice.AccessibilityService
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.components.AppDialogActions
import com.hpu.autoclicker.core.components.AppDialogPanel
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.ui.ServiceDialogs
import com.hpu.autoclicker.core.ui.stripPlatformPanel
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    // WindowManager 用于管理悬浮窗的显示、位置和参数
    private lateinit var windowManager: WindowManager

    // 悬浮窗的根布局（由原 activity_floating_window_service.xml 迁移为 Compose 内容）
    private lateinit var rootView: ComposeView

    /** ComposeView 在 Service 中所需的生命周期宿主（缺失会抛 ViewTreeLifecycleOwner not found） */
    private val composeHost = ServiceComposeHost()

    /** 悬浮窗 UI 状态：展开/运行/隐藏，替代原来 findViewById + isEnabled/setImageResource 的手工刷新 */
    private val ui = FloatingServiceUiState()

    // 主按钮 / 退出按钮在「窗口内」的位置（屏幕坐标 = 窗口内坐标 + layoutParams 偏移）
    // 用于运行期判断触摸是否落在允许的按钮上（等价原 findViewById + getLocationOnScreen）
    private var mainBtnWindowRect = Rect()
    private var exitBtnWindowRect = Rect()

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

    // 当前吸附边缘标记：true = 左侧，false = 右侧
    private var isDockedLeft = true


    private var pointCount = 0                   // 当前编号计数

    companion object {
        val clickPoints = mutableListOf<ClickPoint>()   // 静态点击按钮列表
    }

    // 点击点的一些尺寸参数
    private val pointSize = 60                   // 圆形直径 dp（实际会转 px）
//    private val pointBackground = "#FF5722"      // 橙色背景

    private var pointSizePx = 0

    // 运行状态（读写统一落到 ui 状态，UI 自动刷新启动/停止图标与按钮可用性）
    private var isRunning: Boolean
        get() = ui.isRunning
        set(value) { ui.isRunning = value }
    private val runHandler = Handler(Looper.getMainLooper())

    private var pairCount = 0
    // 用 pairId 映射管道视图及其参数
    private val pipeMap = mutableMapOf<Int, Pair<PipeLineView, WindowManager.LayoutParams>>()

    private var pointSettingsDialog: android.app.AlertDialog? = null

    private var settingsDialog: AlertDialog? = null
    private var maxLoopCount = 0   // 0或负数表示无限循环，>0 为固定次数

    // 隐藏状态（读写统一落到 ui 状态，UI 自动刷新隐藏/显示图标与按钮可用性）
    private var isHidden: Boolean
        get() = ui.isHidden
        set(value) { ui.isHidden = value }

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingWindow", "Service onCreate")

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
                    onHide = { toggleHide() },
                    onExit = { stopSelf() },
                    // 上报按钮在窗口内的位置，供运行期「触摸是否落在允许按钮上」的判定使用
                    onMainBounds = { rect -> mainBtnWindowRect = rect },
                    onExitBounds = { rect -> exitBtnWindowRect = rect },
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
        val h = (56 * resources.displayMetrics.density).toInt()
        Log.d("FloatingWindow", "初始高度估算：高=$h")

        // 设置初始位置：左侧吸附，垂直居中
        layoutParams.gravity = Gravity.START or Gravity.TOP  // 以屏幕左上角为原点
        layoutParams.x = 0
        layoutParams.y = (getScreenHeight() - h) / 2

        // 将悬浮窗添加到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
            Log.d("FloatingWindow", "addView 成功")
        } catch (e: Exception) {
            Log.e("FloatingWindow", "addView 失败", e)
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
                clampAllWindowsToScreen()
            }
        }
    }
    private fun clampAllWindowsToScreen() {
        val newW = getScreenWidth()
        val newH = getScreenHeight()

        // 主悬浮窗
        layoutParams.x = layoutParams.x.coerceIn(0, newW - rootView.width)
        layoutParams.y = layoutParams.y.coerceIn(0, newH - rootView.height)
        windowManager.updateViewLayout(rootView, layoutParams)

        // 所有点击点
        for (point in clickPoints) {
            point.params?.let { params ->
                params.x = params.x.coerceIn(0, newW - point.view.width)
                params.y = params.y.coerceIn(0, newH - point.view.height)
                windowManager.updateViewLayout(point.view, params)
                point.x = params.x.toFloat()
                point.y = params.y.toFloat()
            }
            // 如果是滑动对，更新管道
            updatePipeLine(point.pairId)
        }

        // 管道使用全屏透明绘制层，屏幕尺寸变化时只需要更新绘制层尺寸
        for ((pairId, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            params.x = 0
            params.y = 0
            params.width = newW
            params.height = newH
            windowManager.updateViewLayout(lineView, params)
            updatePipeLine(pairId)
        }
    }

    override fun onDestroy() {
        // 发送广播通知 Activity 服务已停止
        val broadcastIntent = Intent("com.hpu.autoclicker.SERVICE_STOPPED")
        sendBroadcast(broadcastIntent)
        stopRunning()
        isRunning = false
        runHandler.removeCallbacksAndMessages(null)
        for (point in clickPoints) {
            windowManager.removeView(point.view)
        }
        clickPoints.clear()
        if (::rootView.isInitialized) {
            windowManager.removeView(rootView)
        }
        pipeMap.values.forEach { (lineView, _) -> windowManager.removeView(lineView) }
        pipeMap.clear()
        pointSettingsDialog?.dismiss()
        pointSettingsDialog = null

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
                    // 更新窗口位置
                    layoutParams.x = initialWindowX + dx.toInt()
                    layoutParams.y = initialWindowY + dy.toInt()
                    // 垂直边界限制，防止移出屏幕（顶部 0 到底部 screenHeight - 视图高度）
                    layoutParams.y = layoutParams.y.coerceIn(0, getScreenHeight() - rootView.height)
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
            // 折叠后退出按钮不再显示，其位置信息作废，避免运行期触摸判定误命中
            exitBtnWindowRect = Rect()
        }

        // 视图大小改变后，重新测量并更新布局。
        // Compose 内容尺寸变化会触发一次布局，故用 doOnLayout 等布局完成后再校正窗口位置/尺寸。
        rootView.doOnLayout {
            // 通知窗口管理器更新视图（尺寸可能变化）
            windowManager.updateViewLayout(rootView, layoutParams)

            // 保持吸附边缘不变
            if (isDockedLeft) {
                layoutParams.x = 0
            } else {
                layoutParams.x = getScreenWidth() - rootView.width
            }

            // 再次限制垂直范围，防止底部超出屏幕
            layoutParams.y = layoutParams.y.coerceIn(0, getScreenHeight() - rootView.height)
            windowManager.updateViewLayout(rootView, layoutParams)
        }
    }

    /**
     * 根据当前悬浮窗中心点位置，将其吸附到屏幕左侧或右侧边缘
     */
    private fun snapToEdge() {
        rootView.post {
            val viewWidth = rootView.width
            val centerX = layoutParams.x + viewWidth / 2  // 窗口中心点的 X 坐标

            if (centerX < getScreenWidth() / 2) {
                // 中心点偏左，吸附左侧
                layoutParams.x = 0
                isDockedLeft = true
            } else {
                // 中心点偏右，吸附右侧
                layoutParams.x = getScreenWidth() - viewWidth
                isDockedLeft = false
            }

            // 确保垂直方向在屏幕内
            layoutParams.y = layoutParams.y.coerceIn(0, getScreenHeight() - rootView.height)
            windowManager.updateViewLayout(rootView, layoutParams)
        }
    }

    /**
     * 开启前台服务（Android 8.0 以上必须）
     * 需要声明权限：android.permission.FOREGROUND_SERVICE
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

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addClickPoint(pairId: Int,isStart: Boolean) : ClickPoint {
        pointCount++
        val id = pointCount
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
                pointCount--
            }
        }
        // 创建带编号的圆形按钮
        val pointView = createCircleButton(label, pairId, isStart) // 文字显示 label

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
            pointsToRemove.forEach { windowManager.removeView(it.view) }
            clickPoints.removeAll(pointsToRemove)

            // 移除管道
            pipeMap[pairId]?.let { (lineView, _) ->
                windowManager.removeView(lineView)
            }
            pipeMap.remove(pairId)
            Toast.makeText(this, "已删除滑动对", Toast.LENGTH_SHORT).show()
        } else {
            // 普通点，直接删除
            clickPoints.remove(last)
            windowManager.removeView(last.view)
            Toast.makeText(this, "已删除点击点 ${last.id}", Toast.LENGTH_SHORT).show()
        }
        // 删除点击节点后，总节点数减一
        pointCount--
    }

    private fun setupPointDrag(view: View, params: WindowManager.LayoutParams, point: ClickPoint) {
        val longPressHandler = Handler(Looper.getMainLooper())
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
                        params.x = (downParamX + dx).toInt().coerceIn(0, getScreenWidth() - view.width)
                        params.y = (downParamY + dy).toInt().coerceIn(0, getScreenHeight() - view.height)
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
        pairCount = pointCount + 1
        val pairId = pairCount

        // 1. 创建管道视图和参数
        val lineView = PipeLineView(this)
        val lineParams = createPipeLineLayoutParams()
        // 存入 map
        pipeMap[pairId] = Pair(lineView, lineParams)
        // 2. 先添加管道到窗口（使其位于下层）
        windowManager.addView(lineView, lineParams)

        // 3. 添加起点和终点节点（后添加的节点会覆盖在管道上方）
        val startPoint = addClickPoint(pairId, true)
        val endPoint = addClickPoint(pairId, false)

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
        settingsDialog = ServiceDialogs.show(
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
                            settingsDialog?.dismiss()
                        },
                        cancelText = "取消",
                        onCancel = { settingsDialog?.dismiss() },
                    )
                },
            ) {
                SettingsDialogContent(
                    state = form,
                    onSavePoints = { savePointsToFile(); settingsDialog?.dismiss() },
                    onLoadPoints = { loadPointsFromFile(); settingsDialog?.dismiss() },
                )
            }
        }
    }

    private fun createCircleButton(label: String, pairId: Int, isStart: Boolean): TextView {
        val isSwipePoint = pairId != -1
        val sizeDp = 40
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val colors = when {
            !isSwipePoint -> intArrayOf(Color.parseColor("#FF8A50"), Color.parseColor("#FF5722"))
            isStart -> intArrayOf(Color.parseColor("#28D17C"), Color.parseColor("#00A86B"))
            else -> intArrayOf(Color.parseColor("#7C6CFF"), Color.parseColor("#4C5CFF"))
        }
        val strokeColor = when {
            !isSwipePoint -> Color.parseColor("#FFFFF3")
            isStart -> Color.parseColor("#D8FFE9")
            else -> Color.parseColor("#E3E0FF")
        }

        return TextView(this).apply {
            text = label
            textSize = if (isSwipePoint) 14f else 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setShadowLayer(2f, 0f, 1f, Color.parseColor("#66000000"))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                shape = GradientDrawable.OVAL
                setStroke((2 * resources.displayMetrics.density).toInt(), strokeColor)
            }
            elevation = 10f * resources.displayMetrics.density
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

    private fun performPointClick(
        service: AutoClickerAccessibilityService,
        point: ClickPoint,
        x: Float,
        y: Float,
        duration: Long,
        onFinished: () -> Unit
    ) {
        val clickDuration = duration.coerceAtLeast(50L)

        // 首次创建悬浮窗后，点击点窗口可能仍短暂参与命中测试。
        // 点击前隐藏当前点并再次刷新不可触摸标记，避免首个坐标手势落到悬浮点自身。
        point.view.visibility = View.INVISIBLE
        point.params?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(point.view, params)
        }

        runHandler.postDelayed({
            if (!isRunning) {
                point.view.visibility = View.VISIBLE
                onFinished()
                return@postDelayed
            }

            service.performClick(x, y, clickDuration)

            runHandler.postDelayed({
                point.view.visibility = if (isHidden) View.GONE else View.VISIBLE
                onFinished()
            }, clickDuration + 80)
        }, 80)
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
        // 悬浮窗半透明
        rootView.alpha = 0.5f
        // 将其他按钮置灰（添加、减少、设置、滑动、隐藏等）与启动图标切换为“停止”，
        // 原为 setButtonsEnabled(false) + setImageResource(ic_stop)，现由 ui.isRunning 派生，Compose 自动刷新

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

        // 所有点击点圆形按钮半透明可见，让手势穿透到应用层
        for (point in clickPoints) {
            point.view.alpha = 0.3f                     // 半透明可见
            point.params?.let { params ->
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(point.view, params)
            }
        }

        // 管道也必须穿透，否则滑动路径容易被悬浮管道窗口拦截
        for ((_, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            lineView.alpha = 0.3f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(lineView, params)
        }

        // 按编号排序执行
        val sortedPoints = clickPoints.sortedBy { it.id }
        var loopDone = 0   // 已经完成的轮数
        fun performRound(currentIndex: Int) {
            if (!isRunning) return
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

            val targetX = centerX + randomX
            val targetY = centerY + randomY

            // 根据类型执行点击（目前主要用点击）
            Log.d("startRunning point.type", point.type)
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
                        Log.d("ClickDebug", "点击: x=$targetX, y=$targetY, duration=${point.touchDuration}, service=${service != null}")
                        performPointClick(service, point, targetX, targetY, point.touchDuration) {
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

                            // 隐藏起点、终点和管道，避免悬浮窗遮挡滑动路径
                            startView.visibility = View.INVISIBLE
                            endView.visibility = View.INVISIBLE
                            pipeMap[point.pairId]?.first?.visibility = View.INVISIBLE

                            runHandler.postDelayed({
                                // 调用优化后的 performSwipe
                                service.performSwipe(startCx, startCy, endCx, endCy, point.touchDuration)

                                // 手势执行后恢复视图
                                runHandler.postDelayed({
                                    startView.visibility = if (isHidden) View.GONE else View.VISIBLE
                                    endView.visibility = if (isHidden) View.GONE else View.VISIBLE
                                    pipeMap[point.pairId]?.first?.visibility = if (isHidden) View.GONE else View.VISIBLE
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
                        // 双击：两个快速点击
                        point.view.visibility = View.INVISIBLE
                        point.params?.let { params ->
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            windowManager.updateViewLayout(point.view, params)
                        }
                        runHandler.postDelayed({
                            service.performClick(targetX, targetY, 50)
                            runHandler.postDelayed({
                                service.performClick(targetX, targetY, 50)
                                remaining--
                                runHandler.postDelayed({
                                    point.view.visibility = if (isHidden) View.GONE else View.VISIBLE
                                    doDouble()
                                }, point.touchDuration + 50)
                            }, 100)  // 双击间隔100ms
                        }, 80)
                    }
                    doDouble()
                }
                "返回键" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                "主页键" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                }
                "打开通知栏" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
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
        runHandler.removeCallbacksAndMessages(null)

        // 恢复主悬浮窗
//        rootView.visibility = View.VISIBLE
//        windowManager.updateViewLayout(rootView, layoutParams)
        // 恢复透明度
        rootView.alpha = 1.0f

        // 恢复其他按钮（原 setOtherButtonsEnabled(true)，现由 ui.isRunning=false 派生）
        // 启动图标恢复为“播放”同样由 ui.isRunning 派生自动完成

        // 移除空白区域触摸监听
        rootView.setOnTouchListener(null)

        // 恢复所有点击点视图
        for (point in clickPoints) {
            point.view.alpha = 1.0f                     // 恢复完全不透明
            point.view.visibility = if (isHidden) View.GONE else View.VISIBLE
            point.params?.let { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(point.view, params)
            }
        }

        // 恢复管道显示效果，但保持不可触摸，符合“仅作视觉提示”的需求
        for ((_, pipePair) in pipeMap) {
            val (lineView, params) = pipePair
            lineView.alpha = 1.0f
            lineView.visibility = if (isHidden) View.GONE else View.VISIBLE
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(lineView, params)
        }
    }

    /**
     * 判断触摸点是否落在允许的按钮上（主按钮、退出按钮）。
     * 原实现用 findViewById + getLocationOnScreen；现按钮位置由 Compose 的
     * onGloballyPositioned 上报「窗口内坐标」，加上窗口偏移即屏幕绝对坐标。
     */
    private fun isTouchOnAllowedButton(rawX: Int, rawY: Int): Boolean {
        // 主按钮
        val main = Rect(mainBtnWindowRect).apply { offset(layoutParams.x, layoutParams.y) }
        if (main.contains(rawX, rawY)) return true
        // 退出按钮
        val exit = Rect(exitBtnWindowRect).apply { offset(layoutParams.x, layoutParams.y) }
        return exit.contains(rawX, rawY)
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
        point.delay = form.delay.toLongOrNull() ?: 0
        point.touchDuration = form.touchDuration.toLongOrNull() ?: 300
        point.repeatCount = form.repeatCount.toIntOrNull() ?: 1
        point.randomDistance = form.randomDistance.toIntOrNull() ?: 0
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

    private fun savePointsToFile() {
        try {
            // 按 id 升序排序，若 id 相同则起点在前
            val sortedPoints = clickPoints.sortedWith(
                compareBy<ClickPoint> { it.id }
                    .thenBy { if (it.isStart) 0 else 1 }  // 滑动对中起点优先
            )
            val jsonArray = JSONArray()
            for (p in sortedPoints) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                    put("type", p.type)
                    put("name", p.name)
                    put("delay", p.delay)
                    put("touchDuration", p.touchDuration)
                    put("repeatCount", p.repeatCount)
                    put("randomDistance", p.randomDistance)
                    put("clickPosition", p.clickPosition)
                    put("pairId", p.pairId)
                    put("isStart", p.isStart)
                    put("endX", p.endX.toDouble())
                    put("endY", p.endY.toDouble())
                }
                jsonArray.put(obj)
            }
            val file = File(filesDir, "click_points.json")
            file.writeText(jsonArray.toString())
            Toast.makeText(this, "当前点击位置已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPointsFromFile() {
        try {
            val file = File(filesDir, "click_points.json")
            if (!file.exists()) {
                Toast.makeText(this, "没有已保存的位置", Toast.LENGTH_SHORT).show()
                return
            }
            val text = file.readText()
            val jsonArray = JSONArray(text)

            // 1. 停止连点（如果正在运行）
            stopRunning()

            // 2. 移除所有点击点视图
            for (p in clickPoints) {
                windowManager.removeView(p.view)
            }

            // 3. 清除点击点列表
            clickPoints.clear()

            // 4. 移除所有管道视图
            for ((_, pipePair) in pipeMap) {
                windowManager.removeView(pipePair.first)
            }

            // 5. 清除管道映射
            pipeMap.clear()
            pointCount = 0
            // 2. 临时禁用互斥逻辑，直接加载
            // 3. 重新创建点（按类型区分）
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getInt("id")
                val xAxis = obj.getDouble("x").toFloat()
                val yAxis = obj.getDouble("y").toFloat()
                val type = obj.getString("type")
                val name = obj.optString("name", "点 $id")
                val delay = obj.optLong("delay", 0)
                val touchDuration = obj.optLong("touchDuration", 300)
                val repeatCount = obj.optInt("repeatCount", 1)
                val randomDistance = obj.optInt("randomDistance", 0)
                val clickPosition = obj.optString("clickPosition", "中间")
                val pairId = obj.optInt("pairId", -1)
                val isStart = obj.optBoolean("isStart", false)
                val endX = obj.optDouble("endX", 0.0).toFloat()
                val endY = obj.optDouble("endY", 0.0).toFloat()
                Log.d("pointCount", pointCount.toString())
                if (pairId != -1) {
                    val pairId1 = if (isStart ) pointCount + 1 else pointCount
                    // 滑动点：需要成对重建，但加载时按顺序可能先读到起点或终点，采用特殊处理
                    // 添加起点和终点节点（后添加的节点会覆盖在管道上方）
                    if (isStart){
                        // 创建管道视图和参数
                        val lineView = PipeLineView(this)
                        val lineParams = createPipeLineLayoutParams()
                        // 存入 map
                        pipeMap[pairId1] = Pair(lineView, lineParams)
                        // 2. 先添加管道到窗口（使其位于下层）
                        windowManager.addView(lineView, lineParams)
                        // 滑动开始节点
                        val point = addClickPoint(pairId1, true)
                        addLoadedClickPoint(point, xAxis, yAxis, type, endX, endY, delay, touchDuration, repeatCount,randomDistance,isStart)
                        movePoint(point,xAxis,yAxis,false)
                    }else{
                        // 滑动结束节点
                        val point = addClickPoint(pairId1, false)
                        addLoadedClickPoint(point, xAxis, yAxis, type, endX, endY, delay, touchDuration, repeatCount,randomDistance,isStart)
                        movePoint(point,xAxis,yAxis,true)
                    }

                } else {
                    // 普通点击点
                    val point = addClickPoint(-1,true)//isStart:随意
                    addLoadedClickPoint(point, xAxis, yAxis, type, endX, endY, delay, touchDuration, repeatCount,randomDistance,isStart)
                    movePoint(point,xAxis,yAxis,false)
                }
            }
            Toast.makeText(this, "文件已加载完成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "加载失败，文件可能损坏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addLoadedClickPoint(point: ClickPoint, startX: Float, startY: Float,type: String,
                                    endX: Float, endY: Float,
                                    delay: Long, touchDuration: Long, repeatCount: Int,
                                    randomDistance: Int,isStart: Boolean): ClickPoint{
        point.x = startX
        point.y = startY
        point.type = type
        point.isStart = isStart
        point.delay = delay
        point.touchDuration = touchDuration
        point.repeatCount = repeatCount
        point.randomDistance = randomDistance
        return point
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

    private fun toggleHide() {
        if (isRunning) {
            Toast.makeText(this, "请先停止连点", Toast.LENGTH_SHORT).show()
            return
        }
        isHidden = !isHidden
        if (isHidden) {
            // 进入隐藏模式
            rootView.alpha = 0.5f
            for (point in clickPoints) { point.view.visibility = View.GONE }
            for ((_, pipePair) in pipeMap) { pipePair.first.visibility = View.GONE }
            // 隐藏按钮图标切换为“显示”、其余按钮置灰，均由 ui.isHidden 派生自动刷新
            Toast.makeText(this, "已隐藏点击点", Toast.LENGTH_SHORT).show()
        } else {
            // 退出隐藏模式
            rootView.alpha = 1.0f
            for (point in clickPoints) { point.view.visibility = View.VISIBLE }
            for ((_, pipePair) in pipeMap) { pipePair.first.visibility = View.VISIBLE }
            // 图标与按钮可用性同样由 ui.isHidden 派生自动恢复
            Toast.makeText(this, "已显示点击点", Toast.LENGTH_SHORT).show()
        }
    }
}


// 点击点数据类
data class ClickPoint(
    val id: Int,  // 并无唯一
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
    var isStart: Boolean = false // 滑动对中的起点 true，终点 false
)
