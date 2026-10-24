package com.hpu.autoclicker.feature.quickclick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 区域选择器视图：铺满显示一张底图（屏幕截图或相册图），在其上做
 * 「置灰 + 网格」遮罩，仅中央选框区域保留原色无网格。
 *
 * 交互（与需求一致）：
 * - 单指在框内拖动 → 移动选框；
 * - 双指捏合 → 以框中心为锚点放大/缩小选框；
 * - 可通过 [setAspectRatio] 锁定宽高比（null 为自由比例），锁定时缩放保持比例；
 * - 选框始终被约束在图像范围内，最小边长 [minSide] 像素。
 *
 * 通过 [RegionSelectCallback] 把结果（当前选框，图像像素坐标）暴露给宿主，
 * 宿主「确定」时用 [cropSelected] 裁出选框对应的原图区域。
 */
class RegionSelectView(context: Context) : View(context) {

    interface RegionSelectCallback {
        fun onRegionChanged(rectPx: Rect)
    }

    var callback: RegionSelectCallback? = null

    /** 底图（截图/相册图），view 尺寸与之可能不同，绘制时等比铺满 */
    private var source: Bitmap? = null

    // 选框（图像原始像素坐标系）
    private val selRectPx = RectF()
    // 图像→视图的缩放与偏移（fitCenter 等效，但这里用铺满中心裁剪，故 scale 取 max）
    private var drawScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val minSide = 40f // 图像像素最小边长

    /** 锁定宽高比（宽/高），null = 自由比例 */
    private var lockedRatio: Float? = null

    // ===== 画笔 =====
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint().apply { color = OverlayPalette.scrim.toArgb() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayPalette.grid.toArgb()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayPalette.accent.toArgb()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val thirdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayPalette.thirds.toArgb()
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    // ===== 触摸状态 =====
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 0f
    private var startRect = RectF()
    private var pinchCenterPx = PointF()

    // 网格线间隔（视图像素）
    private val gridStepPx = 36f

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_PINCH = 2
    }

    fun setSource(bitmap: Bitmap) {
        source = bitmap
        // 初始选框：居中正方形，边长取图像短边的 40%
        post {
            computeTransform()
            val side = min(bitmap.width, bitmap.height) * 0.4f
            selRectPx.set(
                bitmap.width / 2f - side / 2f,
                bitmap.height / 2f - side / 2f,
                bitmap.width / 2f + side / 2f,
                bitmap.height / 2f + side / 2f,
            )
            invalidate()
            callback?.onRegionChanged(selRectPx.toRectI())
        }
    }

    /** 当前选框（图像像素坐标），供宿主裁剪 */
    fun currentRectPx(): Rect = selRectPx.toRectI()

    /**
     * 设置裁剪宽高比（宽/高），null 表示自由比例。
     * 非空时以当前选框中心为锚点、保持面积不变地调整到目标比例；
     * 之后的捏合缩放也只等比改变尺寸，不再改变比例。
     */
    fun setAspectRatio(ratio: Float?) {
        lockedRatio = ratio
        val src = source ?: return
        if (ratio == null || ratio <= 0f) {
            invalidate()
            return
        }
        if (selRectPx.width() <= 0f || selRectPx.height() <= 0f) return
        val cx = selRectPx.centerX()
        val cy = selRectPx.centerY()
        val area = selRectPx.width() * selRectPx.height()
        var w = sqrt(area * ratio)
        val maxW = min(src.width.toFloat(), src.height * ratio).coerceAtLeast(1f)
        val minW = max(minSide, minSide * ratio).coerceAtMost(maxW)
        w = w.coerceIn(minW, maxW)
        val h = w / ratio
        selRectPx.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        clampRect(src)
        invalidate()
        callback?.onRegionChanged(selRectPx.toRectI())
    }

    /** 当前锁定的宽高比，null 为自由比例 */
    fun currentAspectRatio(): Float? = lockedRatio

    /** 裁出选框对应的底图区域（图像像素坐标） */
    fun cropSelected(): Bitmap? {
        val src = source ?: return null
        val r = selRectPx.toRectI()
        if (r.width() <= 0 || r.height() <= 0) return null
        val clamped = Rect(
            r.left.coerceIn(0, src.width - 1),
            r.top.coerceIn(0, src.height - 1),
            r.right.coerceIn(1, src.width),
            r.bottom.coerceIn(1, src.height),
        )
        return Bitmap.createBitmap(src, clamped.left, clamped.top, clamped.width(), clamped.height())
    }

    private fun computeTransform() {
        val src = source ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || src.width <= 0 || src.height <= 0) return
        // 等比铺满（cover）：取较大缩放，短边留白用居中裁剪的负偏移
        drawScale = max(vw / src.width, vh / src.height)
        offsetX = (vw - src.width * drawScale) / 2f
        offsetY = (vh - src.height * drawScale) / 2f
    }

    // 图像坐标 → 视图坐标
    private fun toViewX(imgX: Float) = imgX * drawScale + offsetX
    private fun toViewY(imgY: Float) = imgY * drawScale + offsetY
    // 视图坐标 → 图像坐标
    private fun toImgX(vx: Float) = (vx - offsetX) / drawScale
    private fun toImgY(vy: Float) = (vy - offsetY) / drawScale

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        canvas.drawColor(OverlayPalette.canvas.toArgb())

        val l = toViewX(0f)
        val t = toViewY(0f)
        val r = toViewX(src.width.toFloat())
        val b = toViewY(src.height.toFloat())
        canvas.drawBitmap(src, null, RectF(l, t, r, b), bmpPaint)

        val boxL = toViewX(selRectPx.left)
        val boxT = toViewY(selRectPx.top)
        val boxR = toViewX(selRectPx.right)
        val boxB = toViewY(selRectPx.bottom)
        val box = RectF(boxL, boxT, boxR, boxB)

        // 遮罩 + 网格只画在框外：用偶奇填充的 Path（外框大矩形 XOR 选框）
        val mask = Path().apply {
            fillType = Path.FillType.WINDING
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            // 挖空选框：改用 EVEN_ODD
        }
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addRect(box, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(mask)
        canvas.drawPaint(scrimPaint)
        drawGrid(canvas)
        canvas.restore()

        // 选框边框 + 三分线 + 角标
        canvas.drawRect(box, boxPaint)
        drawThirds(canvas, box)
    }

    private fun drawGrid(canvas: Canvas) {
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += gridStepPx
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += gridStepPx
        }
    }

    private fun drawThirds(canvas: Canvas, box: RectF) {
        val dx = box.width() / 3f
        val dy = box.height() / 3f
        canvas.drawLine(box.left + dx, box.top, box.left + dx, box.bottom, thirdPaint)
        canvas.drawLine(box.left + 2 * dx, box.top, box.left + 2 * dx, box.bottom, thirdPaint)
        canvas.drawLine(box.left, box.top + dy, box.right, box.top + dy, thirdPaint)
        canvas.drawLine(box.left, box.top + 2 * dy, box.right, box.top + 2 * dy, thirdPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val src = source ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_DRAG
                lastX = event.x
                lastY = event.y
                // 把按下点若落在框外，先把框中心移到按下点，形成「拖动框跟手」
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    mode = MODE_PINCH
                    startDist = dist(event)
                    startRect.set(selRectPx)
                    pinchCenterPx.set(
                        toImgX((event.getX(0) + event.getX(1)) / 2f),
                        toImgY((event.getY(0) + event.getY(1)) / 2f),
                    )
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_DRAG -> {
                        val dImgX = toImgX(event.x) - toImgX(lastX)
                        val dImgY = toImgY(event.y) - toImgY(lastY)
                        selRectPx.offset(dImgX, dImgY)
                        clampRect(src)
                        lastX = event.x
                        lastY = event.y
                    }
                    MODE_PINCH -> {
                        if (event.pointerCount >= 2) {
                            val factor = (dist(event) / startDist).coerceAtLeast(0.05f)
                            applyScale(src, factor)
                        }
                    }
                }
                invalidate()
                callback?.onRegionChanged(selRectPx.toRectI())
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) mode = MODE_NONE
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 以 pinchCenterPx 为锚点，按 factor 相对 startRect 缩放选框 */
    private fun applyScale(src: Bitmap, factor: Float) {
        val ratio = lockedRatio
        if (ratio != null && ratio > 0f) {
            // 锁定比例：只按宽度缩放，高度随比例变化，锚点保持相对位置
            val maxW = min(src.width.toFloat(), src.height * ratio).coerceAtLeast(1f)
            val minW = max(minSide, minSide * ratio).coerceAtMost(maxW)
            val nw = (startRect.width() * factor).coerceIn(minW, maxW)
            val nh = nw / ratio
            val relX = if (startRect.width() > 0f)
                ((pinchCenterPx.x - startRect.left) / startRect.width()).coerceIn(0f, 1f) else 0.5f
            val relY = if (startRect.height() > 0f)
                ((pinchCenterPx.y - startRect.top) / startRect.height()).coerceIn(0f, 1f) else 0.5f
            val nl = pinchCenterPx.x - relX * nw
            val nt = pinchCenterPx.y - relY * nh
            selRectPx.set(nl, nt, nl + nw, nt + nh)
            clampRect(src)
            return
        }
        val leftSpan = pinchCenterPx.x - startRect.left
        val rightSpan = startRect.right - pinchCenterPx.x
        val topSpan = pinchCenterPx.y - startRect.top
        val bottomSpan = startRect.bottom - pinchCenterPx.y
        var nl = pinchCenterPx.x - leftSpan * factor
        var nr = pinchCenterPx.x + rightSpan * factor
        var nt = pinchCenterPx.y - topSpan * factor
        var nb = pinchCenterPx.y + bottomSpan * factor
        // 最小边长约束
        if (nr - nl < minSide) {
            val mid = (nr + nl) / 2f
            nl = mid - minSide / 2f; nr = mid + minSide / 2f
        }
        if (nb - nt < minSide) {
            val mid = (nb + nt) / 2f
            nt = mid - minSide / 2f; nb = mid + minSide / 2f
        }
        // 不能超过原尺寸太多（最多 100% 图像）
        selRectPx.set(nl.coerceAtLeast(0f), nt.coerceAtLeast(0f),
            nr.coerceAtMost(src.width.toFloat()), nb.coerceAtMost(src.height.toFloat()))
        clampRect(src)
    }

    private fun clampRect(src: Bitmap) {
        val w = selRectPx.width()
        val h = selRectPx.height()
        var l = selRectPx.left
        var t = selRectPx.top
        if (l < 0) l = 0f
        if (t < 0) t = 0f
        if (l + w > src.width) l = src.width - w
        if (t + h > src.height) t = src.height - h
        selRectPx.set(l, t, l + w, t + h)
    }

    private fun RectF.toRectI(): Rect =
        Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
}
