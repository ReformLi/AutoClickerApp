package com.hpu.autoclicker.core.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 通用按钮 —— 全项目唯一按钮出口，五种视觉变体 + 四态（默认/按下/禁用/加载）。
 *
 * 规格全部来自 token：高度 [Dimens.buttonHeight]、圆角 [MaterialTheme.shapes]（small）、
 * 文字 [MaterialTheme.typography.labelLarge]、内边距 [Dimens.buttonHPadding]。
 * 按下/悬浮反馈由 Material3 组件自带 ripple 提供，不手写、不加额外动画。
 *
 * 变体语义：
 * - Filled：主操作（品牌色实心）
 * - Outlined：次要操作（白底 + 细描边，浮在浅色背景上依然清晰）
 * - Text：弱化操作（无底色）
 * - Danger：破坏性操作（关闭 / 删除）
 * - DangerText：破坏性操作的弱化形态（红色文字、无底色，用于弹框底部的删除入口）
 * - Success：完成态（权限就绪等）
 */
enum class AppButtonVariant { Filled, Outlined, Text, Danger, DangerText, Success }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Filled,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillMaxWidth: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    val shape = MaterialTheme.shapes.small
    // 加载中强制禁用点击，避免出现「转圈同时还能点」
    val finalEnabled = enabled && !loading
    val baseModifier = modifier
        .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
        .height(Dimens.buttonHeight)
    val contentPadding = PaddingValues(horizontal = Dimens.buttonHPadding, vertical = 0.dp)

    val label: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconSm),
                    strokeWidth = 2.dp,
                    color = scheme.onPrimary,
                )
                Spacer(Modifier.width(Dimens.space8))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }

    when (variant) {
        AppButtonVariant.Filled -> Button(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                disabledContainerColor = scheme.surfaceContainerHighest,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }

        AppButtonVariant.Danger -> Button(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.error,
                contentColor = scheme.onError,
                disabledContainerColor = scheme.surfaceContainerHighest,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }

        AppButtonVariant.Success -> Button(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = sem.success,
                contentColor = sem.onSuccess,
                disabledContainerColor = scheme.surfaceContainerHighest,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }

        AppButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            // 白底 + 细描边：浮在浅色背景上仍能看清轮廓
            border = BorderStroke(
                Dimens.strokeThin,
                if (finalEnabled) scheme.outlineVariant else scheme.surfaceContainerHighest,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = scheme.surface,
                contentColor = scheme.primary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }

        AppButtonVariant.Text -> TextButton(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = scheme.primary,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }

        AppButtonVariant.DangerText -> TextButton(
            onClick = onClick,
            modifier = baseModifier,
            enabled = finalEnabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = scheme.error,
                disabledContentColor = scheme.outline,
            ),
        ) { label() }
    }
}
