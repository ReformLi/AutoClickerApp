package com.hpu.autoclicker.core.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 拖动透明度滑块时的临时预览。
 *
 * 需求：按住滑块拖动的过程中，弹框（连同背后的遮罩）整体隐藏，只在屏幕底部留一条
 * 20% 不透明度的滑动进行条，方便直接看清悬浮窗 / 点位的真实透明度。
 *
 * 为什么不用弹框自身的窗口承载预览条：
 * 弹框窗口被压到 alpha=0 后其内容全部不可见，而滑块必须保持挂载（否则手势会被取消），
 * 所以预览条放在独立的、不可触摸的 overlay 窗口里，滑块仍由原弹框窗口继续接收拖动。
 */
class OpacityDragPreview(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bar: BarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var dialog: AlertDialog? = null
    private var active = false

    /** 拖动开始：隐藏弹框并显示进行条 */
    fun show(dialog: AlertDialog?, value: Float) {
        this.dialog = dialog
        if (!active) {
            active = true
            dialog?.window?.let { window ->
                val attrs = window.attributes
                attrs.alpha = 0f
                window.attributes = attrs
                // 弹框默认压暗背景，预览时必须去掉，才能看到真实的透明度
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        val view = bar ?: BarView(context).also { bar = it }
        view.value = value
        val lp = params ?: createParams().also { params = it }
        try {
            if (added) windowManager.updateViewLayout(view, lp) else windowManager.addView(view, lp)
            added = true
        } catch (e: Exception) {
            Log.e("OpacityPreview", "显示透明度预览条失败", e)
        }
    }

    /** 拖动过程中刷新进行条位置（弹框处于隐藏态才生效） */
    fun update(value: Float) {
        if (active) bar?.value = value
    }

    /** 拖动结束 / 弹框关闭：恢复弹框并移除进行条 */
    fun hide() {
        if (!active) return
        active = false
        dialog?.window?.let { window ->
            try {
                val attrs = window.attributes
                attrs.alpha = 1f
                window.attributes = attrs
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } catch (e: Exception) {
                Log.e("OpacityPreview", "恢复透明度弹框失败", e)
            }
        }
        dialog = null
        if (added) {
            try {
                windowManager.removeView(bar)
            } catch (e: Exception) {
                Log.e("OpacityPreview", "移除透明度预览条失败", e)
            }
            added = false
        }
    }

    private fun createParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return WindowManager.LayoutParams(
            (metrics.widthPixels * 0.78f).toInt(),
            dp(52),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 一条极简的滑动进行条：百分比 + 轨道 + 已选填充 + 圆点 */
    private class BarView(context: Context) : View(context) {

        var value: Float = 1f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }

        private val density = resources.displayMetrics.density
        private val brandColor = OverlayPalette.brand.toArgb()
        // 轨道 / 填充 / 圆点统一 20% 不透明度，百分比数字稍高一点，
        // 避免叠在深色内容上完全看不清（整条仍是「20% 的滑动进行条」）
        private val barAlpha = 51
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            alpha = barAlpha
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            alpha = barAlpha
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            alpha = 150
            textAlign = Paint.Align.CENTER
            textSize = 13 * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val padH = 8 * density
            val barH = 8 * density
            val thumbR = 7 * density
            val centerY = height - 18 * density

            rect.set(padH, centerY - barH / 2, width - padH, centerY + barH / 2)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, bgPaint)

            val thumbX = rect.left + rect.width() * value
            val fillRight = max(thumbX, rect.left + barH / 2)
            canvas.drawRoundRect(
                RectF(rect.left, rect.top, fillRight, rect.bottom),
                barH / 2,
                barH / 2,
                fillPaint,
            )
            canvas.drawCircle(thumbX, centerY, thumbR, fillPaint)

            canvas.drawText(
                "${(value * 100).roundToInt()}%",
                width / 2f,
                centerY - 16 * density,
                textPaint,
            )
        }
    }
}
