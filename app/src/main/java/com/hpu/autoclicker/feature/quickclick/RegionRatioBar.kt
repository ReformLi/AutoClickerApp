package com.hpu.autoclicker.feature.quickclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        setBackgroundColor(0xCC000000.toInt())
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
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 12f
        setPadding(0, 0, dp(8), 0)
    }

    private fun chip(text: String, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
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
        selectedChip = chip
        chip.background = chipBackground(true)
    }

    private fun chipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (selected) 0xFF0F6E7D.toInt() else 0x33FFFFFF)
    }

    /** 自定义比例：宽/高两个数字输入框 */
    private fun showCustomDialog() {
        val inputs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val widthInput = numberInput("宽")
        val heightInput = numberInput("高")
        val colon = TextView(context).apply {
            text = " : "
            textSize = 16f
            gravity = Gravity.CENTER
        }
        inputs.addView(widthInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputs.addView(colon)
        inputs.addView(heightInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val dialog = AlertDialog.Builder(context)
            .setTitle("自定义裁剪比例（宽 : 高）")
            .setView(inputs)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create()

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

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w = widthInput.text.toString().toIntOrNull()
                val h = heightInput.text.toString().toIntOrNull()
                if (w == null || h == null || w <= 0 || h <= 0) {
                    Toast.makeText(context, "请输入大于 0 的宽高", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ratio = w.toFloat() / h.toFloat()
                onRatioChanged(ratio)
                customChip?.let {
                    it.text = "自定义 $w:$h"
                    markSelected(it)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        if (context !is Activity) {
            // overlay 弹框默认带 FLAG_ALT_FOCUSABLE_IM，会导致软键盘不弹
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            val dm = context.resources.displayMetrics
            dialog.window?.setLayout(
                (dm.widthPixels * 0.85f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun numberInput(hint: String): EditText = EditText(context).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4))
        textSize = 16f
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()
}
