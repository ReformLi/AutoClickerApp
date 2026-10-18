package com.hpu.autoclicker.core.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * 语义文本 —— 全站文字只通过这里排版，字号/字重全部来自 [MaterialTheme.typography]，
 * 页面不再写 fontSize / fontWeight。颜色默认取 ColorScheme 前景色，可覆盖。
 */

/** 页面主标题（headlineSmall）。 */
@Composable
fun AppTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.headlineSmall, color = color)
}

/** 卡片 / 分组标题（titleMedium）。 */
@Composable
fun AppSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.titleMedium, color = color)
}

/** 正文（bodyMedium）。 */
@Composable
fun AppBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 辅助说明 / caption（bodySmall，次要前景）。 */
@Composable
fun AppCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}
