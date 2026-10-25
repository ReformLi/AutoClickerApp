package com.hpu.autoclicker.feature.clicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.hypot

/**
 * 滑动通道 —— 连接滑动起点与终点的视觉引导。
 *
 * 视觉规格：品牌青半透明轨带（10dp 宽）+ 品牌青细中线（1.5dp），
 * 末端一段白色 V 形箭头指向终点；两端按标记半径留白，线条不会压在标记上。
 */
class PipeLineView(context: Context) : View(context) {

    /** 标记半径（px）：通道在两端留出的空隙 */
    var markerRadius: Float = 0f

    /** 执行滑动时短暂高亮：整条通道切到品牌强调色 */
    var highlighted: Boolean = false
        set(value) {
            field = value
            refreshColors()
            invalidate()
        }

    private val density = resources.displayMetrics.density

    /** 轨带：品牌色低透明度的宽描边，充当「通道底」 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12f * density
        color = brandWithAlpha(0x4D)
    }

    /** 中线：品牌色实线 */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = brandWithAlpha(0xE6)
    }

    /** 箭头描边：深品牌色垫底，保证浅色背景下也能看清白色箭头 */
    private val arrowEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.5f * density
        color = OverlayPalette.brandDeep.toArgb()
    }

    /** 箭头：白色 V 形 */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
        color = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
    }

    // 相对于视图左上角的坐标
    var startX: Float = 0f
    var startY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f

    /** 按高亮状态刷新各画笔颜色 */
    private fun refreshColors() {
        if (highlighted) {
            trackPaint.color = colorWithAlpha(OverlayPalette.accent.toArgb(), 0x66)
            linePaint.color = colorWithAlpha(OverlayPalette.accent.toArgb(), 0xFF)
            arrowEdgePaint.color = OverlayPalette.brandDeep.toArgb()
            arrowPaint.color = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
        } else {
            trackPaint.color = brandWithAlpha(0x4D)
            linePaint.color = brandWithAlpha(0xE6)
            arrowEdgePaint.color = OverlayPalette.brandDeep.toArgb()
            arrowPaint.color = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
        }
    }

    init {
        refreshColors()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dx = endX - startX
        val dy = endY - startY
        val length = hypot(dx, dy)
        if (length < 1f) return

        val ux = dx / length
        val uy = dy / length
        val r = markerRadius
        if (length <= r * 2f + 1f) return

        val sx = startX + ux * r
        val sy = startY + uy * r
        val ex = endX - ux * r
        val ey = endY - uy * r

        canvas.drawLine(sx, sy, ex, ey, trackPaint)
        canvas.drawLine(sx, sy, ex, ey, linePaint)

        // 末端箭头：两笔组成 V 形，尖端指向终点（先深色垫底再叠白色）
        val arrowLen = 10f * density
        val arrowHalf = 8f * density
        val nx = -uy
        val ny = ux
        val tipX = ex - ux * 1.5f * density
        val tipY = ey - uy * 1.5f * density
        val baseX = tipX - ux * arrowLen
        val baseY = tipY - uy * arrowLen
        canvas.drawLine(baseX + nx * arrowHalf, baseY + ny * arrowHalf, tipX, tipY, arrowEdgePaint)
        canvas.drawLine(baseX - nx * arrowHalf, baseY - ny * arrowHalf, tipX, tipY, arrowEdgePaint)
        canvas.drawLine(baseX + nx * arrowHalf, baseY + ny * arrowHalf, tipX, tipY, arrowPaint)
        canvas.drawLine(baseX - nx * arrowHalf, baseY - ny * arrowHalf, tipX, tipY, arrowPaint)
    }

    private fun brandWithAlpha(alpha: Int): Int {
        val c = OverlayPalette.brand.toArgb()
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
