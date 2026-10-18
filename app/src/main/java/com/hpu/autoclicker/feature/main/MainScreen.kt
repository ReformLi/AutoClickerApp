package com.hpu.autoclicker.feature.main

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppCard
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppSegmentedControl
import com.hpu.autoclicker.core.components.AppTitle
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
 * 主页面（UI 美化：去蓝紫渐变，改为纯色表面 + 品牌色调 Hero 卡片；组件全部 token 化）。
 *
 * 结构（自上而下）：Hero（图标+标题+副标题） → 当前状态卡片 → 获取权限 →
 * 刷新状态 → 连点器/代点器分段开关 → 启动/关闭悬浮窗。
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
                .padding(horizontal = Dimens.screenHMargin, vertical = Dimens.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero：品牌色调卡片承载应用身份（替代原全屏渐变）
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
                        text = "轻触悬浮窗即可自动点击",
                        modifier = Modifier.padding(top = Dimens.space4),
                        color = scheme.onPrimaryContainer,
                    )
                }
            }

            // 当前状态卡片
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space24),
            ) {
                AppCaption(text = "当前状态")
                AppBodyText(
                    text = statusText(state),
                    modifier = Modifier.padding(top = Dimens.space8),
                )
            }

            // 获取权限：已具备 → Success 绿态，否则 Danger 红态
            AppButton(
                text = if (state.allPermissionsGranted) "权限已获取" else "获取权限",
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space24),
                variant = if (state.allPermissionsGranted) AppButtonVariant.Success else AppButtonVariant.Danger,
            )

            // 刷新状态（次要操作，弱化）
            AppButton(
                text = "刷新状态",
                onClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space8),
                variant = AppButtonVariant.Outlined,
            )

            // 连点器 / 代点器 分段开关（选择持久化，默认连点器）
            AppSegmentedControl(
                options = listOf("连点器", "代点器"),
                selectedIndex = if (state.isNewMode) 1 else 0,
                onSelect = { index -> onSwitchMode(index == 1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space24),
            )

            // 启动 / 关闭悬浮窗：未授权禁用，运行中红色「关闭」，否则品牌色「启动」
            ToggleServiceButton(
                state = state,
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space12),
            )
        }
    }
}

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
            .size(Dimens.iconLg + Dimens.iconLg)
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

/** 状态提示文案（按模式区分服务名） */
private fun statusText(state: MainUiState): String = when {
    !state.allPermissionsGranted -> "请先获取所有权限"
    state.isServiceRunning -> "悬浮窗正在运行（${state.modeName}）"
    else -> "悬浮窗未启动"
}
