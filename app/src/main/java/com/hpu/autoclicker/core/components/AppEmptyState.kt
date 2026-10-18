package com.hpu.autoclicker.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 空状态 —— 列表/面板无内容时的统一占位：居中留白 + 可选图标 + 标题 + 可选说明。
 * 当前用于「脚本管理」的空列表提示，替代散落的裸 Text。
 *
 * 图标为可选入参且不内置默认图标资源（项目未引入 material-icons-extended），
 * 调用方可自行传入已有的 ImageVector；不传则仅显示文字。
 */
@Composable
fun AppEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(vertical = Dimens.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconLg),
                tint = scheme.outline,
            )
            Spacer(Modifier.height(Dimens.space8))
        }
        AppCaption(text = title, color = scheme.onSurfaceVariant)
        if (description != null) {
            Spacer(Modifier.height(Dimens.space4))
            AppCaption(text = description, color = scheme.outline)
        }
    }
}
