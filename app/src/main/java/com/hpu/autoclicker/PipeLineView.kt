package com.hpu.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class PipeLineView(context: Context) : View(context) {
    // 固定宽度的半透明淡蓝色画笔
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600BCD4")  // 淡蓝半透明
        strokeWidth = 30f                      // 固定宽度（px），可按需改为 dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    // 相对于视图左上角的坐标
    var startX: Float = 0f
    var startY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制直线连接两个圆心
        canvas.drawLine(startX, startY, endX, endY, paint)
    }
}