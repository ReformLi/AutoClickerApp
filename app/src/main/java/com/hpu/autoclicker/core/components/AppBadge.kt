package com.hpu.autoclicker.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 徽标 —— 胶囊底色 + 短文字，用于状态标识（如「已就绪」「运行中」）。
 * 语义色统一取自主题：品牌 / 成功 / 警示 / 中性四档，页面不自行配色。
 */
enum class AppBadgeTone { Primary, Success, Warning, Neutral }

@Composable
fun AppBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppBadgeTone = AppBadgeTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    val (background, foreground) = when (tone) {
        AppBadgeTone.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        AppBadgeTone.Success -> sem.successContainer to sem.onSuccessContainer
        AppBadgeTone.Warning -> sem.warningContainer to sem.onWarningContainer
        AppBadgeTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = Dimens.badgeHPadding, vertical = Dimens.badgeVPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
        )
    }
}
