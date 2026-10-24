package com.hpu.autoclicker.feature.main

import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.components.AppBadge
import com.hpu.autoclicker.core.components.AppBadgeTone
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppCard
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppPillButton
import com.hpu.autoclicker.core.components.AppSegmentedControl
import com.hpu.autoclicker.core.components.AppSectionTitle
import com.hpu.autoclicker.core.components.AppTitle
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 主页 UI 状态（由 MainActivity 持有并驱动刷新）。
 *
 * 纯状态数据，业务不变：所有文案、按钮颜色、可用性由该状态派生。
 */
@Immutable
data class MainUiState(
    val isNewMode: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val isServiceRunning: Boolean = false,
) {
    val allPermissionsGranted: Boolean
        get() = hasOverlayPermission && hasAccessibilityPermission

    val modeName: String
        get() = if (isNewMode) "代点器" else "连点器"
}

/**
 * 主页面。
 *
 * 结构（自上而下，统一的「分区 + 卡片」层次）：
 * Hero（图标 + 应用名 + 当前模式说明） → 权限与状态（标题 + 就绪徽标 + 行尾「刷新」胶囊按钮，
 * 下方卡片三行状态 + 条件显示的「获取权限」按钮）→ 运行模式（分段开关）
 * → 底部主操作（启动/关闭，平铺占满整行）。
 *
 * 视觉规则：页面底色用 background，卡片用 surface + 细描边，
 * 状态用「圆点 + 徽标」表达，避免大块高饱和色块抢占注意力。
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchMode: (toNew: Boolean) -> Unit,
    onToggleService: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenHMargin, vertical = Dimens.screenVMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroCard(state)

            // ===== 权限与状态 =====
            // 就绪徽标左移到标题右侧；行尾原徽标位置改为紧凑的「刷新」按钮。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.sectionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppSectionTitle(text = "权限与状态")
                AppBadge(
                    text = if (state.allPermissionsGranted) "已就绪" else "待授权",
                    tone = if (state.allPermissionsGranted) AppBadgeTone.Success else AppBadgeTone.Warning,
                    modifier = Modifier.padding(start = Dimens.space8),
                )
                Spacer(Modifier.weight(1f))
                AppPillButton(text = "刷新", onClick = onRefresh)
            }
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.blockGap),
                border = BorderStroke(Dimens.strokeThin, scheme.outlineVariant),
            ) {
                StatusRow(
                    title = "悬浮窗权限",
                    ok = state.hasOverlayPermission,
                    okText = "已授权",
                    badText = "未授权",
                )
                StatusDivider()
                StatusRow(
                    title = "无障碍服务",
                    ok = state.hasAccessibilityPermission,
                    okText = "已开启",
                    badText = "未开启",
                )
                StatusDivider()
                StatusRow(
                    title = "悬浮窗运行",
                    ok = state.isServiceRunning,
                    okText = "运行中",
                    badText = "未启动",
                )
            }
            // 权限齐备时不再重复按钮，状态卡片已经说明一切
            if (!state.allPermissionsGranted) {
                AppButton(
                    text = "获取权限",
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.blockGap),
                    variant = AppButtonVariant.Filled,
                )
            }

            // ===== 运行模式 =====
            // 标题与「权限与状态」同款（AppSectionTitle）靠左，行尾放当前模式徽标；
            // 下方分段开关与标题保持小间距成组
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.sectionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppSectionTitle(text = "运行模式")
                Spacer(Modifier.weight(1f))
                AppBadge(
                    text = state.modeName,
                    tone = AppBadgeTone.Primary,
                )
            }
            AppSegmentedControl(
                options = listOf("连点器", "代点器"),
                selectedIndex = if (state.isNewMode) 1 else 0,
                onSelect = { index -> onSwitchMode(index == 1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.fieldGap),
            )

            // ===== 主操作 =====
            // 刷新按钮已移到标题行，底部主操作平铺占满整行
            ToggleServiceButton(
                state = state,
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.sectionGap),
            )
        }
    }
}

/** Hero 卡片：品牌色底承载应用身份与当前模式说明 */
@Composable
private fun HeroCard(state: MainUiState) {
    val scheme = MaterialTheme.colorScheme
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = scheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon()
            AppTitle(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(top = Dimens.space12),
                color = scheme.onPrimaryContainer,
            )
            AppCaption(
                text = "${state.modeName} · ${modeDescription(state)}",
                modifier = Modifier.padding(top = Dimens.space4),
                color = scheme.onPrimaryContainer,
            )
        }
    }
}

/** 当前模式的一句话说明 */
private fun modeDescription(state: MainUiState): String =
    if (state.isNewMode) "识别文字/图像/坐标后进行点击" else "按点位循环连点"

/**
 * 应用图标：表面色圆角容器 + 真实启动图标。
 *
 * @mipmap/ic_launcher 在 API 26+ 是 adaptive-icon，不能用 painterResource 加载；
 * 沿用 AndroidView + ImageView.setImageResource 这条系统合成路径（背景层+前景层+遮罩），
 * 与迁移前一致。圆角走 token、去掉阴影（层次靠 Hero 卡片本身）。
 */
@Composable
private fun AppIcon() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(Dimens.iconLg + Dimens.space32)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surface)
            .padding(Dimens.space12),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "应用图标"
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 状态行：左侧状态圆点 + 名称 + 右侧状态徽标 */
@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.statusDot)
                .clip(CircleShape)
                .background(if (ok) sem.success else scheme.outlineVariant),
        )
        AppBodyText(
            text = title,
            modifier = Modifier
                .padding(start = Dimens.space12)
                .weight(1f),
        )
        AppBadge(
            text = if (ok) okText else badText,
            tone = if (ok) AppBadgeTone.Success else AppBadgeTone.Neutral,
        )
    }
}

/** 状态行之间的细分隔线 */
@Composable
private fun StatusDivider() {
    HorizontalDivider(
        thickness = Dimens.divider,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 启动/关闭悬浮窗按钮：状态派生变体与可用性 */
@Composable
private fun ToggleServiceButton(
    state: MainUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !state.allPermissionsGranted -> AppButton(
            text = "启动悬浮窗",
            onClick = onClick,
            modifier = modifier,
            variant = AppButtonVariant.Filled,
            enabled = false,
        )
        state.isServiceRunning -> AppButton(
            text = "关闭悬浮窗",
            onClick = onClick,
            modifier = modifier,
            variant = AppButtonVariant.Danger,
        )
        else -> AppButton(
            text = "启动悬浮窗",
            onClick = onClick,
            modifier = modifier,
            variant = AppButtonVariant.Filled,
        )
    }
}
