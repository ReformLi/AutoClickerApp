package com.hpu.autoclicker.core.components

import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 通用输入框 —— 外观（底色/描边/圆角/字号）全部走 token，与 [AppButton]/[AppCard] 一致。
 *
 * 关键：内部仍用 [AndroidView] 包裹原生 [EditText]，而非 Compose 的 BasicTextField。
 * 因为本组件用在「Service 内 TYPE_APPLICATION_OVERLAY 弹框」，该场景下 Compose 输入框只会把
 * showSoftInput 请求发给 ComposeView，而 ComposeView 不是输入法记录的 served/focus view，
 * 请求被输入法丢弃（logcat: The current service view is not the focus view），表现为
 * 输入框有光标、能收按键但软键盘不弹。原生 EditText 走 View.requestFocus() 才能正常弹键盘。
 * 这段行为逻辑必须保留，美化只替换其「外观壳」。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLength: Int? = null,
    inputFilter: InputFilter? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val bodySize = MaterialTheme.typography.bodyLarge.fontSize.value
    val paddingPx = with(LocalDensity.current) {
        intArrayOf(Dimens.space12.roundToPx(), Dimens.space12.roundToPx())
    }
    // 下面这些值在 update 里用于「变了才赋值」的比较，必须在重组间保持稳定实例：
    // 每次重组新建 filters 数组会让比较永远成立，等于没加判断
    val filters: Array<InputFilter> = remember(maxLength, inputFilter) {
        buildList {
            if (maxLength != null) add(InputFilter.LengthFilter(maxLength))
            if (inputFilter != null) add(inputFilter)
        }.toTypedArray()
    }
    val inputType =
        if (keyboardType == KeyboardType.Number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
    val targetLines = if (singleLine) 1 else Int.MAX_VALUE
    val alignment = when (textAlign) {
        TextAlign.Center -> View.TEXT_ALIGNMENT_CENTER
        TextAlign.End -> View.TEXT_ALIGNMENT_VIEW_END
        else -> View.TEXT_ALIGNMENT_VIEW_START
    }
    val textColor = scheme.onSurface.toArgb()
    val hintColor = scheme.onSurfaceVariant.toArgb()
    val cursorColor = scheme.primary.toArgb()
    var focused by remember { mutableStateOf(false) }
    // factory 里注册的 TextWatcher 只注册一次，用 rememberUpdatedState 回调最新 onValueChange
    val currentOnValueChange by rememberUpdatedState { raw: String ->
        onValueChange(if (maxLength != null && raw.length > maxLength) raw.take(maxLength) else raw)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.fieldHeight)
            .background(
                // 未聚焦：浅色底 + 淡描边；聚焦：纯白底 + 品牌色描边（层次更清晰）
                color = if (focused) scheme.surface else scheme.surfaceContainerLow,
                shape = shape,
            )
            .border(
                width = if (focused) Dimens.strokeFocused else Dimens.strokeThin,
                color = if (focused) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .padding(horizontal = Dimens.space4),
        contentAlignment = Alignment.CenterStart,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    // 原 EditText 自带下划线背景，置空，底色/描边由外层 Box 绘制
                    background = null
                    setPadding(paddingPx[0], paddingPx[1], paddingPx[0], paddingPx[1])
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setSingleLine(singleLine)
                    setOnFocusChangeListener { _, hasFocus -> focused = hasFocus }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            currentOnValueChange(s?.toString() ?: "")
                        }
                    })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        textCursorDrawable?.setTint(cursorColor)
                    }
                }
            },
            update = { et ->
                // 每个属性都「变了才赋值」：无条件重设 inputType / filters / 对齐方式等会让输入法
                // 重启合成态，Android 15+ 上表现为打字时光标被弹回文字最前面
                if (et.textSize != bodySize) et.textSize = bodySize
                if (et.isEnabled != enabled) et.isEnabled = enabled
                if (et.currentTextColor != textColor) et.setTextColor(textColor)
                if (et.hintTextColors.defaultColor != hintColor) et.setHintTextColor(hintColor)
                if (et.hint?.toString() != hint) et.hint = hint
                val editable = !readOnly
                if (et.isFocusable != editable) et.isFocusable = editable
                if (et.isFocusableInTouchMode != editable) et.isFocusableInTouchMode = editable
                if (et.isCursorVisible != editable) et.isCursorVisible = editable
                if (et.inputType != inputType) et.inputType = inputType
                // setSingleLine 会重设 inputType 的多行标记，同样只在需要时调用；
                // 不能用 isSingleLine 取值（API 29+），改看 maxLines 是否已是目标行数
                if (et.maxLines != targetLines) et.setSingleLine(singleLine)
                if (!et.filters.contentEquals(filters)) et.filters = filters
                if (et.textAlignment != alignment) et.textAlignment = alignment

                // 外部状态回灌：内容一致时绝不 setText（setText 会重置选区）。
                // 不一致时（初始化回填 / 取点回填 / 名称超长截断）保留原选区，
                // 只有未聚焦的外部写入才把光标放到末尾
                if (et.text.toString() != value) {
                    val selStart = et.selectionStart
                    val selEnd = et.selectionEnd
                    et.setText(value)
                    // setText 会先过 InputFilter，实际文本可能比 value 短；选区必须按实际长度夹取，
                    // 否则「旧名超长被滤镜截短」时 setSelection 会抛 IndexOutOfBounds
                    val length = et.text.length
                    if (et.hasFocus() && selStart >= 0 && selEnd >= 0) {
                        et.setSelection(
                            selStart.coerceIn(0, length),
                            selEnd.coerceIn(0, length),
                        )
                    } else {
                        et.setSelection(length)
                    }
                }
            },
        )
    }
}
