package com.hpu.autoclicker.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 通用卡片容器 —— 用色调层次（[MaterialTheme.colorScheme.surfaceContainer]）区分前后景，
 * 不叠加阴影。圆角走 [MaterialTheme.shapes]（medium）、内边距走 [Dimens.cardPadding]。
 *
 * 传入 [onClick] 时整卡可点击并自带 ripple（统一「可点卡片」的交互反馈）。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Dimens.cardPadding),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val clickableModifier = if (onClick != null) {
        Modifier.clip(shape).clickable(onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(clickableModifier)
            .background(containerColor, shape)
            .padding(contentPadding),
        content = content,
    )
}
