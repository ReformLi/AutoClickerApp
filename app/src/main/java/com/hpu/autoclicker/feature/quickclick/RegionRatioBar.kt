package com.hpu.autoclicker.feature.quickclick

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.KeyboardType
import com.hpu.autoclicker.core.components.AppDialogActions
import com.hpu.autoclicker.core.components.AppDialogPanel
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.theme.OverlayPalette
import com.hpu.autoclicker.core.ui.ServiceComposeHost
import com.hpu.autoclicker.core.ui.ServiceDialogs
import com.hpu.autoclicker.core.ui.stripPlatformPanel
import kotlin.math.roundToInt

/**
 * 裁剪比例选择条：自由 + 常用比例 + 自定义 W:H。
 *
 * 选中比例后通过 [onRatioChanged] 回调给宿主（null = 自由比例），
 * 宿主把它传给 [RegionSelectView.setAspectRatio]。
 *
 * 本控件同时用于 Service 浮层（截图）与 Activity（相册裁剪），
 * 自定义比例弹框会根据上下文自动处理窗口类型与软键盘。
 */
class RegionRatioBar(
    private val context: Context,
    private val onRatioChanged: (Float?) -> Unit,
) {

    /** 横幅（自由 + 预设），自定义单独追加在后面 */
    private val presets = listOf(
        "自由" to null,
        "1:1" to 1f,
        "4:3" to 4f / 3f,
        "3:4" to 3f / 4f,
        "16:9" to 16f / 9f,
        "9:16" to 9f / 16f,
    )

    private val chipRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private var selectedChip: TextView? = null
    private var customChip: TextView? = null

    val view: View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // 底部一条细分隔线，把比例条与下方操作栏分开
        background = LayerDrawable(
            arrayOf(
                ColorDrawable(OverlayPalette.bar.toArgb()),
                ColorDrawable(OverlayPalette.divider.toArgb()),
            )
        ).apply {
            setLayerSize(0, -1, dp(1))
            setLayerGravity(0, Gravity.BOTTOM)
        }
        addView(
            chipRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    init {
        chipRow.addView(label("比例"))
        presets.forEach { (text, ratio) ->
            val chipView = chip(text) {
                onRatioChanged(ratio)
                markSelected(it)
            }
            chipRow.addView(chipView)
            if (ratio == null) markSelected(chipView)
        }
        customChip = chip("自定义") { showCustomDialog() }.also { chipRow.addView(it) }
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(OverlayPalette.onBarMuted.toArgb())
        textSize = 12f
        setPadding(0, 0, dp(8), 0)
    }

    private fun chip(text: String, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = chipBackground(false)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick(this) }
        }

    private fun markSelected(chip: TextView) {
        selectedChip?.background = chipBackground(false)
        selectedChip?.setTextColor(Color.WHITE)
        selectedChip = chip
        chip.background = chipBackground(true)
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (selected) OverlayPalette.brand.toArgb() else OverlayPalette.chipIdle.toArgb())
    }

    /** 自定义比例：宽/高两个数字输入框（走统一弹框外框） */
    private fun showCustomDialog() {
        val host = ServiceComposeHost()
        val state = RatioInputState()
        var dialogRef: AlertDialog? = null
        val compose = ComposeView(context)
        host.attach(compose)
        compose.setContent {
            AutoClickerTheme {
                AppDialogPanel(
                    title = "自定义裁剪比例",
                    subtitle = "填写裁剪区域的宽与高（像素）",
                    actions = {
                        AppDialogActions(
                            confirmText = "应用",
                            onConfirm = {
                                val w = state.width.toIntOrNull()
                                val h = state.height.toIntOrNull()
                                if (w == null || h == null || w <= 0 || h <= 0) {
                                    Toast.makeText(
                                        context,
                                        "请输入大于 0 的宽高",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    onRatioChanged(w.toFloat() / h.toFloat())
                                    customChip?.let {
                                        it.text = "自定义 $w:$h"
                                        markSelected(it)
                                    }
                                    dialogRef?.dismiss()
                                }
                            },
                            cancelText = "取消",
                            onCancel = { dialogRef?.dismiss() },
                        )
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                        AppTextField(
                            value = state.width,
                            onValueChange = { state.width = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            hint = "宽",
                            keyboardType = KeyboardType.Number,
                        )
                        AppTextField(
                            value = state.height,
                            onValueChange = { state.height = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            hint = "高",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(context).setView(compose).create()
        dialogRef = dialog
        // Service 场景下这个自建弹框也要登记，服务销毁时统一收掉，否则会留在桌面上点不掉
        val ownerService = context as? Service
        ownerService?.let { ServiceDialogs.track(it, dialog) }

        // 窗口根视图挂宿主 Owner（必须在 show() 之前，原因见 ServiceComposeHost.attachWindowRoot）
        dialog.window?.decorView?.let { host.attachWindowRoot(it) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            host.destroy()
            ownerService?.let { ServiceDialogs.reportDismissed(it, dialog) }
        }

        // Service 浮层场景必须显式指定 overlay 窗口类型，否则 BadTokenException
        if (context !is Activity) {
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
            )
        }

        dialog.show()
        // overlay 弹框默认带 FLAG_ALT_FOCUSABLE_IM，会导致软键盘不弹
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.85f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        // 去掉平台面板底，避免与自绘圆角面板叠成双层框
        dialog.stripPlatformPanel()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
}

/** 自定义比例弹框的输入状态 */
@Stable
private class RatioInputState {
    var width by mutableStateOf("")
    var height by mutableStateOf("")
}
