package com.hpu.autoclicker

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.Dialog
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
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
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

    // 悬浮窗的根布局（包含主按钮和展开的操作按钮区域）
    private lateinit var rootView: LinearLayout

    // 可展开/折叠的操作按钮区域
    private lateinit var expandedControls: LinearLayout

    // 主悬浮按钮（可拖动、可点击展开）
    private lateinit var btnMain: ImageView

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

    // 运行状态
    private var isRunning = false
    private val runHandler = Handler(Looper.getMainLooper())

    private var pairCount = 0
    // 用 pairId 映射管道视图及其参数
    private val pipeMap = mutableMapOf<Int, Pair<PipeLineView, WindowManager.LayoutParams>>()

    private var pointSettingsDialog: android.app.AlertDialog? = null

    private var settingsDialog: AlertDialog? = null
    private var maxLoopCount = 0   // 0或负数表示无限循环，>0 为固定次数

    private var isHidden = false   // 隐藏状态

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingWindow", "Service onCreate")

        // 获取 WindowManager 系统服务
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 加载自定义的悬浮窗布局（注意：之前的代码中文件名可能为 activity_floating_window_service，
        // 务必确认布局文件实际名称与 R.layout.xxx 一致，推荐改为 R.layout.floating_window_layout）
        rootView = LayoutInflater.from(this)
            .inflate(R.layout.activity_floating_window_service, null) as LinearLayout

        // 获取布局中的控件
        expandedControls = rootView.findViewById(R.id.expandedControls)
        btnMain = rootView.findViewById(R.id.btnMain)

        // 设置触摸监听和按钮点击事件
        setupTouchAndClick()

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

        // 提前测量布局，以获得正确的宽高（避免后续 post 测量为 0）
        rootView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = rootView.measuredWidth
        val h = rootView.measuredHeight
        Log.d("FloatingWindow", "布局测量完成：宽=$w, 高=$h")

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
     * 设置主按钮的触摸监听（按住拖动 + 点击展开）和其他按钮的点击事件
     */
    private fun setupTouchAndClick() {
        // 主按钮触摸监听：按住拖动 + 点击展开
        btnMain.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录按下时的位置和窗口坐标，准备拖动
                    initialX = event.rawX
                    initialY = event.rawY
                    initialWindowX = layoutParams.x
                    initialWindowY = layoutParams.y
                    isDragging = false   // 重置拖动状态
                    true  // 必须返回 true，否则后续事件收不到
                }

                MotionEvent.ACTION_MOVE -> {
                    // 计算手指移动的距离
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY

                    // 移动超过 10 像素时，进入拖动模式
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
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

        // 示例：退出按钮的点击事件，点击后关闭悬浮窗服务
        rootView.findViewById<ImageView>(R.id.btnExit).setOnClickListener {
            stopSelf()
        }

        //绑定添加按钮的点击事件
        rootView.findViewById<ImageView>(R.id.btnAdd).setOnClickListener {
            addClickPoint(-1,true)//isStart:随意
        }
        // 绑定减少按钮：删除最后一个添加的点击点
        rootView.findViewById<ImageView>(R.id.btnReduce).setOnClickListener {
            removeLastClickPoint()
        }
        // 绑定启动/停止按钮
        rootView.findViewById<ImageView>(R.id.btnStart).setOnClickListener {
            if (isRunning) {
                stopRunning()
            } else {
                startRunning()
            }
        }
        // 绑定滑动按钮
        rootView.findViewById<ImageView>(R.id.btnSlide).setOnClickListener {
            addSwipePair()
        }
        // 绑定设置按钮
        rootView.findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            showGlobalSettingsDialog()
        }
        // 绑定隐藏按钮
        rootView.findViewById<ImageView>(R.id.btnHide).setOnClickListener {
            toggleHide()
        }
        // 你可以在这里继续绑定其他按钮（如 btnStart、btnAdd 等）的点击功能
    }

    /**
     * 切换操作栏的展开/折叠状态，并重新校正悬浮窗位置确保不超出屏幕
     */
    private fun toggleExpanded() {
        // 切换可见性
        if (expandedControls.visibility == View.GONE) {
            expandedControls.visibility = View.VISIBLE
        } else {
            expandedControls.visibility = View.GONE
        }

        // 视图大小改变后，重新测量并更新布局
        rootView.post {
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
        val pointView = createCircleButton(label) // 文字显示 label

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
        val (lineView, lineParams) = pipeMap[pairId] ?: return

        // 两个圆形节点的中心坐标
        val startCx = startPoint.x + startPoint.view.width / 2f
        val startCy = startPoint.y + startPoint.view.height / 2f
        val endCx = endPoint.x + endPoint.view.width / 2f
        val endCy = endPoint.y + endPoint.view.height / 2f

        // 线与画笔宽度相关
        val lineWidth = 12f   // 必须与 PipeLineView 中的 strokeWidth 一致
        val halfWidth = lineWidth / 2f

        // 计算窗口最小矩形（包含直线和线宽）
        val minX = Math.min(startCx, endCx) - halfWidth
        val minY = Math.min(startCy, endCy) - halfWidth
        val maxX = Math.max(startCx, endCx) + halfWidth
        val maxY = Math.max(startCy, endCy) + halfWidth

        // 更新窗口位置和尺寸
        lineParams.x = minX.toInt()
        lineParams.y = minY.toInt()
        lineParams.width = Math.ceil((maxX - minX).toDouble()).toInt().coerceAtLeast(1)
        lineParams.height = Math.ceil((maxY - minY).toDouble()).toInt().coerceAtLeast(1)

        // 更新直线相对于窗口的坐标
        lineView.startX = startCx - minX
        lineView.startY = startCy - minY
        lineView.endX = endCx - minX
        lineView.endY = endCy - minY

        // 应用更改
        windowManager.updateViewLayout(lineView, lineParams)
        lineView.invalidate()
    }
    private fun addSwipePair() {
        pairCount = pointCount + 1
        val pairId = pairCount

        // 1. 创建管道视图和参数
        val lineView = PipeLineView(this)
        val lineParams = WindowManager.LayoutParams(
            100, 100, // 临时尺寸，稍后更新
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
            x = 0
            y = 0
        }
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

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etLoopCount = dialogView.findViewById<EditText>(R.id.etLoopCount)
        val btnSavePoints = dialogView.findViewById<Button>(R.id.btnSavePoints)
        val btnLoadPoints = dialogView.findViewById<Button>(R.id.btnLoadPoints)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmSettings)

        // 显示当前循环数值
        etLoopCount.setText(if (maxLoopCount <= 0) "" else maxLoopCount.toString())

        btnSavePoints.setOnClickListener { savePointsToFile(); settingsDialog?.dismiss() }
        btnLoadPoints.setOnClickListener { loadPointsFromFile(); settingsDialog?.dismiss() }
        // 取消：直接关闭
        btnCancel.setOnClickListener { settingsDialog?.dismiss() }

        // 确定：保存循环值后关闭
        btnConfirm.setOnClickListener {
            val input = etLoopCount.text.toString()
            maxLoopCount = input.toIntOrNull() ?: 0
            if (maxLoopCount < 0) maxLoopCount = 0
            Toast.makeText(this, "循环次数已更新", Toast.LENGTH_SHORT).show()
            settingsDialog?.dismiss()
        }

        val builder = AlertDialog.Builder(this).setView(dialogView).setCancelable(true)
        settingsDialog = builder.create()

        settingsDialog?.apply {
            window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            window?.setGravity(Gravity.CENTER)
            window?.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            show()
        }
    }

    private fun createCircleButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF5722")) // 橙色
            }
            val sizePx = (35 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }
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
        // 将其他按钮置灰（添加、减少、设置、滑动等）
        setButtonsEnabled(false)
        // 切换主按钮图标为停止
        rootView.findViewById<ImageView>(R.id.btnStart).setImageResource(R.drawable.ic_stop)

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
            val centerX = point.x + point.view.width / 2f
            val centerY = point.y + point.view.height / 2f

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
                        service.performClick(targetX, targetY, point.touchDuration)
                        remaining--
                        runHandler.postDelayed({
                            doAction()
                        }, point.touchDuration + 50)
                    }
                    doAction()
                }
                "滑动起点" -> {
                    val endPoint = clickPoints.find { it.pairId == point.pairId && !it.isStart }
                    if (endPoint != null) {
                        val startView = point.view
                        val endView = endPoint.view
                        val startCx = point.x + startView.width / 2f
                        val startCy = point.y + startView.height / 2f
                        val endCx = endPoint.x + endView.width / 2f
                        val endCy = endPoint.y + endView.height / 2f

                        var remaining = point.repeatCount
                        fun doSwipe() {
                            if (!isRunning || remaining <= 0) {
                                runHandler.postDelayed({ performRound(currentIndex + 1) }, point.delay)
                                return
                            }

                            // 隐藏起点终点视图，避免遮挡
                            startView.visibility = View.INVISIBLE
                            endView.visibility = View.INVISIBLE

                            runHandler.postDelayed({
                                // 调用优化后的 performSwipe
                                service.performSwipe(startCx, startCy, endCx, endCy, point.touchDuration)

                                // 手势执行后恢复视图
                                runHandler.postDelayed({
                                    startView.visibility = View.VISIBLE
                                    endView.visibility = View.VISIBLE
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
                        service.performClick(targetX, targetY, 50)
                        runHandler.postDelayed({
                            service.performClick(targetX, targetY, 50)
                            remaining--
                            runHandler.postDelayed({ doDouble() }, point.touchDuration + 50)
                        }, 100)  // 双击间隔100ms
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
        performRound(0)
    }

    private fun stopRunning() {
        isRunning = false
        runHandler.removeCallbacksAndMessages(null)

        // 恢复主悬浮窗
//        rootView.visibility = View.VISIBLE
//        windowManager.updateViewLayout(rootView, layoutParams)
        // 恢复透明度
        rootView.alpha = 1.0f

        // 恢复其他按钮
        setOtherButtonsEnabled(true)

        // 移除空白区域触摸监听
        rootView.setOnTouchListener(null)

        // 恢复所有点击点视图
        for (point in clickPoints) {
            point.view.alpha = 1.0f                     // 恢复完全不透明
            point.params?.let { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager.updateViewLayout(point.view, params)
            }
        }

        rootView.findViewById<ImageView>(R.id.btnStart).setImageResource(R.drawable.ic_play)
    }

    private fun setButtonsEnabled(enable: Boolean) {
        val buttonsToDisable = listOf(
            R.id.btnAdd,
            R.id.btnReduce,
            R.id.btnSettings,
            R.id.btnSlide,   // 如果有
            R.id.btnHide
        )
        for (id in buttonsToDisable) {
            val btn = rootView.findViewById<View>(id)
            btn.isEnabled = enable
            btn.alpha = if (enable) 1.0f else 0.4f   // 置灰效果
        }
        // 启动按钮本身不在此处禁用，它由 isRunning 状态控制图标变化，但仍可点击（用于停止）
    }

    /**
     * 判断触摸点是否落在允许的按钮上（主按钮、退出按钮）
     */
    private fun isTouchOnAllowedButton(rawX: Int, rawY: Int): Boolean {
        val allowedIds = listOf(R.id.btnMain, R.id.btnExit)
        for (id in allowedIds) {
            val btn = rootView.findViewById<View>(id)
            val loc = IntArray(2)
            btn.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(
                loc[0], loc[1],
                loc[0] + btn.width, loc[1] + btn.height
            )
            if (rect.contains(rawX, rawY)) return true
        }
        return false
    }

    /**
     * 启用/禁用除主按钮、退出按钮以外的按钮
     */
    private fun setOtherButtonsEnabled(enable: Boolean) {

        val idsToDisable = listOf(
            R.id.btnStart, R.id.btnAdd, R.id.btnSlide,
            R.id.btnReduce, R.id.btnSettings, R.id.btnHide
        )
        for (id in idsToDisable) {
            val btn = rootView.findViewById<View>(id)
            btn.isEnabled = enable
            btn.alpha = if (enable) 1.0f else 0.4f   // 置灰效果
        }
    }

    /**
     * 显示给定点的设置对话框（全局悬浮）
     */
    private fun showPointSettingsDialog(point: ClickPoint) {
        pointSettingsDialog?.dismiss()

        // 加载设置布局
        val dialogView = LayoutInflater.from(this).inflate(R.layout.activity_point_settings, null)

        // 绑定控件（保持原有 bindViews 调用，或直接手动绑定）
        val etName = dialogView.findViewById<EditText>(R.id.etName)
//        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgType)
        val rbClick = dialogView.findViewById<RadioButton>(R.id.rbClick)
        val rbDoubleClick = dialogView.findViewById<RadioButton>(R.id.rbDoubleClick)
        val rbBack = dialogView.findViewById<RadioButton>(R.id.rbBack)
        val rbHome = dialogView.findViewById<RadioButton>(R.id.rbHome)
        val rbNotification = dialogView.findViewById<RadioButton>(R.id.rbNotification)
//        val rbLongClick = dialogView.findViewById<RadioButton>(R.id.rbLongClick)
        val rgPosition = dialogView.findViewById<RadioGroup>(R.id.rgPosition)
        val etDelay = dialogView.findViewById<EditText>(R.id.etDelay)
        val etTouchDuration = dialogView.findViewById<EditText>(R.id.etTouchDuration)
        val etRepeatCount = dialogView.findViewById<EditText>(R.id.etRepeatCount)
        val etRandomDistance = dialogView.findViewById<EditText>(R.id.etRandomDistance)

        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val rbSwipe = dialogView.findViewById<RadioButton>(R.id.rbSwipe)
        val tvTouchDurationLabel = dialogView.findViewById<TextView>(R.id.tvTouchDurationLabel)

        val allTypeButtons = listOf(rbClick, rbDoubleClick, rbBack, rbHome, rbNotification, rbSwipe)

        // 定义共享的互斥监听器
        val typeCheckedListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // 取消其他所有按钮的选中状态
                allTypeButtons.forEach { other ->
                    if (other != buttonView) {
                        other.isChecked = false
                    }
                }
            }
        }
        // 为所有按钮绑定同一个监听器
        allTypeButtons.forEach { it.setOnCheckedChangeListener(typeCheckedListener) }

        // 填充数据（与之前相同）
        etName.setText(point.name)

        // 绑定监听器
        allTypeButtons.forEach { it.setOnCheckedChangeListener(typeCheckedListener) }
        if (point.pairId != -1) {
            // 滑动点：隐藏其他按钮，只显示滑动
            allTypeButtons.forEach { it.visibility = View.GONE }
            rbSwipe.visibility = View.VISIBLE
            rbSwipe.isChecked = true
            rbSwipe.isEnabled = false   // 不可取消
            tvTouchDurationLabel.text = "滑动持续时间（毫秒）"
        } else {
            // 普通点时隐藏滑动按钮
            rbSwipe.visibility = View.GONE
            // 显示其他所有按钮
            allTypeButtons.forEach { it.visibility = View.VISIBLE }
            when (point.type) {
                "双击" -> rbDoubleClick.isChecked = true
                "返回键" -> rbBack.isChecked = true
                "主页键" -> rbHome.isChecked = true
                "打开通知栏" -> rbNotification.isChecked = true
                else -> rbClick.isChecked = true
            }
        }
//        when (point.type) {
//            "双击" -> rbDoubleClick.isChecked = true
//            "返回键" -> rbBack.isChecked = true
//            "主页键" -> rbHome.isChecked = true
//            "打开通知栏" -> rbNotification.isChecked = true
//            else -> rbClick.isChecked = true
//        }
//        when (point.clickPosition) {
//            "随机" -> rgPosition.check(R.id.rbRandom)
//            else -> rgPosition.check(R.id.rbCenter)
//        }
        etDelay.setText(point.delay.toString())
        etTouchDuration.setText(point.touchDuration.toString())
        etRepeatCount.setText(point.repeatCount.toString())
        etRandomDistance.setText(point.randomDistance.toString())
        // 取消
        btnCancel.setOnClickListener {
            pointSettingsDialog?.dismiss()
        }
        // 保存逻辑
        btnSave.setOnClickListener {
            point.name = etName.text.toString().ifBlank { "点 ${point.id}" }
            point.delay = etDelay.text.toString().toLongOrNull() ?: 0
            point.touchDuration = etTouchDuration.text.toString().toLongOrNull() ?: 300
            point.repeatCount = etRepeatCount.text.toString().toIntOrNull() ?: 1
            point.randomDistance = etRandomDistance.text.toString().toIntOrNull() ?: 0
            if (point.pairId == -1) {
                point.type = when {
                    rbDoubleClick.isChecked -> "双击"
                    rbBack.isChecked -> "返回键"
                    rbHome.isChecked -> "主页键"
                    rbNotification.isChecked -> "打开通知栏"
                    else -> "点击"
                }
            }
            // 滑动点的类型保持不变
            point.clickPosition = if (rgPosition.checkedRadioButtonId == R.id.rbRandom) "随机" else "中间"

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

        // 外层容器添加百分比边距
//        val wrapperView = FrameLayout(this).apply {
//            val metrics = resources.displayMetrics
//            val padH = (metrics.widthPixels * 0.1).toInt()   // 左右各 10%
//            val padV = (metrics.heightPixels * 0.05).toInt()  // 上下各 10%
//            setPadding(padH, padV, padH, padV)
//            addView(dialogView)
//        }
        // 外层容器，设置 10% 屏幕边距，使卡片四周留空
        val wrapper = FrameLayout(this).apply {
            val metrics = resources.displayMetrics
            val padH = (metrics.widthPixels * 0.1).toInt()
            val padV = (metrics.heightPixels * 0.05).toInt()
            setPadding(padH, padV, padH, padV)
            addView(dialogView)
        }

        val builder = AlertDialog.Builder(this)
            .setView(wrapper)
            .setCancelable(true)

        pointSettingsDialog = builder.create()

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
            // 窗口大小自适应内容，四周的 padding 区域就是透明可点击的外部区域
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)   // 点击外部空白关闭
            show()
        }
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
                        val lineParams = WindowManager.LayoutParams(
                            100, 100, // 临时尺寸，稍后更新
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
                            x = 0
                            y = 0
                        }
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
            rootView.findViewById<ImageView>(R.id.btnHide)
                .setImageResource(R.drawable.ic_display)
            setButtonsEnabledForHide(true)   // 置灰除主按钮、退出、隐藏以外的按钮
            Toast.makeText(this, "已隐藏点击点", Toast.LENGTH_SHORT).show()
        } else {
            // 退出隐藏模式
            rootView.alpha = 1.0f
            for (point in clickPoints) { point.view.visibility = View.VISIBLE }
            for ((_, pipePair) in pipeMap) { pipePair.first.visibility = View.VISIBLE }
            rootView.findViewById<ImageView>(R.id.btnHide)
                .setImageResource(R.drawable.ic_hide)
            setButtonsEnabledForHide(false)  // 恢复所有按钮
            Toast.makeText(this, "已显示点击点", Toast.LENGTH_SHORT).show()
        }
    }
    private fun setButtonsEnabledForHide(hide: Boolean) {
        val idsToDisable = listOf(
            R.id.btnStart, R.id.btnAdd, R.id.btnSlide,
            R.id.btnReduce, R.id.btnSettings
        )
        for (id in idsToDisable) {
            val btn = rootView.findViewById<View>(id)
            btn.isEnabled = !hide
            btn.alpha = if (hide) 0.4f else 1.0f
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