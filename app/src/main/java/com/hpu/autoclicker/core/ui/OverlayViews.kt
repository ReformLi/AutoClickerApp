package com.hpu.autoclicker.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.OverlayPalette
import kotlin.math.roundToInt

/**
 * 原生浮层按钮 —— 截图框选 / 相册裁剪这类原生 View 浮层画在 Canvas / Drawable 上，
 * 读不到 Compose 的 ColorScheme，因此这里单独给一套与 Compose 侧按钮同款的视觉：
 *
 * - 12dp 圆角（与 [com.hpu.autoclicker.core.components.AppButton] 一致）
 * - 主操作：品牌实心底 + 白字；次要操作：半透明底 + 细描边 + 白字
 * - 文字 15sp（对应 typography.labelLarge）
 *
 * 用 [TextView] 而非系统 [android.widget.Button]：系统 Button 自带 Material 主题背景，
 * 圆角与内边距不可控，会与主页按钮产生两套风格。
 */
internal fun createOverlayButton(
    context: Context,
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
): TextView = TextView(context).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = 15f
    gravity = Gravity.CENTER
    isClickable = true
    isFocusable = true
    val density = resources.displayMetrics.density
    background = GradientDrawable().apply {
        cornerRadius = (12 * density).roundToInt().toFloat()
        if (filled) {
            setColor(OverlayPalette.brand.toArgb())
        } else {
            setColor(OverlayPalette.chipIdle.toArgb())
            setStroke(
                (1 * density).roundToInt(),
                OverlayPalette.onBarMuted.toArgb(),
            )
        }
    }
    setOnClickListener { onClick() }
}

/**
 * 浮层底部操作栏底色：半透明黑 + 顶部 1dp 分隔线，
 * 把操作栏与上方内容（裁剪画布 / 比例条）清楚分开。
 */
internal fun createOverlayBarBackground(context: Context): LayerDrawable {
    val density = context.resources.displayMetrics.density
    return LayerDrawable(
        arrayOf(
            ColorDrawable(OverlayPalette.bar.toArgb()),
            ColorDrawable(OverlayPalette.divider.toArgb()),
        )
    ).apply {
        setLayerSize(0, -1, (1 * density).roundToInt())
        setLayerGravity(0, Gravity.TOP)
    }
}
