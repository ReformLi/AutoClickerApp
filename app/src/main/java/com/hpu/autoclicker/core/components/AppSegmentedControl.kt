package com.hpu.autoclicker.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 分段单选控件 —— 用于少量互斥选项（如「连点器 / 代点器」模式切换）。
 *
 * 轨道用 [MaterialTheme.colorScheme.surfaceContainer] + 淡描边，选中段填充 primary、文字 onPrimary；
 * 整行高度 [Dimens.buttonHeight]，每段可点区域均分且 ≥ 触控要求。
 */
@Composable
fun AppSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 轨道与分段都用 background(color, shape) 画圆角，不加 Modifier.clip：
    // clip 会连带裁掉点击分发，段的圆角处会变成点不动的死区。
    Row(
        modifier = modifier
            .height(Dimens.buttonHeight)
            .background(scheme.surfaceContainer, MaterialTheme.shapes.small)
            .border(Dimens.strokeThin, scheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(Dimens.space4),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (selected) scheme.primary else Color.Transparent,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}
