package com.hpu.autoclicker.feature.quickclick

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.ui.rememberRawTapDetector
import com.hpu.autoclicker.core.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 悬浮快捷点击（新功能）悬浮窗的 Compose 内容（由 quick_floating_root.xml 迁移）
 *
 * 与旧实现差异说明：
 * - 原来通过 LinearLayout + 动态 add/removeView 构建展开区，现改为「状态驱动」的 Column：
 *   展开/编辑态、子按钮列表、拖动位移全部由 [QuickFloatingUiState] 决定，Compose 自动重组。
 * - 触摸事件仍沿用 MotionEvent 原始坐标（通过 pointerInteropFilter 直接拿到 rawX/rawY），
 *   因此拖动跟手、边缘吸附、长按排序等行为与原来保持一致。
 */
@Stable
class QuickFloatingUiState {
    /** 是否展开（显示主按钮下方的展开区） */
    var isExpanded by mutableStateOf(false)

    /** 是否处于编辑态（展开区显示 [+][脚本管理][透明度][关闭]） */
    var isEditing by mutableStateOf(false)

    /** 无障碍断连时主按钮变灰（1f 正常 / 0.4f 不可用） */
    var mainAlpha by mutableStateOf(1f)

    /** 主按钮按下态（用于背景色反馈，等价原 bg_floating_button 的 pressed 状态） */
    var mainPressed by mutableStateOf(false)

    /** 正在按下的子按钮索引（-1 表示无） */
    var pressedChildIndex by mutableStateOf(-1)

    /** 正在长按拖动排序的子按钮（引用比较；null 表示无） */
    var draggingConfig by mutableStateOf<QuickClickConfig?>(null)

    /** 拖动中的纵向位移（px），用于跟手 */
    var dragOffsetY by mutableStateOf(0f)

    /** 展开区子按钮列表（原 Service 中内存态 configs） */
    val configs = mutableStateListOf<QuickClickConfig>()
}

@Composable
fun QuickFloatingContent(
    state: QuickFloatingUiState,
    /** 主按钮原始触摸事件（单击/拖动/双击状态机在 Service 内维护） */
    onMainTouch: (MotionEvent) -> Boolean,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOpacity: () -> Unit,
    onCloseWindow: () -> Unit,
    onChildClick: (QuickClickConfig) -> Unit,
    /** 编辑态子按钮原始触摸事件（长按拖动排序） */
    onChildTouch: (index: Int, event: MotionEvent) -> Boolean,
    /** 展开区在根视图中的 Y 偏移（px），用于拖动排序时换算屏幕坐标 */
    onExpandedPositioned: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    Column(
        // 宽度改为「随内容自适应」：按钮统一收窄到 40dp 后，若窗口仍固定 120dp，
        // 吸附屏幕边缘时按钮会因水平居中而在窗口内留下约 40dp 透明间隙，看起来就不贴边了。
        // 随内容收缩后，窗口宽度 ≈ 40dp + 左右各 8dp 内边距，吸附时按钮紧贴边缘。
        modifier = Modifier
            .background(Color.Transparent)
            .padding(8.dp),
    ) {
        // 主悬浮总按钮（样式与旧功能一致：蓝底 12dp 圆角 + 白色图标）
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(40.dp)
                .graphicsLayer { alpha = state.mainAlpha }
                .pointerInteropFilter { onMainTouch(it) }
                .clip(MaterialTheme.shapes.small)
                .background(if (state.mainPressed) sem.primaryPressed else scheme.primary)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_main),
                contentDescription = "主按钮",
                modifier = Modifier.fillMaxSize(),
                // 深色模式下主按钮底为浅青，着 onPrimary 保证图标对比度
                colorFilter = ColorFilter.tint(scheme.onPrimary),
            )
        }

        // 展开区：非编辑态仅子按钮列表；编辑态为 [+] 子按钮列表 [脚本管理][透明度][关闭]
        if (state.isExpanded) {
            Column(
                // 注意：这里不能加 fillMaxWidth()。悬浮窗是 WRAP_CONTENT，fillMaxWidth 会解析为
                // 父约束的最大宽度（≈屏幕宽度），把整个窗口撑成全屏宽，按钮看起来就「只能居中」、
                // 且 lp.x 被压成 0 无法贴边。去掉后窗口宽度 = 40dp 内容 + 左右各 8dp 内边距 = 56dp。
                modifier = Modifier
                    .onGloballyPositioned { onExpandedPositioned(it.positionInRoot().y) },
                // 展开区按钮统一 40dp 见方并水平居中（与主按钮同宽）
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.isEditing) {
                    ControlRow(iconRes = R.drawable.ic_add, desc = "添加", onClick = onAdd)
                }
                state.configs.forEachIndexed { index, cfg ->
                    ChildRow(
                        cfg = cfg,
                        editing = state.isEditing,
                        pressed = state.pressedChildIndex == index,
                        dragging = state.draggingConfig === cfg,
                        dragOffsetY = if (state.draggingConfig === cfg) state.dragOffsetY else 0f,
                        onClick = { onChildClick(cfg) },
                        onTouch = { event -> onChildTouch(index, event) },
                    )
                }
                if (state.isEditing) {
                    ControlRow(iconRes = R.drawable.ic_folder, desc = "脚本管理", onClick = onOpenSettings)
                    ControlRow(iconRes = R.drawable.ic_opacity, desc = "透明度", onClick = onOpenOpacity)
                    ControlRow(iconRes = R.drawable.ic_exit, desc = "关闭", onClick = onCloseWindow)
                }
            }
        }
    }
}

/** 编辑态控制行：[+]/[脚本管理]/[透明度]/[关闭]（对应原 buildControlRow） */
@Composable
private fun ControlRow(iconRes: Int, desc: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    // 与主按钮同一条原始触摸链路（不使用 clickable，避免两条事件链路互相吞事件）
    val tap = rememberRawTapDetector()
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (tap.isPressed) sem.primaryPressed else scheme.primary)
            .pointerInteropFilter { event -> tap.onEvent(event, onTap = onClick) }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = desc,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(scheme.onPrimary),
        )
    }
}

/** 子按钮行：非编辑态点击触发识别；编辑态点击打开修改/删除弹框，长按可拖动排序（对应原 buildChildRow） */
@Composable
private fun ChildRow(
    cfg: QuickClickConfig,
    editing: Boolean,
    pressed: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onTouch: (MotionEvent) -> Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    // graphicsLayer 的 lambda 不是 Composable 上下文，shape 须在外部捕获
    val rowShape = MaterialTheme.shapes.small
    // 非编辑态用同一套原始触摸判定做点击；编辑态交给 Service 的长按拖动状态机
    val tap = rememberRawTapDetector()
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(40.dp)
            .offset { IntOffset(x = 0, y = dragOffsetY.roundToInt()) }
            .graphicsLayer {
                // 拖动中的按钮浮起（对应原 view.elevation = 6dp）
                shadowElevation = if (dragging) 6.dp.toPx() else 0f
                shape = rowShape
            }
            .clip(rowShape)
            .background(
                if (pressed || dragging || tap.isPressed) sem.primaryPressed else scheme.primary
            )
            .pointerInteropFilter { event ->
                if (editing) onTouch(event)
                else tap.onEvent(event, onTap = onClick)
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cfg.name,
            color = scheme.onPrimary,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}
