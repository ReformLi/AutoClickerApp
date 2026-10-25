package com.hpu.autoclicker.feature.clicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette

/**
 * 连点器的点位标记 —— 点击点 / 滑动起点 / 滑动终点共用一个原生 View。
 *
 * 视觉规格（与 app 悬浮窗按钮同一套语言）：
 * - 44dp 圆角方块、14dp 圆角（悬浮按钮是 40dp / 12dp，点位略大以便手指按住）
 * - 实心态：品牌青线性渐变（brandLight → brandDeep）+ 1.5dp 白色半透明描边 + 4dp 柔和投影
 * - 描边态（滑动终点）：浅色底 brandSoft + 品牌色描边 + 深色字，对应 AppButton 的 Outlined 变体
 * - 点击点的数字外再套一圈细白环（靶心），一眼能看出「这是屏幕上的点击靶」
 */
class PointMarkerView(context: Context) : View(context) {

    enum class Style {
        /** 点击点 */
        CLICK,

        /** 滑动起点 */
        START,

        /** 滑动终点 */
        END,
    }

    companion object {
        /** 标记边长（dp） */
        const val SIZE_DP = 44

        /** 标记半径（dp）：滑动通道两端按它留白，避免线条压在标记上 */
        const val RADIUS_DP = SIZE_DP / 2
    }

    var style: Style = Style.CLICK
        set(value) {
            field = value
            invalidate()
        }

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 执行动作时短暂高亮：整块切到品牌强调色（浅青）+ 深色字，起到「这一步正在执行」的强调 */
    var highlighted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val cornerRadius = 14f * density
    private val strokeWidth = 1.5f * density
    private val chipRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    /** 点击点的靶心环：白色低透明细环 */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(0x4D, 0xFF, 0xFF, 0xFF)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        // setShadowLayer 只在软件层生效
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val outlined = style == Style.END && !highlighted
        val inset = strokeWidth / 2f + 1f
        chipRect.set(inset, inset, w - inset, h - inset)

        // 底色 + 投影
        if (highlighted) {
            fillPaint.shader = null
            fillPaint.color = OverlayPalette.accent.toArgb()
        } else if (outlined) {
            fillPaint.shader = null
            fillPaint.color = OverlayPalette.brandSoft.toArgb()
        } else {
            fillPaint.shader = LinearGradient(
                chipRect.left,
                chipRect.top,
                chipRect.right,
                chipRect.bottom,
                OverlayPalette.brandLight.toArgb(),
                OverlayPalette.brandDeep.toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
        fillPaint.setShadowLayer(
            if (highlighted) 9f * density else 6f * density,
            0f,
            2f * density,
            if (highlighted) {
                Color.argb(0x80, 0x5A, 0xD1, 0xE0)
            } else {
                Color.argb(0x4D, 0, 0, 0)
            },
        )
        canvas.drawRoundRect(chipRect, cornerRadius, cornerRadius, fillPaint)
        fillPaint.clearShadowLayer()
        fillPaint.shader = null

        // 顶部柔光：让纯色块有轻微的体积感
        highlightPaint.shader = LinearGradient(
            0f,
            chipRect.top,
            0f,
            chipRect.top + chipRect.height() * 0.55f,
            Color.argb(0x40, 0xFF, 0xFF, 0xFF),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(chipRect, cornerRadius, cornerRadius, highlightPaint)
        highlightPaint.shader = null

        // 描边
        strokePaint.color = when {
            highlighted -> Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
            outlined -> OverlayPalette.brand.toArgb()
            else -> Color.argb(0x8C, 0xFF, 0xFF, 0xFF)
        }
        canvas.drawRoundRect(chipRect, cornerRadius, cornerRadius, strokePaint)

        // 文字
        textPaint.color = when {
            highlighted -> OverlayPalette.onBrandSoft.toArgb()
            outlined -> OverlayPalette.onBrandSoft.toArgb()
            else -> Color.WHITE
        }
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            if (style == Style.CLICK) 17f else 15f,
            resources.displayMetrics,
        )
        val fm = textPaint.fontMetrics
        val baseline = chipRect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, chipRect.centerX(), baseline, textPaint)

        // 点击点：数字外圈靶心环
        if (style == Style.CLICK) {
            ringPaint.color = if (highlighted) {
                Color.argb(0x66, 0x00, 0x36, 0x3D)
            } else {
                Color.argb(0x4D, 0xFF, 0xFF, 0xFF)
            }
            canvas.drawCircle(chipRect.centerX(), chipRect.centerY(), chipRect.height() * 0.34f, ringPaint)
        }
    }
}
