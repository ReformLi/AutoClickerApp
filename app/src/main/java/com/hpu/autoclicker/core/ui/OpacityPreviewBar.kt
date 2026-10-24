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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.max
import kotlin.math.roundToInt

/** 进行条顶部为百分比数字预留的高度（dp） */
private const val TEXT_SPACE_DP = 24f

/**
 * 拖动透明度滑块时的临时预览。
 *
 * 需求：按住滑块拖动的过程中，弹框（连同背后的遮罩）整体隐藏，只把「20% 不透明度的滑动进行条」
 * 画在**当前这根滑块原来的位置**上——滑钮就在手指下方，位置不发生跳变，才好继续拖。
 *
 * 为什么不用弹框自身的窗口承载预览条：
 * 弹框窗口被压到 alpha=0 后其内容全部不可见，而滑块必须保持挂载（否则手势会被取消），
 * 所以预览条放在独立的、不可触摸的 overlay 窗口里，滑块仍由原弹框窗口继续接收拖动。
 */
class OpacityDragPreview(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bar: BarView? = null
    private var dialog: AlertDialog? = null
    private var active = false

    /**
     * 拖动开始：隐藏弹框，并把进行条摆到 [anchor]（滑块在屏幕上的矩形）。
     *
     * @param anchor 被拖动滑块的屏幕矩形，进行条与它同宽、轨道线同高
     * @param minValue 滑块量程下限（透明度为 0.2），用于让填充比例与真实滑块一致
     */
    fun show(dialog: AlertDialog?, value: Float, anchor: Rect, minValue: Float) {
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
        view.minValue = minValue
        view.value = value
        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, createParams(anchor))
            } else {
                windowManager.addView(view, createParams(anchor))
            }
        } catch (e: Exception) {
            Log.e("OpacityPreview", "显示透明度预览条失败", e)
        }
    }

    /** 拖动过程中刷新进行条数值（弹框处于隐藏态才生效） */
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
        bar?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.e("OpacityPreview", "移除透明度预览条失败", e)
                }
            }
        }
    }

    private fun createParams(anchor: Rect): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val textSpace = (TEXT_SPACE_DP * metrics.density).toInt()
        return WindowManager.LayoutParams(
            anchor.width.roundToInt(),
            anchor.height.roundToInt() + textSpace,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = anchor.left.roundToInt()
            // 顶部多留一条给百分比数字，轨道线才能与滑块原位置重合
            y = anchor.top.roundToInt() - textSpace
        }
    }

    /**
     * 一条极简的滑动进行条：百分比 + 轨道 + 已选填充 + 圆点。
     * 几何尺寸与 `AppSlider` 对齐（轨道左右各缩进一个滑钮半径、滑钮直径 24dp），
     * 换成它以后看起来就是原滑块原地变透明，而不是跳到别处。
     */
    private class BarView(context: Context) : View(context) {

        var value: Float = 1f
            set(v) {
                field = v.coerceIn(0f, 1f)
                invalidate()
            }

        var minValue: Float = 0f
            set(v) {
                field = v
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
        private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brandColor
            alpha = 110
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
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
            val thumbR = 12 * density          // 与 AppSlider 的滑钮半径一致
            val trackH = 8 * density           // 与 AppSlider 的轨道高度一致
            val textSpace = TEXT_SPACE_DP * density
            val centerY = textSpace + (height - textSpace) / 2f

            val railLeft = thumbR
            val railRight = width - thumbR
            rect.set(railLeft, centerY - trackH / 2, railRight, centerY + trackH / 2)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, bgPaint)

            val span = 1f - minValue
            val fraction =
                if (span <= 0f) 0f else ((value - minValue) / span).coerceIn(0f, 1f)
            val thumbX = railLeft + (railRight - railLeft) * fraction
            val fillRight = max(thumbX, railLeft + trackH / 2)
            canvas.drawRoundRect(
                RectF(railLeft, rect.top, fillRight, rect.bottom),
                trackH / 2,
                trackH / 2,
                fillPaint,
            )

            canvas.drawCircle(thumbX, centerY, thumbR - thumbRingPaint.strokeWidth / 2, thumbRingPaint)

            canvas.drawText(
                "${(value * 100).roundToInt()}%",
                width / 2f,
                centerY - 16 * density,
                textPaint,
            )
        }
    }

    private companion object {
        /** 进行条顶部为百分比数字预留的高度 */
        const val TEXT_SPACE_DP = 24f
    }
}
