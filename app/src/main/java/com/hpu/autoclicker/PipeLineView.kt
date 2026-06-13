package com.hpu.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class PipeLineView(context: Context) : View(context) {
    companion object {
        const val STROKE_WIDTH = 18f
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        strokeWidth = STROKE_WIDTH + 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5537D5FF")
        strokeWidth = STROKE_WIDTH + 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 固定宽度的高亮蓝色画笔
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00B8D4")
        strokeWidth = STROKE_WIDTH             // 固定宽度（px），可按需改为 dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFFFFFF")
        style = Paint.Style.FILL
    }

    // 相对于视图左上角的坐标
    var startX: Float = 0f
    var startY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dx = endX - startX
        val dy = endY - startY
        if (dx * dx + dy * dy < 4f) return

        // 绘制带阴影和光晕的方向管道
        canvas.drawLine(startX, startY, endX, endY, shadowPaint)
        canvas.drawLine(startX, startY, endX, endY, glowPaint)
        canvas.drawLine(startX, startY, endX, endY, linePaint)

        // 在终点附近绘制箭头，强化滑动方向
        val angle = atan2(dy, dx)
        val arrowLength = 28f
        val arrowHalfWidth = 14f
        val tipX = endX
        val tipY = endY
        val baseX = tipX - arrowLength * cos(angle)
        val baseY = tipY - arrowLength * sin(angle)
        val normalX = -sin(angle)
        val normalY = cos(angle)

        val arrowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(baseX + normalX * arrowHalfWidth, baseY + normalY * arrowHalfWidth)
            lineTo(baseX - normalX * arrowHalfWidth, baseY - normalY * arrowHalfWidth)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
    }
}
