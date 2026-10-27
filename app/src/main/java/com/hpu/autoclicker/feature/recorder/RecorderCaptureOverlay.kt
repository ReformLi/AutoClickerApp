package com.hpu.autoclicker.feature.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.roundToInt

/**
 * 录制捕获层：全屏透明窗口，消费屏幕上的所有单指触摸并转成脚本步骤。
 *
 * 它只负责「看清手指怎么动」，不注入、不存脚本：
 * - 按下/抬起间隔 → delayBefore；
 * - 位移未超 touchSlop → CLICK / LONG_PRESS（按按压时长区分）；
 * - 位移超 touchSlop → SWIPE（记录起点、终点与总时长）。
 *
 * 即时回显期间把窗口切成 FLAG_NOT_TOUCHABLE，让注入手势落到下层应用；
 * 回显结束（或超时兜底）再恢复触摸。这个空窗期就是方案里说明过的
 * 「回放瞬间的误触漏洞」，Phase 0 接受该取舍。
 *
 * 底部导航栏区域由本层消费时只提示、不记录，避免把系统栏操作写进脚本。
 */
class RecorderCaptureOverlay(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStepRecorded(step: RecorderStep)
        fun onSystemAreaTouched()
        fun onIgnored(reason: String)
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var root: CaptureView? = null
    private lateinit var lp: WindowManager.LayoutParams

    /** 录制小条的屏幕矩形：落在其中的触摸归小条 / 应用所有，不录成步骤（null 表示没有小条） */
    private var ignoreRect: Rect? = null

    fun isShowing(): Boolean = root != null

    fun setIgnoreRect(rect: Rect?) {
        ignoreRect = rect
    }

    fun show() {
        if (root != null) return
        // 时间线从 0 开始：第一步的回放延迟取 0，不把「点开始前的等待」录进脚本
        val view = CaptureView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点（录制期间应用输入不受影响），触摸全部由本层消费
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        lp = params
        try {
            windowManager.addView(view, params)
            root = view
        } catch (e: Exception) {
            android.util.Log.e("RecorderCapture", "addView 失败", e)
            root = null
        }
    }

    /** 回放注入期间关闭触摸；恢复时把时间线重置到「现在」，暂停时长不计入间隔 */
    fun setTouchable(touchable: Boolean) {
        val view = root ?: return
        // 窗口标志要等输入分发器刷新才生效（约一帧），注入手势可能抢先落回本层；
        // 用抑制位兜底，保证回显手势不会被录成新步骤
        view.suppressInput = !touchable
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            android.util.Log.e("RecorderCapture", "updateViewLayout 失败", e)
        }
        if (touchable) {
            view.resetTimeline()
        } else {
            view.resetGesture()
        }
    }

    /** 录制/暂停的屏幕边框提示（暂停时隐藏，避免误以为还在录） */
    fun setRecordingVisual(recording: Boolean) {
        root?.recordingVisual = recording
    }

    fun dismiss() {
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
    }

    private inner class CaptureView : View(context) {
        private val density = resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayPalette.brand.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayPalette.brand.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }

        private val navBarHeight = systemDimen("navigation_bar_height", 48)
        private val statusBarHeight = systemDimen("status_bar_height", 24)

        private fun systemDimen(name: String, fallbackDp: Int): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            val px = if (id > 0) {
                resources.getDimensionPixelSize(id)
            } else {
                (fallbackDp * density).roundToInt()
            }
            return px.coerceIn(0, resources.displayMetrics.heightPixels / 4)
        }

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downAt = 0L
        private var moved = false
        private var multiTouch = false
        private var lastStepEndAt = 0L

        /** 手势起点落在小条上：整段手势静默丢弃，不记录也不提示 */
        private var ignoredByBar = false

        private var feedbackX = -1f
        private var feedbackY = -1f
        private var feedbackAt = 0L

        /** 注入回显期间为 true：吞掉落在本层的注入手势，不录成新步骤 */
        var suppressInput = false

        private fun cx(value: Float): Int =
            value.roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))

        private fun cy(value: Float): Int =
            value.roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))

        var recordingVisual = true
            set(value) {
                field = value
                invalidate()
            }

        fun resetTimeline() {
            // 还没录过任何一步时保持 0：第一步不应带上「点开始之前的等待」
            if (lastStepEndAt > 0L) lastStepEndAt = SystemClock.uptimeMillis()
        }

        /** 顶部状态栏 / 底部导航栏：系统栏触摸不记录，避免把下拉通知、返回桌面写进脚本 */
        private fun isSystemArea(y: Float): Boolean =
            y < statusBarHeight.toFloat() ||
                (navBarHeight > 0 && y >= (height - navBarHeight).toFloat())

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (recordingVisual) {
                // 细边框提示「正在录制」；不遮挡内容，也不影响触摸
                canvas.drawRect(
                    borderPaint.strokeWidth / 2f,
                    borderPaint.strokeWidth / 2f,
                    width - borderPaint.strokeWidth / 2f,
                    height - borderPaint.strokeWidth / 2f,
                    borderPaint,
                )
            }
            val elapsed = SystemClock.uptimeMillis() - feedbackAt
            if (feedbackX >= 0f && elapsed in 0..280L) {
                val progress = elapsed / 280f
                feedbackPaint.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(
                    feedbackX,
                    feedbackY,
                    (10f + 22f * progress) * density,
                    feedbackPaint,
                )
                postInvalidateOnAnimation()
            }
        }

        private fun showFeedback(x: Float, y: Float) {
            feedbackX = x
            feedbackY = y
            feedbackAt = SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (suppressInput) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) resetGesture()
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val bar = ignoreRect
                    if (bar != null && bar.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        // 小条的非按钮区域：小条自己不收、捕获层也不录，直接吞掉
                        resetGesture()
                        ignoredByBar = true
                        return true
                    }
                    downX = event.rawX
                    downY = event.rawY
                    lastX = downX
                    lastY = downY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    multiTouch = false
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ignoredByBar) return true
                    multiTouch = true
                    listener.onIgnored("多指手势暂不支持，本次未记录")
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (ignoredByBar) return true
                    if (event.pointerCount > 1) multiTouch = true
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                    if (!moved) {
                        val dx = lastX - downX
                        val dy = lastY - downY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) moved = true
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (ignoredByBar) {
                        resetGesture()
                        return true
                    }
                    if (multiTouch) {
                        resetGesture()
                        return true
                    }
                    val upX = event.rawX
                    val upY = event.rawY
                    if (isSystemArea(upY) || isSystemArea(downY)) {
                        listener.onSystemAreaTouched()
                        resetGesture()
                        return true
                    }
                    val upAt = SystemClock.uptimeMillis()
                    val duration = (upAt - downAt).coerceAtLeast(RecorderStep.MIN_GESTURE_DURATION)
                    val delay = if (lastStepEndAt <= 0L) 0L
                    else (downAt - lastStepEndAt).coerceIn(0L, RecorderStep.MAX_DELAY_BEFORE)
                    val step = if (moved) {
                        RecorderStep(
                            type = RecorderStepType.SWIPE,
                            x1 = cx(downX),
                            y1 = cy(downY),
                            x2 = cx(upX),
                            y2 = cy(upY),
                            duration = duration,
                            delayBefore = delay,
                        )
                    } else {
                        RecorderStep(
                            type = if (duration >= longPressTimeout) RecorderStepType.LONG_PRESS
                            else RecorderStepType.CLICK,
                            x = cx(downX),
                            y = cy(downY),
                            duration = duration,
                            delayBefore = delay,
                        )
                    }
                    lastStepEndAt = upAt
                    showFeedback(downX, downY)
                    listener.onStepRecorded(step)
                    resetGesture()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    resetGesture()
                    return true
                }
            }
            return true
        }

        fun resetGesture() {
            moved = false
            multiTouch = false
            downAt = 0L
            ignoredByBar = false
        }
    }
}
