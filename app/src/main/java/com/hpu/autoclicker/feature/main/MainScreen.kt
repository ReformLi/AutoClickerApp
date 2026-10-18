package com.hpu.autoclicker.feature.main

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.theme.AppButton
import com.hpu.autoclicker.core.theme.AppOutlineButton
import com.hpu.autoclicker.core.theme.LocalAppPalette
import com.hpu.autoclicker.core.theme.MainGradientBackground
import com.hpu.autoclicker.core.theme.ModernBody
import com.hpu.autoclicker.core.theme.ModernLabel
import com.hpu.autoclicker.core.theme.ModernTitle
import com.hpu.autoclicker.core.theme.ModernCard

/**
 * 主页 UI 状态（由 MainActivity 持有并驱动刷新）
 *
 * 原来通过 findViewById 逐个 setText/setBackground 的刷新方式，
 * 迁移后统一收敛为「状态驱动」：所有文案、按钮颜色、可用性都由该状态派生。
 */
@Immutable
data class MainUiState(
    val isNewMode: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasAccessibilityPermission: Boolean = false,
    val isServiceRunning: Boolean = false,
) {
    /** 悬浮窗权限 + 无障碍权限是否都已具备 */
    val allPermissionsGranted: Boolean
        get() = hasOverlayPermission && hasAccessibilityPermission

    /** 当前功能模式名称（旧功能=连点器，新功能=代点器） */
    val modeName: String
        get() = if (isNewMode) "代点器" else "连点器"
}

/**
 * 主页面（由 activity_main.xml 迁移）
 *
 * 结构（自上而下）：图标 → 标题 → 副标题 → 状态卡片 → 获取权限 →
 * 刷新状态 → 新旧功能分段开关 → 启动/关闭悬浮窗
 */
@Composable
fun MainScreen(
    state: MainUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchMode: (toNew: Boolean) -> Unit,
    onToggleService: () -> Unit,
) {
    val palette = LocalAppPalette.current
    MainGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 应用图标：白色圆角容器衬托（对应原 ImageView + bg_card + 14dp padding）
            AppIcon()

            // 标题（对应原 XML android:text="@string/app_name"）
            ModernTitle(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(top = 16.dp),
                color = palette.onGradient,
            )

            // 副标题
            Text(
                text = "轻触悬浮窗即可自动点击",
                modifier = Modifier.padding(top = 4.dp),
                color = palette.onGradientSecondary,
                fontSize = 14.sp,
            )

            // 状态卡片
            ModernCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ModernLabel(text = "当前状态")
                    ModernBody(
                        text = statusText(state),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // 获取权限：两个权限都满足 → 绿色「权限已获取」，否则红色「获取权限」
            AppButton(
                text = if (state.allPermissionsGranted) "权限已获取" else "获取权限",
                onClick = onRequestPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                containerColor = if (state.allPermissionsGranted) palette.success else palette.danger,
                contentColor = Color.White,
            )

            // 刷新状态
            AppOutlineButton(
                text = "刷新状态",
                onClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            // 新旧功能切换：分段开关样式（选择会持久化，默认旧功能）
            ModeSwitch(
                isNewMode = state.isNewMode,
                onSwitchMode = onSwitchMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )

            // 启动/关闭悬浮窗：只有两个权限都满足才可用
            ToggleServiceButton(
                state = state,
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

/**
 * 应用图标：白底 12dp 圆角容器 + 真实启动图标
 * （等价原 XML：ImageView 72dp + bg_card 背景 + 14dp padding + android:src="@mipmap/ic_launcher"）
 *
 * 说明：@mipmap/ic_launcher 在 API 26+ 上是 adaptive-icon（自适应图标），
 * 不能用 painterResource 加载（会抛异常），也不能只取 ic_launcher_foreground，
 * 因为前景层是 108dp 画布、图形只占中间安全区，单独显示会变得又小又偏。
 * 这里改用 AndroidView + ImageView.setImageResource，与旧 XML 的 android:src 是同一条渲染路径，
 * 由系统完成「背景层 + 前景层 + 自适应遮罩」的合成，显示效果与迁移前完全一致。
 */
@Composable
private fun AppIcon() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)   // 原 bg_card：surface(#FFFFFF) + 12dp 圆角
            .padding(14.dp),
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

/** 新旧功能分段开关：半透明白轨道 + 白色滑块，选中段蓝字、未选中段白字 */
@Composable
private fun ModeSwitch(
    isNewMode: Boolean,
    onSwitchMode: (toNew: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x33FFFFFF))
            .padding(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            ModeSegment(
                text = "连点器",
                selected = !isNewMode,
                onClick = { onSwitchMode(false) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            )
            ModeSegment(
                text = "代点器",
                selected = isNewMode,
                onClick = { onSwitchMode(true) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ModeSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.surface else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) palette.primary else palette.onGradient,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 启动/关闭悬浮窗按钮：未授权时禁用置灰，运行中红色，未运行白底蓝字 */
@Composable
private fun ToggleServiceButton(
    state: MainUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    when {
        !state.allPermissionsGranted -> AppButton(
            text = "启动悬浮窗",
            onClick = onClick,
            modifier = modifier,
            enabled = false,
            disabledContainerColor = palette.disabled,
            disabledContentColor = palette.textSecondary,
        )
        state.isServiceRunning -> AppButton(
            text = "关闭悬浮窗",
            onClick = onClick,
            modifier = modifier,
            containerColor = palette.danger,
            contentColor = Color.White,
        )
        else -> AppButton(
            text = "启动悬浮窗",
            onClick = onClick,
            modifier = modifier,
            // 深蓝渐变背景上用白底蓝字，保证按钮醒目
            containerColor = Color.White,
            contentColor = palette.primary,
        )
    }
}

/** 状态提示文案（按模式区分服务名） */
private fun statusText(state: MainUiState): String = when {
    !state.allPermissionsGranted -> "请先获取所有权限"
    state.isServiceRunning -> "悬浮窗正在运行（${state.modeName}）"
    else -> "悬浮窗未启动"
}
