package com.hpu.autoclicker.core.ui

import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 悬浮窗几何工具：横竖屏切换或屏幕尺寸变化后，把窗口重新放回可见区域。
 *
 * 三个悬浮窗服务共用。需要注意两点：
 * - 窗口 `LayoutParams.x/y` 的原点在内容区左上角（通常位于状态栏下方），
 *   而手势坐标用的是真实屏幕，所以不能直接拿 `x/y` 与屏幕尺寸比较；
 * - 窗口比屏幕还大时 [clampOffset] / [clampInside] 直接贴住起始边，
 *   避免 `coerceIn(0, screen - view)` 在新区间为负时抛 IllegalArgumentException。
 */
object FloatingWindowBounds {

    /** 真实屏幕尺寸（含系统栏），与注入手势使用的坐标系一致 */
    fun displaySize(windowManager: WindowManager): Point {
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        if (size.x > 0 && size.y > 0) return size
        val rect = Rect()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRectSize(rect)
        return Point(rect.width(), rect.height())
    }

    /** 窗口 `x/y` 原点在屏幕上的偏移（状态栏 / 侧边导航栏占位） */
    fun originOffset(view: View, params: WindowManager.LayoutParams): Point {
        if (view.width <= 0 || view.height <= 0) return Point(0, 0)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Point(
            (loc[0] - params.x).coerceAtLeast(0),
            (loc[1] - params.y).coerceAtLeast(0),
        )
    }

    /** 窗口实际可用的屏幕区域（真实屏幕减去系统栏占位） */
    fun usableSize(windowManager: WindowManager, view: View, params: WindowManager.LayoutParams): Point {
        val display = displaySize(windowManager)
        val offset = originOffset(view, params)
        return Point(
            (display.x - offset.x).coerceAtLeast(0),
            (display.y - offset.y).coerceAtLeast(0),
        )
    }

    /**
     * 安全区（相对窗口 `LayoutParams` 坐标系），已避让状态栏、导航栏 / 手势条与挖孔，
     * 并向内缩进 [margin]，避免窗口贴死物理边缘。
     */
    fun safeBounds(
        windowManager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        margin: Int,
    ): Rect {
        val display = displaySize(windowManager)
        val offset = originOffset(view, params)
        val insets = systemInsets(view, windowManager)
        return Rect(
            (insets.left - offset.x).coerceAtLeast(0) + margin,
            (insets.top - offset.y).coerceAtLeast(0) + margin,
            (display.x - insets.right - offset.x - margin).coerceAtLeast(0),
            (display.y - insets.bottom - offset.y - margin).coerceAtLeast(0),
        )
    }

    /** 把窗口偏移夹到 [0, screenSize - viewSize]；窗口比屏幕大时固定为 0。 */
    fun clampOffset(value: Int, viewSize: Int, screenSize: Int): Int =
        value.coerceIn(0, (screenSize - viewSize).coerceAtLeast(0))

    /** 把窗口偏移夹进安全区 [min, max]（max 为右 / 下边界）；窗口比安全区大时贴住起始边。 */
    fun clampInside(value: Int, viewSize: Int, min: Int, max: Int): Int {
        val upper = (max - viewSize).coerceAtLeast(min)
        return value.coerceIn(min, upper)
    }

    /** 系统栏 / 挖孔在显示坐标系中的占位，取各来源的最大值 */
    private fun systemInsets(view: View, windowManager: WindowManager): Rect {
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        ViewCompat.getRootWindowInsets(view)?.let {
            val i = it.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            left = maxOf(left, i.left)
            top = maxOf(top, i.top)
            right = maxOf(right, i.right)
            bottom = maxOf(bottom, i.bottom)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = maxOf(left, i.left)
            top = maxOf(top, i.top)
            right = maxOf(right, i.right)
            bottom = maxOf(bottom, i.bottom)
        }
        if (bottom == 0) bottom = navigationBarFallback(view)
        return Rect(left, top, right, bottom)
    }

    /** 旧版本 / 部分 ROM 的悬浮窗拿不到 insets 时，用系统导航栏高度兜底 */
    private fun navigationBarFallback(view: View): Int {
        val landscape = view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val name = if (landscape) "navigation_bar_height_landscape" else "navigation_bar_height"
        val res = view.resources
        var id = res.getIdentifier(name, "dimen", "android")
        if (id == 0) id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }
}
