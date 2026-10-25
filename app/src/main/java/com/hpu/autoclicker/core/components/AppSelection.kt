package com.hpu.autoclicker.core.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 单选行 —— 左侧 RadioButton + 文字，整行可点。选中态取 [MaterialTheme.colorScheme.primary]。
 * 触控高度不低于 [Dimens.touchMin]。替代旧 `core.ui.LabeledRadio`。
 */
@Composable
fun AppRadioButtonRow(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = Dimens.touchMin)
            .clickable(enabled = enabled) { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = scheme.primary,
                unselectedColor = scheme.onSurfaceVariant,
            ),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = Dimens.space8),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

/**
 * 复选行 —— 左侧 Checkbox + 文字，整行可切换。用于「随机坐标偏移」这类布尔选项。
 */
@Composable
fun AppCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchMin)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = scheme.primary,
                checkmarkColor = scheme.onPrimary,
            ),
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = Dimens.space8),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}
