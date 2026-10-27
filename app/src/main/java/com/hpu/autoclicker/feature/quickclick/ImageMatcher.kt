package com.hpu.autoclicker.feature.quickclick

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.hpu.autoclicker.core.util.DebugLog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 模板匹配引擎（不依赖 OpenCV）。
 *
 * 算法：降采样 → 灰度/RGB → 归一化互相关（NCC）滑窗 → 取最高分匹配点。
 * 提速手段（保证结果精度不变的前提）：
 * 1. NCC 在单遍扫描内同时累加 Σs、Σs²、Σs·t，避免对每个窗口做多轮回读；
 * 2. 先以 [STRIDE_DIVISOR] 分之一的模板尺寸为步长做粗扫，再围绕粗扫最优的
 *    若干候选点做逐级减半步长的局部精扫（coarse-to-fine），最终落到 1px 精度。
 *
 * 调用方应在工作线程调用，全屏降采样 2x 后典型耗时几十~几百 ms。
 */
object ImageMatcher {
    private const val TAG = "ImageMatcher"

    /** 粗扫步长 = min(模板宽, 模板高) / STRIDE_DIVISOR，且至少为 2px */
    private const val STRIDE_DIVISOR = 8

    /** 粗扫保留的候选点数量（NCC 峰值可能不止一个） */
    private const val COARSE_CANDIDATES = 3

    data class MatchResult(
        val confidence: Float,   // [0,1]，>=threshold 为有效匹配
        val point: PointF,       // 匹配中心点（原始屏幕坐标系）
    )

    private class Candidate(val x: Int, val y: Int, val score: Float)

    /**
     * 在 screen 中查找 template 的最佳匹配位置。
     * @param screen    当前屏幕截图
     * @param template  模板图
     * @param scale     降采样倍率（1=不降采样，2=宽高各减半，4=¼）
     * @param grayMode  true=灰度匹配（抗色偏），false=RGB 三通道
     * @return MatchResult；confidence < threshold 表示未找到
     */
    fun match(
        screen: Bitmap,
        template: Bitmap,
        threshold: Float = 0.80f,
        scale: Int = 2,
        grayMode: Boolean = true,
    ): MatchResult {
        val s = scale.coerceIn(1, 4)
        val sw = screen.width / s
        val sh = screen.height / s
        val tw = template.width / s
        val th = template.height / s

        if (tw < 4 || th < 4 || sw <= 0 || sh <= 0) {
            Log.w(TAG, "模板/画面尺寸过小，无法匹配")
            return emptyResult()
        }
        if (tw > sw || th > sh) {
            Log.w(TAG, "模板(${template.width}x${template.height})大于搜索画面(${screen.width}x${screen.height})")
            return emptyResult()
        }

        // 尺寸相同时 createScaledBitmap 可能直接返回原对象，绝不能回收调用方的 Bitmap
        val scaledScreen =
            if (sw == screen.width && sh == screen.height) screen
            else Bitmap.createScaledBitmap(screen, sw, sh, true)
        val scaledTemplate =
            if (tw == template.width && th == template.height) template
            else Bitmap.createScaledBitmap(template, tw, th, true)

        try {
            val channels = if (grayMode) 1 else 3
            val screenPixels = IntArray(sw * sh)
            val templatePixels = IntArray(tw * th)
            scaledScreen.getPixels(screenPixels, 0, sw, 0, 0, sw, sh)
            scaledTemplate.getPixels(templatePixels, 0, tw, 0, 0, tw, th)

            val screenData = FloatArray(sw * sh * channels)
            val templateData = FloatArray(tw * th * channels)
            fillPixels(screenPixels, screenData, channels)
            fillPixels(templatePixels, templateData, channels)

            val n = (tw * th * channels).toDouble()
            var tSum = 0.0
            var tSq = 0.0
            for (v in templateData) {
                tSum += v
                tSq += v * v
            }
            // 与旧实现一致的方差下限：模板几乎纯色时无法可靠匹配
            if (tSq - tSum * tSum / n < n) {
                Log.w(TAG, "模板方差过低，跳过匹配")
                return emptyResult()
            }

            val best = search(screenData, templateData, channels, sw, sh, tw, th, tSum, tSq)

            // 映射回原始屏幕坐标（窗口中心）
            val origX = (best.x + tw / 2f) * s
            val origY = (best.y + th / 2f) * s
            DebugLog.d(
                TAG,
                "匹配完成: score=${best.score} at ($origX,$origY) 模板=${template.width}x${template.height} " +
                    "scale=${s}x gray=$grayMode",
            )
            return MatchResult(best.score.coerceIn(0f, 1f), PointF(origX, origY))
        } finally {
            if (scaledScreen !== screen) scaledScreen.recycle()
            if (scaledTemplate !== template) scaledTemplate.recycle()
        }
    }

    /**
     * 限定搜索区域匹配：裁剪 screen 的 [region] 后匹配，坐标偏移回全屏幕。
     * @param region 搜索矩形（原始屏幕像素 l,t,r,b）
     */
    fun matchInRegion(
        screen: Bitmap,
        template: Bitmap,
        region: Rect,
        threshold: Float = 0.80f,
        scale: Int = 2,
        grayMode: Boolean = true,
    ): MatchResult {
        val clampedX = region.left.coerceIn(0, screen.width - 1)
        val clampedY = region.top.coerceIn(0, screen.height - 1)
        val clampedW = region.width().coerceAtMost(screen.width - clampedX)
        val clampedH = region.height().coerceAtMost(screen.height - clampedY)
        if (clampedW <= 0 || clampedH <= 0 || clampedW < template.width || clampedH < template.height) {
            Log.w(TAG, "搜索区域(${clampedW}x${clampedH})非法或小于模板")
            return emptyResult()
        }
        val cropped = Bitmap.createBitmap(screen, clampedX, clampedY, clampedW, clampedH)
        try {
            val result = match(cropped, template, threshold, scale, grayMode)
            return if (result.confidence >= threshold) {
                result.copy(point = PointF(result.point.x + clampedX, result.point.y + clampedY))
            } else result
        } finally {
            cropped.recycle()
        }
    }

    // ==================== 搜索 ====================

    private fun search(
        screenData: FloatArray,
        templateData: FloatArray,
        channels: Int,
        sw: Int,
        sh: Int,
        tw: Int,
        th: Int,
        tSum: Double,
        tSq: Double,
    ): Candidate {
        val searchW = sw - tw + 1
        val searchH = sh - th + 1
        val step = max(2, min(tw, th) / STRIDE_DIVISOR)

        val candidates = ArrayList<Candidate>(COARSE_CANDIDATES + 1)
        var sy = 0
        while (sy < searchH) {
            var sx = 0
            while (sx < searchW) {
                val score = ncc(
                    screenData, templateData, channels, sw,
                    sx, sy, tw, th, tSum, tSq,
                )
                if (candidates.size < COARSE_CANDIDATES ||
                    score > candidates[candidates.size - 1].score
                ) {
                    candidates.add(Candidate(sx, sy, score))
                    candidates.sortByDescending { it.score }
                    if (candidates.size > COARSE_CANDIDATES) {
                        candidates.removeAt(candidates.size - 1)
                    }
                }
                sx += step
            }
            sy += step
        }

        var best = candidates.first()
        for (seed in candidates) {
            val refined = refine(
                seed, step, screenData, templateData, channels,
                sw, tw, th, searchW, searchH, tSum, tSq,
            )
            if (refined.score > best.score) best = refined
        }
        return best
    }

    /** 以 seed 为中心逐级减半步长精扫：step → step/2 → … → 1 */
    private fun refine(
        seed: Candidate,
        coarseStep: Int,
        screenData: FloatArray,
        templateData: FloatArray,
        channels: Int,
        sw: Int,
        tw: Int,
        th: Int,
        searchW: Int,
        searchH: Int,
        tSum: Double,
        tSq: Double,
    ): Candidate {
        var bx = seed.x
        var by = seed.y
        var bestScore = seed.score
        var radius = coarseStep
        while (radius > 1) {
            val stride = max(1, radius / 2)
            val x0 = max(0, bx - radius)
            val x1 = min(searchW - 1, bx + radius)
            val y0 = max(0, by - radius)
            val y1 = min(searchH - 1, by + radius)
            var yy = y0
            while (yy <= y1) {
                var xx = x0
                while (xx <= x1) {
                    val score = ncc(
                        screenData, templateData, channels, sw,
                        xx, yy, tw, th, tSum, tSq,
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bx = xx
                        by = yy
                    }
                    xx += stride
                }
                yy += stride
            }
            radius = stride
        }
        return Candidate(bx, by, bestScore)
    }

    /**
     * 单个窗口的归一化互相关：单遍同时累加 Σs、Σs²、Σs·t。
     * NCC = (n·Σst − Σs·Σt) / sqrt((n·Σs² − (Σs)²) · (n·Σt² − (Σt)²))
     */
    private fun ncc(
        screenData: FloatArray,
        templateData: FloatArray,
        channels: Int,
        sw: Int,
        sx: Int,
        sy: Int,
        tw: Int,
        th: Int,
        tSum: Double,
        tSq: Double,
    ): Float {
        var s = 0.0
        var sq = 0.0
        var st = 0.0
        val rowStride = tw * channels
        for (ty in 0 until th) {
            var si = ((sy + ty) * sw + sx) * channels
            var ti = ty * rowStride
            val end = ti + rowStride
            while (ti < end) {
                val sv = screenData[si].toDouble()
                s += sv
                sq += sv * sv
                st += sv * templateData[ti].toDouble()
                si++
                ti++
            }
        }
        val n = (tw * th * channels).toDouble()
        val num = n * st - s * tSum
        val den = sqrt((n * sq - s * s) * (n * tSq - tSum * tSum))
        if (den < 1e-6 || num <= 0.0) return 0f
        return (num / den).toFloat().coerceIn(0f, 1f)
    }

    // ==================== 像素转换 ====================

    private fun fillPixels(src: IntArray, dst: FloatArray, channels: Int) {
        if (channels == 1) {
            for (i in src.indices) dst[i] = toGray(src[i])
            return
        }
        for (i in src.indices) {
            val px = src[i]
            val base = i * 3
            dst[base] = ((px shr 16) and 0xFF).toFloat()
            dst[base + 1] = ((px shr 8) and 0xFF).toFloat()
            dst[base + 2] = (px and 0xFF).toFloat()
        }
    }

    private fun toGray(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun emptyResult() = MatchResult(0f, PointF(0f, 0f))
}
