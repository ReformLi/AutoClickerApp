package com.hpu.autoclicker.feature.recorder

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Point
import android.graphics.PixelFormat
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
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.ui.ServiceDialogs

/**
 * 录点器悬浮服务 —— 与连点器 / 代点器完全独立：
 * 只共用悬浮窗基础设施（ComposeView、拖动、弹框宿主），脚本、存储、运行态都归本模块所有。
 *
 * 编辑态：主按钮 + 录制 / 运行 / 脚本 / 设置 / 关闭。
 * 录制态：主窗隐藏，全屏透明捕获层消费触摸并即时回显；顶部小条提供开始 / 暂停 / 停止。
 * 运行态：按脚本逐步注入手势，支持暂停、停止、返回编辑；重复次数是脚本属性，改动会写回脚本。
 *
 * 现场保存：
 * - 录制每落一步写 `draft.json`，服务被切模块/被杀后可在下次启动恢复；
 * - 回放进度写 `session.json`，切回录点器时恢复为暂停态，绝不自动注入。
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_STOP = "com.hpu.autoclicker.STOP_RECORDER"

        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "recorder"

        /** 切换 NOT_TOUCHABLE 后等一帧多再注入，避免回显手势落回捕获层 */
        private const val INJECTION_SWITCH_DELAY_MS = 48L
    }

    private enum class RecordSession { NONE, READY, RECORDING, PAUSED }

    private lateinit var windowManager: WindowManager

    private lateinit var root: ComposeView
    private lateinit var lp: WindowManager.LayoutParams
    private val composeHost = ServiceComposeHost()
    private var rootAdded = false

    private val ui = RecorderFloatingUiState()

    // ===== 悬浮窗手感 =====
    private var touchSlop = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var initWindowX = 0
    private var initWindowY = 0
    private var isDragging = false
    private var mainHidden = false
    private var forceNotTouchable = false

    // ===== 录制小条 =====
    private val barState = RecorderBarState()
    private var barView: ComposeView? = null
    private var barHost: ServiceComposeHost? = null
    private lateinit var barLp: WindowManager.LayoutParams
    private var barAdded = false
    private var barX = -1
    private var barY = -1
    private var barDownRawX = 0f
    private var barDownRawY = 0f
    private var barInitX = 0
    private var barInitY = 0

    private var captureOverlay: RecorderCaptureOverlay? = null
    private var replayTimeout: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())

    private val engineListener: RecorderPlaybackEngine.Listener =
        object : RecorderPlaybackEngine.Listener {
        override fun onProgress(stepIndex: Int, repeatIndex: Int) {
            ui.currentStep = stepIndex
            ui.currentRepeat = repeatIndex
            ui.playing = true
            ui.paused = engine.isPaused
        }

        override fun onPaused() {
            ui.playing = true
            ui.paused = true
            persistSession(engine.currentStepIndex, engine.currentRepeatIndex)
        }

        override fun onFinished() {
            ui.playing = false
            ui.paused = false
            ui.currentStep = 0
            ui.currentRepeat = 0
            RecorderStateStore.clearSession(this@RecorderService)
            unblockPanel()
            toast("回放完成")
        }

        override fun onError(message: String) {
            ui.playing = true
            ui.paused = true
            persistSession(engine.currentStepIndex, engine.currentRepeatIndex)
            toast(message)
        }

        override fun onStepDispatched(step: RecorderStep) {
            if (gestureIntersectsPanel(step)) {
                forceNotTouchable = true
                updateMainWindowTouchable()
            }
        }

        override fun onStepCompleted() = unblockPanel()

        override fun injectionGuardDelayMs(): Long =
            if (forceNotTouchable) INJECTION_SWITCH_DELAY_MS else 0L
    }

    private val engine: RecorderPlaybackEngine = RecorderPlaybackEngine(
        handler = handler,
        serviceProvider = { AutoClickerAccessibilityService.instance },
        listener = engineListener,
    )

    // ===== 当前脚本 / 录制现场 / 回放现场 =====
    private var currentScript: RecorderScript? = null
    private var recordSession = RecordSession.NONE
    private var recordingScript: RecorderScript? = null
    private var recordingStartedAt = 0L
    private var preRecordScript: RecorderScript? = null
    private var recordedFromDraft = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        root = ComposeView(this)
        composeHost.attach(root)
        root.alpha = RecorderStore.getWindowAlpha(this)
        root.setContent {
            AutoClickerTheme {
                RecorderFloatingContent(
                    state = ui,
                    onMainTouch = { event -> onMainTouch(event) },
                    onRecord = { requestNewRecording() },
                    onRun = { enterRunningMode() },
                    onScripts = { showScriptManagerDialog() },
                    onSettings = { showSettingsDialog() },
                    onClose = { requestCloseWindow() },
                    onTogglePlay = { togglePlayback() },
                    onStopPlay = { stopPlayback() },
                    onReturnEdit = { requestReturnToEdit() },
                    onRepeatChange = { value -> changeRepeatCount(value) },
                )
            }
        }

        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (getScreenHeight() - dp(56)) / 2
        }

        try {
            windowManager.addView(root, lp)
            rootAdded = true
        } catch (e: Exception) {
            android.util.Log.e("Recorder", "addView 失败", e)
        }

        ui.speed = RecorderStore.getSpeed(this)
        ui.repeatCount = 1

        startForegroundIfNeeded()

        root.post { restorePersistedState() }
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
        replayTimeout?.let { handler.removeCallbacks(it) }
        replayTimeout = null
        if (recordSession == RecordSession.RECORDING || recordSession == RecordSession.PAUSED) {
            persistDraft()
        }
        if (ui.playing) {
            persistSession(engine.currentStepIndex, engine.currentRepeatIndex)
        }
        engine.stop()
        captureOverlay?.dismiss()
        captureOverlay = null
        removeBar()
        if (rootAdded) {
            try {
                windowManager.removeView(root)
            } catch (e: Exception) {
                android.util.Log.e("Recorder", "removeView 失败", e)
            }
            rootAdded = false
        }
        composeHost.destroy()
        handler.removeCallbacksAndMessages(null)
        sendBroadcast(Intent("com.hpu.autoclicker.SERVICE_STOPPED").setPackage(packageName))
        super.onDestroy()
    }

    /** Android 15 起 dataSync 前台服务有时长上限，超时需主动停止 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            super.onTimeout(startId, fgsType)
        }
        android.util.Log.w("Recorder", "dataSync 前台服务达到运行时长上限，主动停止")
        stopSelf(startId)
    }

    // ==================== 主按钮 ====================

    private fun onMainTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            downRawX = event.rawX
            downRawY = event.rawY
            initWindowX = lp.x
            initWindowY = lp.y
            isDragging = false
            ui.mainPressed = true
            true
        }

        MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - downRawX
            val dy = event.rawY - downRawY
            if (!isDragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                isDragging = true
                ui.mainPressed = false
            }
            if (isDragging) {
                lp.x = (initWindowX + dx.toInt())
                    .coerceIn(0, (getScreenWidth() - root.width).coerceAtLeast(0))
                lp.y = (initWindowY + dy.toInt())
                    .coerceIn(0, (getScreenHeight() - root.height).coerceAtLeast(0))
                try {
                    windowManager.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    android.util.Log.e("Recorder", "updateViewLayout 失败", e)
                }
            }
            true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            ui.mainPressed = false
            if (isDragging) {
                if (event.actionMasked == MotionEvent.ACTION_UP) snapToEdge()
            } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                ui.isExpanded = !ui.isExpanded
                clampWindowY()
            }
            isDragging = false
            true
        }

        else -> false
    }

    private fun snapToEdge() {
        val screenW = getScreenWidth()
        val viewW = root.width.coerceAtLeast(root.measuredWidth)
        lp.x = if (lp.x + viewW / 2 < screenW / 2) 0 else (screenW - viewW).coerceAtLeast(0)
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("Recorder", "snapToEdge 失败", e)
        }
    }

    private fun clampWindowY() {
        root.post {
            if (!rootAdded) return@post
            val maxY = (getScreenHeight() - root.height).coerceAtLeast(0)
            val newY = lp.y.coerceIn(0, maxY)
            if (newY != lp.y) {
                lp.y = newY
                try {
                    windowManager.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    android.util.Log.e("Recorder", "clampWindowY 失败", e)
                }
            }
        }
    }

    private fun hideMainWindow() {
        if (!rootAdded) return
        mainHidden = true
        root.visibility = View.INVISIBLE
        updateMainWindowTouchable()
    }

    private fun showMainWindow() {
        if (!rootAdded) return
        mainHidden = false
        root.visibility = View.VISIBLE
        updateMainWindowTouchable()
        clampWindowY()
    }

    /** 隐藏 / 回放手势与悬浮窗冲突时，统一在这里增删 NOT_TOUCHABLE */
    private fun updateMainWindowTouchable() {
        if (!rootAdded) return
        val touchable = !mainHidden && !forceNotTouchable
        val newFlags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags == lp.flags) return
        lp.flags = newFlags
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("Recorder", "切换触摸状态失败", e)
        }
    }

    private fun unblockPanel() {
        if (!forceNotTouchable) return
        forceNotTouchable = false
        updateMainWindowTouchable()
    }

    /** 注入手势的落点/路径是否压住悬浮窗本身：是则这一步让悬浮窗临时不可触摸 */
    private fun gestureIntersectsPanel(step: RecorderStep): Boolean {
        if (mainHidden || !rootAdded) return false
        // lp.x/lp.y 不含状态栏偏移，用实际屏幕位置判断，避免边界处漏判
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + root.width
        val bottom = top + root.height
        if (right <= left || bottom <= top) return false
        fun inside(x: Int, y: Int): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        return when (step.type) {
            RecorderStepType.SWIPE -> {
                val minX = minOf(step.x1, step.x2)
                val maxX = maxOf(step.x1, step.x2)
                val minY = minOf(step.y1, step.y2)
                val maxY = maxOf(step.y1, step.y2)
                maxX >= left && minX <= right && maxY >= top && minY <= bottom
            }

            RecorderStepType.CLICK, RecorderStepType.LONG_PRESS -> inside(step.x, step.y)
            RecorderStepType.WAIT -> false
        }
    }

    // ==================== 录制：准备条 → 捕获层 → 结束三选一 ====================

    /** 开始新录制：已有步骤或草稿时先确认，避免误清 */
    private fun requestNewRecording() {
        if (recordSession != RecordSession.NONE) return
        val steps = currentScript?.steps?.size ?: 0
        val hasDraft = RecorderStateStore.loadDraft(this) != null
        if (steps > 0 || hasDraft) {
            showConfirmDialog(
                title = "开始新录制",
                message = "当前 $steps 步以及未完成的录制草稿都会被清空，确定重新录制？",
                confirmText = "重新录制",
                danger = true,
            ) {
                beginNewRecording()
            }
        } else {
            beginNewRecording()
        }
    }

    private fun beginNewRecording() {
        preRecordScript = currentScript
        recordedFromDraft = false
        val script = RecorderScript.newEmpty(
            name = RecorderStore.defaultScriptName(this),
            screenW = getScreenWidth(),
            screenH = getScreenHeight(),
            orientation = resources.configuration.orientation,
        )
        beginReady(script)
    }

    /** 草稿恢复 / 脚本入口「恢复草稿」：保留已录步骤，停在准备态等用户点开始 */
    private fun beginReady(script: RecorderScript) {
        if (recordSession != RecordSession.NONE) return
        engine.stop()
        ui.mode = RecorderPanelMode.EDIT
        ui.isExpanded = true
        ui.playing = false
        ui.paused = false
        recordingScript = script
        recordingStartedAt = System.currentTimeMillis()
        recordSession = RecordSession.READY
        barState.stepCount = script.steps.size
        barState.phase = RecorderBarPhase.READY
        applyCurrentScript(script)
        ui.draftAvailable = false
        hideMainWindow()
        showBar()
        toast(
            if (recordedFromDraft) "草稿已载入，点「开始」继续录制"
            else "准备好后点「开始」，录制期间会即时回显"
        )
    }

    private fun startCapture() {
        if (recordSession != RecordSession.READY) return
        val script = recordingScript ?: return
        val overlay = RecorderCaptureOverlay(this, captureListener)
        overlay.show()
        if (!overlay.isShowing()) {
            toast("无法创建录制层，请检查悬浮窗权限后重试")
            return
        }
        if (!recordedFromDraft) RecorderStateStore.clearDraft(this)
        captureOverlay = overlay
        // 捕获层会盖住户窗口；先移除小条，添加捕获层后再把小条放回最上层
        removeBar()
        showBar()
        recordSession = RecordSession.RECORDING
        barState.phase = RecorderBarPhase.RECORDING
        overlay.setRecordingVisual(true)
        overlay.setTouchable(true)
        persistDraft()
        if (AutoClickerAccessibilityService.instance == null) {
            toast("无障碍未连接：仍可录制，但不会即时回显")
        }
        if (script.steps.isNotEmpty()) barState.stepCount = script.steps.size
    }

    private val captureListener = object : RecorderCaptureOverlay.Listener {
        override fun onStepRecorded(step: RecorderStep) = recordStep(step)

        override fun onSystemAreaTouched() {
            toast("顶部状态栏 / 底部导航栏区域不会录制，请避开系统栏")
        }

        override fun onIgnored(reason: String) {
            toast(reason)
        }
    }

    private fun recordStep(step: RecorderStep) {
        if (recordSession != RecordSession.RECORDING) return
        val script = recordingScript ?: return
        if (script.steps.size >= RecorderScript.MAX_RECORD_STEPS) {
            pauseRecording()
            toast("已达单次录制上限 ${RecorderScript.MAX_RECORD_STEPS} 步，已自动暂停")
            return
        }
        val updated = script.copy(
            steps = script.steps + step,
            updatedAt = System.currentTimeMillis(),
        )
        recordingScript = updated
        barState.stepCount = updated.steps.size
        ui.stepCount = updated.steps.size
        persistDraft()
        replayDuringRecording(step)
    }

    /**
     * 录制即时回显：注入这一步，让用户看到实际效果。
     * 注入期间捕获层不可触摸（注入手势要落到下层应用），结束后恢复；
     * 暂停/停止则不再恢复，由用户显式继续。
     */
    private fun replayDuringRecording(step: RecorderStep) {
        val svc = AutoClickerAccessibilityService.instance ?: return
        val gesture = buildRecorderGesture(step) ?: return
        val overlay = captureOverlay ?: return

        replayTimeout?.let { handler.removeCallbacks(it) }
        overlay.setTouchable(false)
        val timeout = Runnable {
            replayTimeout = null
            if (recordSession == RecordSession.RECORDING) overlay.setTouchable(true)
        }
        replayTimeout = timeout
        handler.postDelayed(timeout, INJECTION_SWITCH_DELAY_MS + step.duration.coerceAtLeast(50L) + 900L)
        // 等一帧让 NOT_TOUCHABLE 生效，否则注入手势会被捕获层吞掉，看不到回显
        handler.postDelayed({
            if (recordSession != RecordSession.RECORDING) return@postDelayed
            svc.dispatchInjectedGesture(gesture) {
                handler.post {
                    handler.removeCallbacks(timeout)
                    if (replayTimeout === timeout) replayTimeout = null
                    if (recordSession == RecordSession.RECORDING) overlay.setTouchable(true)
                }
            }
        }, INJECTION_SWITCH_DELAY_MS)
    }

    private fun pauseRecording() {
        if (recordSession != RecordSession.RECORDING) return
        recordSession = RecordSession.PAUSED
        barState.phase = RecorderBarPhase.PAUSED
        captureOverlay?.setTouchable(false)
        captureOverlay?.setRecordingVisual(false)
        persistDraft()
    }

    private fun resumeRecording() {
        if (recordSession != RecordSession.PAUSED) return
        recordSession = RecordSession.RECORDING
        barState.phase = RecorderBarPhase.RECORDING
        captureOverlay?.setTouchable(true)
        captureOverlay?.setRecordingVisual(true)
    }

    private fun togglePauseRecording() {
        when (recordSession) {
            RecordSession.RECORDING -> pauseRecording()
            RecordSession.PAUSED -> resumeRecording()
            else -> Unit
        }
    }

    private fun requestStopRecording() {
        if (recordingScript == null) return
        if (recordSession == RecordSession.RECORDING) pauseRecording()
        showStopRecordingDialog()
    }

    private fun showStopRecordingDialog() {
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.88f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderStopDialog(
                stepCount = recordingScript?.steps?.size ?: 0,
                onSave = {
                    dialogRef?.dismiss()
                    promptSaveRecording()
                },
                onDiscard = {
                    dialogRef?.dismiss()
                    confirmDiscardRecording()
                },
                onContinue = {
                    dialogRef?.dismiss()
                    resumeRecording()
                },
            )
        }
    }

    private fun promptSaveRecording() {
        val script = recordingScript ?: return
        showNameDialog(
            title = "保存脚本",
            initial = script.name,
            hint = "输入脚本名称",
        ) { name ->
            saveScriptNamed(script, name) { saved ->
                RecorderStateStore.clearDraft(this)
                finishRecordingSession()
                applyCurrentScript(saved)
                ui.draftAvailable = false
                toast("已保存脚本「${saved.name}」")
            }
        }
    }

    private fun confirmDiscardRecording() {
        val steps = recordingScript?.steps?.size ?: 0
        showConfirmDialog(
            title = "丢弃录制",
            message = "已录的 $steps 步将无法恢复，确定丢弃？",
            confirmText = "丢弃",
            danger = true,
        ) {
            RecorderStateStore.clearDraft(this)
            finishRecordingSession()
            applyCurrentScript(
                RecorderScript.newEmpty(
                    name = RecorderStore.defaultScriptName(this),
                    screenW = getScreenWidth(),
                    screenH = getScreenHeight(),
                    orientation = resources.configuration.orientation,
                )
            )
            ui.draftAvailable = false
            toast("已丢弃本次录制")
        }
    }

    private fun finishRecordingSession() {
        recordSession = RecordSession.NONE
        recordingScript = null
        recordingStartedAt = 0L
        preRecordScript = null
        recordedFromDraft = false
        replayTimeout?.let { handler.removeCallbacks(it) }
        replayTimeout = null
        captureOverlay?.dismiss()
        captureOverlay = null
        barState.phase = RecorderBarPhase.READY
        barState.stepCount = 0
        removeBar()
        showMainWindow()
    }

    private fun cancelReadyRecording() {
        if (recordSession != RecordSession.READY) return
        val restore = if (recordedFromDraft) {
            RecorderStateStore.loadDraft(this)?.toScript() ?: preRecordScript
        } else {
            preRecordScript
        }
        finishRecordingSession()
        applyCurrentScript(restore)
        ui.draftAvailable = RecorderStateStore.loadDraft(this) != null
        toast("已取消录制")
    }

    // ==================== 录制小条 ====================

    private fun showBar() {
        if (barAdded) return
        val view = ComposeView(this)
        val host = ServiceComposeHost()
        host.attach(view)
        view.setContent {
            AutoClickerTheme {
                RecorderBarContent(
                    state = barState,
                    onStart = { startCapture() },
                    onCancel = { cancelReadyRecording() },
                    onTogglePause = { togglePauseRecording() },
                    onStop = { requestStopRecording() },
                    onDragTouch = { event -> onBarTouch(event) },
                )
            }
        }
        barLp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (barX >= 0) barX else dp(12)
            y = if (barY >= 0) barY else (getScreenHeight() - dp(150)).coerceAtLeast(0)
        }
        try {
            windowManager.addView(view, barLp)
            barView = view
            barHost = host
            barAdded = true
        } catch (e: Exception) {
            android.util.Log.e("Recorder", "录制小条添加失败", e)
            host.destroy()
        }
    }

    private fun removeBar() {
        val view = barView
        if (view != null) {
            barX = barLp.x
            barY = barLp.y
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                android.util.Log.e("Recorder", "录制小条移除失败", e)
            }
        }
        barView = null
        barAdded = false
        barHost?.destroy()
        barHost = null
    }

    private fun onBarTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            barDownRawX = event.rawX
            barDownRawY = event.rawY
            barInitX = barLp.x
            barInitY = barLp.y
            true
        }

        MotionEvent.ACTION_MOVE -> {
            val view = barView
            if (view != null) {
                val maxX = (getScreenWidth() - view.width).coerceAtLeast(0)
                val maxY = (getScreenHeight() - view.height).coerceAtLeast(0)
                barLp.x = (barInitX + (event.rawX - barDownRawX).toInt()).coerceIn(0, maxX)
                barLp.y = (barInitY + (event.rawY - barDownRawY).toInt()).coerceIn(0, maxY)
                try {
                    windowManager.updateViewLayout(view, barLp)
                } catch (e: Exception) {
                    android.util.Log.e("Recorder", "录制小条移动失败", e)
                }
            }
            true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            barX = barLp.x
            barY = barLp.y
            true
        }

        else -> false
    }

    // ==================== 回放 ====================

    private fun enterRunningMode() {
        val script = currentScript
        if (script == null || script.steps.isEmpty()) {
            toast("请先录制或加载一个脚本")
            return
        }
        if (AutoClickerAccessibilityService.instance == null) {
            toast("无障碍服务未连接，请到系统设置重新开启")
            return
        }
        ui.mode = RecorderPanelMode.RUNNING
        ui.isExpanded = true
        ui.speed = RecorderStore.getSpeed(this)
        ui.repeatCount = script.repeatCount
        startPlayback()
    }

    private fun startPlayback() {
        val script = currentScript ?: return
        if (script.steps.isEmpty()) {
            toast("脚本没有可回放的步骤")
            return
        }
        if (AutoClickerAccessibilityService.instance == null) {
            toast("无障碍服务未连接，请到系统设置重新开启")
            return
        }
        ui.speed = RecorderStore.getSpeed(this)
        ui.currentStep = 0
        ui.currentRepeat = 0
        ui.playing = true
        ui.paused = false
        if (!engine.start(script, ui.speed)) {
            ui.playing = false
        }
    }

    private fun togglePlayback() {
        when {
            ui.playing && ui.paused -> {
                engine.resume()
                ui.paused = false
            }

            ui.playing -> {
                engine.pause()
                ui.paused = true
                persistSession(engine.currentStepIndex, engine.currentRepeatIndex)
            }

            else -> startPlayback()
        }
    }

    private fun stopPlayback() {
        engine.stop()
        ui.playing = false
        ui.paused = false
        ui.currentStep = 0
        ui.currentRepeat = 0
        RecorderStateStore.clearSession(this)
        unblockPanel()
        toast("回放已停止")
    }

    private fun requestReturnToEdit() {
        if (!ui.playing) {
            ui.mode = RecorderPanelMode.EDIT
            return
        }
        showConfirmDialog(
            title = "返回编辑",
            message = "回放会立即停止，当前进度不会保留。",
            confirmText = "停止并返回",
            danger = true,
        ) {
            engine.stop()
            ui.playing = false
            ui.paused = false
            ui.currentStep = 0
            ui.currentRepeat = 0
            RecorderStateStore.clearSession(this)
            unblockPanel()
            ui.mode = RecorderPanelMode.EDIT
            ui.isExpanded = true
            ui.repeatCount = currentScript?.repeatCount ?: 1
            toast("已返回编辑")
        }
    }

    /** 重复次数是脚本属性：运行中修改立即生效，并写回当前脚本；已保存脚本同步落盘 */
    private fun changeRepeatCount(value: Int) {
        val script = currentScript ?: return
        val count = value.coerceIn(RecorderScript.MIN_REPEAT, RecorderScript.MAX_REPEAT)
        if (count == ui.repeatCount) return
        currentScript = script.copy(repeatCount = count)
        ui.repeatCount = count
        engine.setRepeatCount(count)
        if (isSaved(script.id)) {
            currentScript = RecorderStore.saveScript(this, currentScript!!)
            toast("重复次数已更新为 $count，已写入脚本")
        } else {
            toast("重复次数已更新为 $count（脚本尚未保存）")
        }
    }

    private fun persistSession(stepIndex: Int, repeatIndex: Int) {
        val script = currentScript ?: return
        if (!ui.playing) return
        RecorderStateStore.saveSession(
            this,
            RecorderPlaybackSession(
                script = script,
                stepIndex = stepIndex,
                repeatIndex = repeatIndex,
                speed = ui.speed,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ==================== 启动时恢复草稿 / 回放现场 ====================

    private fun restorePersistedState() {
        val session = RecorderStateStore.loadSession(this)
        if (session != null) {
            restoreSession(session)
            return
        }
        val draft = RecorderStateStore.loadDraft(this)
        if (draft != null) showDraftRecoveryDialog(draft)
    }

    private fun restoreSession(session: RecorderPlaybackSession) {
        val script = session.script
        applyCurrentScript(script)
        ui.mode = RecorderPanelMode.RUNNING
        ui.isExpanded = true
        ui.speed = session.speed
        ui.repeatCount = script.repeatCount
        ui.playing = true
        ui.paused = true
        ui.currentStep = session.stepIndex
        ui.currentRepeat = session.repeatIndex
        engine.start(
            script = script,
            speed = session.speed,
            startStep = session.stepIndex,
            startRepeat = session.repeatIndex,
            startPaused = true,
        )
        toast("已恢复上次回放现场，点「继续」接着执行")
    }

    private fun showDraftRecoveryDialog(draft: RecorderDraft) {
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.88f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderDraftRecoveryDialog(
                stepCount = draft.steps.size,
                onContinue = {
                    dialogRef?.dismiss()
                    applyCurrentScript(draft.toScript())
                    recordedFromDraft = true
                    preRecordScript = null
                    beginReady(draft.toScript())
                },
                onLater = {
                    dialogRef?.dismiss()
                    applyCurrentScript(draft.toScript())
                    ui.draftAvailable = true
                    toast("草稿已载入，可从「脚本」入口恢复")
                },
                onDiscard = {
                    dialogRef?.dismiss()
                    confirmDiscardDraft(draft)
                },
            )
        }
    }

    private fun confirmDiscardDraft(draft: RecorderDraft) {
        showConfirmDialog(
            title = "丢弃草稿",
            message = "草稿里的 ${draft.steps.size} 步将无法恢复，确定丢弃？",
            confirmText = "丢弃",
            danger = true,
        ) {
            RecorderStateStore.clearDraft(this)
            applyCurrentScript(null)
            ui.draftAvailable = false
            toast("已丢弃草稿")
        }
    }

    // ==================== 脚本管理 ====================

    private fun showScriptManagerDialog() {
        val scriptsState = mutableStateOf(RecorderStore.loadScripts(this))
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.9f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderScriptManagerDialog(
                scripts = scriptsState.value,
                currentId = currentScript?.id,
                hasDraft = RecorderStateStore.loadDraft(this) != null,
                canSaveCurrent = (currentScript?.steps?.size ?: 0) > 0,
                onLoad = { script ->
                    dialogRef?.dismiss()
                    requestLoadScript(script)
                },
                onRename = { script ->
                    showNameDialog(
                        title = "重命名脚本",
                        initial = script.name,
                        hint = "输入新名称",
                    ) { newName ->
                        val conflict = RecorderStore.loadScripts(this)
                            .firstOrNull { it.name == newName && it.id != script.id }
                        if (conflict != null) {
                            toast("脚本名「$newName」已存在")
                            return@showNameDialog
                        }
                        val renamed = RecorderStore.saveScript(this, script.copy(name = newName))
                        if (currentScript?.id == renamed.id) {
                            currentScript = renamed
                            ui.scriptName = renamed.name
                            ui.scriptSaved = true
                        }
                        scriptsState.value = RecorderStore.loadScripts(this)
                        toast("已重命名为「$newName」")
                    }
                },
                onDelete = { script ->
                    showConfirmDialog(
                        title = "删除脚本",
                        message = "确定删除脚本「${script.name}」？",
                        confirmText = "删除",
                        danger = true,
                    ) {
                        RecorderStore.deleteScript(this, script.id)
                        if (currentScript?.id == script.id) {
                            applyCurrentScript(null)
                        }
                        scriptsState.value = RecorderStore.loadScripts(this)
                        toast("已删除脚本「${script.name}」")
                    }
                },
                onRecoverDraft = {
                    dialogRef?.dismiss()
                    recoverDraftFromManager()
                },
                onSaveCurrent = {
                    val script = currentScript
                    if (script == null || script.steps.isEmpty()) {
                        toast("当前没有步骤可保存")
                    } else {
                        showNameDialog(
                            title = "保存脚本",
                            initial = script.name,
                            hint = "输入脚本名称",
                        ) { name ->
                            saveScriptNamed(script, name) { saved ->
                                applyCurrentScript(saved)
                                ui.draftAvailable = false
                                scriptsState.value = RecorderStore.loadScripts(this)
                                toast("已保存脚本「${saved.name}」")
                            }
                        }
                    }
                },
                onDismiss = { dialogRef?.dismiss() },
            )
        }
    }

    private fun recoverDraftFromManager() {
        val draft = RecorderStateStore.loadDraft(this)
        if (draft == null) {
            toast("没有可恢复的草稿")
            return
        }
        val steps = currentScript?.steps?.size ?: 0
        val start = {
            recordedFromDraft = true
            preRecordScript = currentScript
            beginReady(draft.toScript())
        }
        if (steps > 0) {
            showConfirmDialog(
                title = "恢复草稿",
                message = "恢复草稿会替换当前 $steps 步，确定继续？",
                confirmText = "恢复",
                danger = true,
            ) { start() }
        } else {
            start()
        }
    }

    private fun requestLoadScript(script: RecorderScript) {
        val current = currentScript
        val warn = current != null &&
            current.id != script.id &&
            current.steps.isNotEmpty() &&
            !isSaved(current.id)
        if (warn) {
            showConfirmDialog(
                title = "加载脚本",
                message = "当前步骤尚未保存，加载「${script.name}」会替换它们。",
                confirmText = "加载",
            ) { loadScript(script) }
        } else {
            loadScript(script)
        }
    }

    private fun loadScript(script: RecorderScript) {
        engine.stop()
        ui.playing = false
        ui.paused = false
        RecorderStateStore.clearSession(this)
        applyCurrentScript(script)
        ui.mode = RecorderPanelMode.EDIT
        ui.isExpanded = true
        ui.speed = RecorderStore.getSpeed(this)
        if (script.screenW > 0 && script.screenH > 0 &&
            (script.screenW != getScreenWidth() || script.screenH != getScreenHeight())
        ) {
            toast("脚本录制于 ${script.screenW}×${script.screenH}，当前屏幕为 ${getScreenWidth()}×${getScreenHeight()}，坐标可能有偏移")
        } else {
            toast("已加载脚本「${script.name}」")
        }
    }

    private fun saveScriptNamed(
        script: RecorderScript,
        name: String,
        onSaved: (RecorderScript) -> Unit,
    ) {
        val trimmed = name.trim().take(RecorderScript.MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) {
            toast("名称不能为空")
            return
        }
        val conflict = RecorderStore.loadScripts(this)
            .firstOrNull { it.name == trimmed && it.id != script.id }
        val save = {
            val saved = RecorderStore.saveScript(this, script.copy(name = trimmed))
            // 草稿与已保存脚本是同一份内容时即失效，否则下次启动会重复提示恢复
            if (RecorderStateStore.loadDraft(this)?.id == saved.id) {
                RecorderStateStore.clearDraft(this)
                ui.draftAvailable = false
            }
            onSaved(saved)
        }
        if (conflict != null) {
            showConfirmDialog(
                title = "覆盖脚本",
                message = "脚本「$trimmed」已存在，是否覆盖？",
                confirmText = "覆盖",
                danger = true,
            ) {
                RecorderStore.deleteScript(this, conflict.id)
                save()
            }
        } else {
            save()
        }
    }

    // ==================== 关闭 / 设置 ====================

    private fun requestCloseWindow() {
        if (ui.playing) {
            showConfirmDialog(
                title = "关闭悬浮窗",
                message = "回放中关闭会先停止回放。",
                confirmText = "停止并关闭",
            ) {
                engine.stop()
                ui.playing = false
                RecorderStateStore.clearSession(this)
                closeWindowNow()
            }
            return
        }
        closeWindowNow()
    }

    private fun closeWindowNow() {
        val script = currentScript
        val steps = script?.steps?.size ?: 0
        if (script == null || steps == 0 || isSaved(script.id)) {
            stopSelf()
            return
        }
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.88f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderCloseDialog(
                stepCount = steps,
                onSaveClose = {
                    dialogRef?.dismiss()
                    val script = currentScript ?: return@RecorderCloseDialog
                    saveScriptNamed(script, script.name) { saved ->
                        applyCurrentScript(saved)
                        stopSelf()
                    }
                },
                onDiscardClose = {
                    dialogRef?.dismiss()
                    // 用户明确放弃这些步骤，草稿也一并清掉，避免下次启动又提示恢复
                    RecorderStateStore.clearDraft(this)
                    ui.draftAvailable = false
                    stopSelf()
                },
                onCancel = { dialogRef?.dismiss() },
            )
        }
    }

    private fun showSettingsDialog() {
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.88f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderSettingsDialog(
                initialSpeed = RecorderStore.getSpeed(this),
                initialAlpha = root.alpha,
                onConfirm = { speed, alpha ->
                    RecorderStore.setSpeed(this, speed)
                    RecorderStore.setWindowAlpha(this, alpha)
                    root.alpha = alpha
                    ui.speed = speed
                    dialogRef?.dismiss()
                    toast("设置已保存")
                },
                onCancel = { dialogRef?.dismiss() },
            )
        }
    }

    // ==================== 弹框 / 状态工具 ====================

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        danger: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.85f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderConfirmDialog(
                title = title,
                message = message,
                confirmText = confirmText,
                danger = danger,
                onConfirm = {
                    dialogRef?.dismiss()
                    onConfirm()
                },
                onCancel = { dialogRef?.dismiss() },
            )
        }
    }

    private fun showNameDialog(
        title: String,
        initial: String,
        hint: String,
        onConfirm: (String) -> Unit,
    ) {
        var dialogRef: AlertDialog? = null
        var host: ServiceComposeHost? = null
        val composeHost = ServiceComposeHost().also { host = it }
        dialogRef = ServiceDialogs.show(
            service = this,
            host = composeHost,
            widthRatio = 0.85f,
            onDismiss = { host?.destroy() },
        ) {
            RecorderNameDialog(
                title = title,
                initial = initial,
                hint = hint,
                onConfirm = { name ->
                    dialogRef?.dismiss()
                    onConfirm(name)
                },
                onCancel = { dialogRef?.dismiss() },
            )
        }
    }

    private fun applyCurrentScript(script: RecorderScript?) {
        currentScript = script
        ui.scriptName = script?.name ?: "未命名脚本"
        ui.stepCount = script?.steps?.size ?: 0
        ui.repeatCount = script?.repeatCount ?: 1
        ui.scriptSaved = script != null && isSaved(script.id)
    }

    private fun isSaved(id: String): Boolean =
        RecorderStore.loadScripts(this).any { it.id == id }

    private fun persistDraft() {
        val script = recordingScript ?: return
        if (script.steps.isEmpty()) return
        RecorderStateStore.saveDraft(this, RecorderDraft.from(script, recordingStartedAt))
        ui.draftAvailable = true
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录点器",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val stopIntent = Intent(this, RecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("录点器运行中")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pendingStop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
