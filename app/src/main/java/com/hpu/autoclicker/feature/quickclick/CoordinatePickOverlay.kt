package com.hpu.autoclicker.feature.quickclick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 全屏坐标拾取浮层（「屏幕取点」）。
 *
 * 屏幕覆盖半透明置灰 + 网格帘，能看见下层应用但触摸全部由本浮层消费，
 * 因此点选位置不会触发下层应用点击。手指按下实时显示十字线与坐标，
 * 松手后把屏幕绝对坐标回传给宿主并自动关闭浮层。
 * 浮层没有取消按钮，系统返回键等同取消并回到配置弹框。
 */
class CoordinatePickOverlay(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPicked(x: Int, y: Int)
        fun onCancel()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null

    fun show() {
        // 浮层窗口无 Activity 承载，返回键需在根视图上自行拦截
        val overlay = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && root != null) {
                        dismiss()
                        listener.onCancel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val pickView = PickView(context)
        overlay.addView(
            pickView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val hint = TextView(context).apply {
            text = "点击屏幕任意位置获取坐标，按返回键退出"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xCC000000.toInt())
            }
        }
        overlay.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(72) },
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 可聚焦以接收系统返回键；不加 NOT_TOUCHABLE，触摸事件全部由本窗口消费
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            windowManager.addView(overlay, lp)
            root = overlay
            overlay.requestFocus()
        } catch (e: Exception) {
            android.util.Log.e("CoordinatePick", "addView 失败", e)
            root = null
            listener.onCancel()
        }
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

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    /** 置灰网格底 + 十字线与实时坐标；松手即取点 */
    private inner class PickView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val gridStep = 40f * density

        private val scrimPaint = Paint().apply { color = 0x66000000 }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x26FFFFFF
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5AD1E0.toInt()
            strokeWidth = 2f * density
            style = Paint.Style.STROKE
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5AD1E0.toInt()
            strokeWidth = 2f * density
            style = Paint.Style.STROKE
        }
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6000000.toInt()
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
        }

        private var touchX = -1f
        private var touchY = -1f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(scrimPaint.color)

            drawGrid(canvas)

            if (touchX >= 0f && touchY >= 0f) {
                drawCrosshair(canvas, touchX, touchY)
                drawCoordinateBubble(canvas, touchX, touchY)
            }
        }

        private fun drawGrid(canvas: Canvas) {
            var x = 0f
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += gridStep
            }
            var y = 0f
            while (y <= height) {
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += gridStep
            }
        }

        private fun drawCrosshair(canvas: Canvas, x: Float, y: Float) {
            canvas.drawLine(0f, y, width.toFloat(), y, crossPaint)
            canvas.drawLine(x, 0f, x, height.toFloat(), crossPaint)
            canvas.drawCircle(x, y, 10f * density, ringPaint)
        }

        private fun drawCoordinateBubble(canvas: Canvas, x: Float, y: Float) {
            val label = "X ${x.roundToInt()}   Y ${y.roundToInt()}"
            val pad = 8f * density
            val rect = RectF(
                x + 16f * density,
                y + 16f * density,
                x + 16f * density + textPaint.measureText(label) + pad * 2,
                y + 16f * density + textPaint.textSize + pad * 2,
            )
            // 贴边时把气泡翻到指针另一侧，避免跑出屏幕
            if (rect.right > width) rect.offset(width - rect.right, 0f)
            if (rect.bottom > height) rect.offset(0f, -(rect.bottom - height) - 32f * density)
            canvas.drawRoundRect(rect, pad, pad, bubblePaint)
            canvas.drawText(label, rect.left + pad, rect.top + pad + textPaint.textSize, textPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    touchX = event.rawX.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
                    touchY = event.rawY.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val x = event.rawX.roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
                    val y = event.rawY.roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
                    dismiss()
                    listener.onPicked(x, y)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dismiss()
                    listener.onCancel()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
