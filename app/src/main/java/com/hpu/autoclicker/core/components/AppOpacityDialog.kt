package com.hpu.autoclicker.core.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.hpu.autoclicker.core.theme.Dimens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 透明度弹框正文 —— 代点器与连点器共用同一页面。
 *
 * 代点器只有悬浮窗一个可调对象；连点器额外传入 [AppOpacityDialogContent] 的
 * onPointAlphaChange，以第二根滑块调节点击 / 滑动点位的不透明度。
 * 两边各自持久化自己的值，互不影响。
 */
@Stable
class AppOpacityDialogState {
    var alpha by mutableStateOf(1f)
    var pointAlpha by mutableStateOf(1f)
}

/**
 * @param onPointAlphaChange 非空时显示「点击 / 滑动点位」滑块（仅连点器）
 * @param onDraggingChange 拖动开始 / 结束时回调（true 带当前值），宿主据此隐藏弹框并显示预览条
 */
@Composable
fun AppOpacityDialogContent(
    state: AppOpacityDialogState,
    minAlpha: Float,
    onAlphaChange: (Float) -> Unit,
    onPointAlphaChange: ((Float) -> Unit)? = null,
    onDraggingChange: (dragging: Boolean, value: Float) -> Unit = { _, _ -> },
) {
    val showPointSlider = onPointAlphaChange != null
    Column(modifier = Modifier.fillMaxWidth()) {
        AppCaption(text = "拖动滑块实时预览，拖动时弹框会暂时隐藏；点「确定」保存")
        OpacitySlider(
            label = if (showPointSlider) "悬浮窗" else null,
            value = state.alpha,
            minAlpha = minAlpha,
            onChange = {
                state.alpha = it
                onAlphaChange(it)
            },
            onDraggingChange = onDraggingChange,
        )
        if (onPointAlphaChange != null) {
            OpacitySlider(
                label = "点击 / 滑动点位",
                value = state.pointAlpha,
                minAlpha = minAlpha,
                onChange = {
                    state.pointAlpha = it
                    onPointAlphaChange(it)
                },
                onDraggingChange = onDraggingChange,
                modifier = Modifier.padding(top = Dimens.space16),
            )
        }
    }
}

@Composable
private fun OpacitySlider(
    label: String?,
    value: Float,
    minAlpha: Float,
    onChange: (Float) -> Unit,
    onDraggingChange: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val currentValue by rememberUpdatedState(value)

    // 只在「开始拖动 / 松手」时切换宿主弹框的显示，拖动过程中的数值由 onChange 实时刷新；
    // 先等一小会儿再隐藏，避免轻点滑杆时弹框闪一下
    LaunchedEffect(dragged) {
        if (dragged) {
            delay(120)
            currentOnDraggingChange(true, currentValue)
        } else {
            currentOnDraggingChange(false, currentValue)
        }
    }

    val percent = (value * 100).roundToInt()
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            AppFieldLabel(
                text = label,
                modifier = Modifier.padding(top = Dimens.space12),
            )
        }
        AppSlider(
            value = value,
            onValueChange = onChange,
            valueRange = minAlpha..1f,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space8),
        )
        Text(
            text = "$percent%",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
