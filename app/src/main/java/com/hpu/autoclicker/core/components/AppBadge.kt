package com.hpu.autoclicker.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 徽标 —— 胶囊底色 + 短文字，用于状态标识（如「已就绪」「运行中」）。
 * 语义色统一取自主题：品牌 / 成功 / 警示 / 中性四档，页面不自行配色。
 */
enum class AppBadgeTone { Primary, Success, Warning, Neutral }

/** 色调对应的「背景 / 前景」配色 */
@Composable
private fun AppBadgeTone.colors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    return when (this) {
        AppBadgeTone.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        AppBadgeTone.Success -> sem.successContainer to sem.onSuccessContainer
        AppBadgeTone.Warning -> sem.warningContainer to sem.onWarningContainer
        AppBadgeTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}

@Composable
fun AppBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: AppBadgeTone = AppBadgeTone.Neutral,
) {
    val (background, foreground) = tone.colors()
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

/**
 * 紧凑胶囊按钮 —— 尺寸与 [AppBadge] 完全一致（同字号同内边距），
 * 用于标题行等空间有限处的操作（如主页标题行右侧的「刷新」）。
 * 品牌底色 + 白字，明确可点；禁用态转灰。
 */
@Composable
fun AppPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (enabled) scheme.primary else scheme.surfaceContainerHighest)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = scheme.onPrimary),
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Dimens.badgeHPadding, vertical = Dimens.badgeVPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) scheme.onPrimary else scheme.outline,
        )
    }
}
