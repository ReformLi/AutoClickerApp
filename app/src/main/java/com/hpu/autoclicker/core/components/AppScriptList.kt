package com.hpu.autoclicker.core.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 命名脚本列表 —— 代点器「脚本管理」与连点器「悬浮窗设置」共用同一套行样式。
 * 点击名称加载，右侧「改名 / 删除」；无脚本时显示空态，超高时列表自身滚动。
 */
@Composable
fun <T> AppScriptList(
    scripts: List<T>,
    nameOf: (T) -> String,
    onLoad: (T) -> Unit,
    onRename: (T) -> Unit,
    onDelete: (T) -> Unit,
    emptyTitle: String,
    emptyDescription: String,
    modifier: Modifier = Modifier,
) {
    if (scripts.isEmpty()) {
        AppEmptyState(
            title = emptyTitle,
            description = emptyDescription,
            modifier = modifier.padding(top = Dimens.space8),
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.space24 * 10)
            .padding(top = Dimens.space8)
            .verticalScroll(rememberScrollState()),
    ) {
        scripts.forEachIndexed { index, script ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = Dimens.divider,
                    color = scheme.outlineVariant,
                    modifier = Modifier.padding(vertical = Dimens.space4),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.touchMin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppBodyText(
                    text = nameOf(script),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onLoad(script) }
                        .padding(start = Dimens.space4),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "改名",
                    modifier = Modifier
                        .clickable { onRename(script) }
                        .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                )
                Text(
                    text = "删除",
                    modifier = Modifier
                        .clickable { onDelete(script) }
                        .padding(start = Dimens.space12, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space4),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.error,
                )
            }
        }
    }
}
