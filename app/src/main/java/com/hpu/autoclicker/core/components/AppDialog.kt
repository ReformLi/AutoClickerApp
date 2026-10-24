package com.hpu.autoclicker.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 统一弹框面板 —— 全项目弹框的唯一外框。
 *
 * 所有弹框（新建/编辑按钮、脚本管理、透明度、悬浮窗设置、点击点设置、确认框…）
 * 都套这一层外壳，标题、说明、正文、按钮的排版与间距因此天然一致：
 *
 * ```
 * ╭──────────────────────────────╮
 * │  标题                         │  标题区（20sp 半粗）
 * │  说明文字（可选）              │  13sp 次要色
 * ├──────────────────────────────┤  1dp 分隔线
 * │                              │
 * │  正文（超高时内部滚动）        │
 * │                              │
 * ├──────────────────────────────┤  1dp 分隔线
 * │  [可选次要]  [取消]   [主操作] │  按钮区
 * ╰──────────────────────────────╯
 * ```
 *
 * 面板自身圆角走 [MaterialTheme.shapes.large]、底色走 surface，
 * 因此宿主窗口需把背景设为透明（否则平台面板会与自绘面板叠成两层）。
 * 整体高度上限取屏幕 72%，超出部分由正文区内部滚动，标题与按钮始终可见。
 */
@Composable
fun AppDialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(scheme.surface, MaterialTheme.shapes.large),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = Dimens.dialogHPadding,
                vertical = Dimens.space16,
            ),
        ) {
            AppDialogTitle(text = title)
            if (subtitle != null) {
                AppCaption(text = subtitle, modifier = Modifier.padding(top = Dimens.space4))
            }
        }
        HorizontalDivider(thickness = Dimens.divider, color = scheme.outlineVariant)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimens.dialogHPadding,
                    vertical = Dimens.dialogVPadding,
                ),
            content = content,
        )
        if (actions != null) {
            HorizontalDivider(thickness = Dimens.divider, color = scheme.outlineVariant)
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimens.dialogHPadding,
                    vertical = Dimens.space12,
                ),
                content = actions,
            )
        }
    }
}

/**
 * 弹框底部按钮行 —— 取消（描边）在左、主操作（实心）在右，
 * 可在最左侧插入一个危险/弱化操作（如「删除」）。
 *
 * 按钮一律走 [AppButton]：主操作有品牌底色，次要操作白底描边，与主页按钮同款。
 */
@Composable
fun AppDialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = "取消",
    onCancel: (() -> Unit)? = null,
    confirmVariant: AppButtonVariant = AppButtonVariant.Filled,
    leading: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Dimens.space8))
        }
        if (cancelText != null && onCancel != null) {
            AppButton(
                text = cancelText,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                variant = AppButtonVariant.Outlined,
            )
        }
        AppButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            variant = confirmVariant,
        )
    }
}

/**
 * 确认类弹框的正文 —— 一段说明文字。
 * 危险操作（删除/覆盖）由调用方把主按钮换成 [AppButtonVariant.Danger]。
 */
@Composable
fun AppConfirmMessage(text: String, modifier: Modifier = Modifier) {
    AppBodyText(text = text, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
