package com.hpu.autoclicker.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.autoclicker.core.theme.LocalAppPalette

/**
 * 共享小控件：单选行（对应 XML 中的 RadioButton + label）。
 * 被 feature/clicker 的点击点设置弹框与 feature/quickclick 的新建按钮弹框共同使用。
 */
@Composable
internal fun LabeledRadio(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled) { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.primary,
                unselectedColor = palette.textHint,
                disabledSelectedColor = palette.primary,
                disabledUnselectedColor = palette.textHint,
            ),
        )
        Text(
            text = text,
            color = if (enabled) palette.textPrimary else palette.textSecondary,
            fontSize = 16.sp,
        )
    }
}
