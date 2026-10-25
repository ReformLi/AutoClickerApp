package com.hpu.autoclicker.core.ui

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams

/**
 * 全面屏适配：把整屏铺开的页面 / 浮层里的「底部操作栏」抬到导航栏（手势条）之上。
 *
 * 判据是操作栏底边有没有触到屏幕底边：
 * - 触到了 → 窗口铺到了导航栏之下，底部按钮会被导航栏压住，按导航栏高度整体上移；
 * - 没触到 → 窗口本身已经避让了导航栏，保持原样，不重复留白。
 *
 * 因此同一段逻辑对「铺满整屏的浮层」与「窗口已避让系统栏的 Activity」都成立。
 */
internal fun liftBottomBarAboveNavigationBar(bar: View) {
    bar.doOnLayout {
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        if (loc[1] + bar.height < screenHeight(bar.context)) return@doOnLayout
        val navHeight = navigationBarHeight(bar)
        if (navHeight <= 0) return@doOnLayout
        bar.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = navHeight
        }
    }
}

/** 屏幕真实高度（px），含状态栏与导航栏区域 */
private fun screenHeight(context: Context): Int {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Suppress("DEPRECATION")
    val size = Point()
    wm.defaultDisplay.getRealSize(size)
    return size.y
}

/** 底部导航栏 / 手势条占用的高度（px） */
private fun navigationBarHeight(view: View): Int {
    val fromWindow = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.systemBars())
    if (fromWindow != null && fromWindow.bottom > 0) return fromWindow.bottom

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = view.context.getSystemService(WindowManager::class.java)
        val bottom = wm.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.navigationBars()).bottom
        if (bottom > 0) return bottom
    }
    // 回退：系统导航栏高度资源（框架未把 navigation_bar_height 暴露为公开常量）
    val resId = view.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (resId > 0) view.resources.getDimensionPixelSize(resId) else 0
}
