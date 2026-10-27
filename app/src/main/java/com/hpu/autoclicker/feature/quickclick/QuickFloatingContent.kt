package com.hpu.autoclicker.feature.quickclick

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.ui.FloatingSquareButton
import com.hpu.autoclicker.core.ui.rememberRawTapDetector
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

    /** 拖动中的横向位移（px）；横排展开栏排序时使用 */
    var dragOffsetX by mutableStateOf(0f)

    /**
     * 展开栏方向：false = 主按钮下方竖排，true = 主按钮右侧横排。
     * 竖排在当前屏幕高度放不下时由 Service 切到横排（横屏 / 小窗口场景）。
     */
    var horizontal by mutableStateOf(false)

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
    /**
     * 运行态子按钮原始触摸事件：点击执行配置动作，长按 / 滑动透传给下层应用。
     * 判定放在 Service 里，因为透传需要手势轨迹和窗口可触摸性切换。
     */
    onChildRunTouch: (index: Int, event: MotionEvent) -> Boolean,
    /** 编辑态子按钮原始触摸事件（长按拖动排序） */
    onChildTouch: (index: Int, event: MotionEvent) -> Boolean,
    /** 展开区在根视图中的位置（px），用于拖动排序时换算屏幕坐标 */
    onExpandedPositioned: (left: Float, top: Float) -> Unit,
) {
    if (state.horizontal) {
        Row(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickMainButton(state = state, onMainTouch = onMainTouch)
            if (state.isExpanded) {
                Row(
                    modifier = Modifier.onGloballyPositioned {
                        val pos = it.positionInRoot()
                        onExpandedPositioned(pos.x, pos.y)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpandedItems(
                        state = state,
                        onAdd = onAdd,
                        onOpenSettings = onOpenSettings,
                        onOpenOpacity = onOpenOpacity,
                        onCloseWindow = onCloseWindow,
                        onChildRunTouch = onChildRunTouch,
                        onChildTouch = onChildTouch,
                        horizontal = true,
                    )
                }
            }
        }
    } else {
        Column(
            // 宽度改为「随内容自适应」：按钮统一收窄到 40dp 后，若窗口仍固定 120dp，
            // 吸附屏幕边缘时按钮会因水平居中而在窗口内留下约 40dp 透明间隙，看起来就不贴边了。
            // 随内容收缩后，窗口宽度 ≈ 40dp + 左右各 8dp 内边距，吸附时按钮紧贴边缘。
            modifier = Modifier
                .background(Color.Transparent)
                .padding(8.dp),
        ) {
            // 主悬浮总按钮（40dp 品牌底圆角方块 + 白色图标，与连点器悬浮窗同一套样式）
            QuickMainButton(
                state = state,
                onMainTouch = onMainTouch,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            // 展开区：非编辑态仅子按钮列表；编辑态为 [+] 子按钮列表 [脚本管理][透明度][关闭]
            if (state.isExpanded) {
                Column(
                    // 注意：这里不能加 fillMaxWidth()。悬浮窗是 WRAP_CONTENT，fillMaxWidth 会解析为
                    // 父约束的最大宽度（≈屏幕宽度），把整个窗口撑成全屏宽，按钮看起来就「只能居中」、
                    // 且 lp.x 被压成 0 无法贴边。去掉后窗口宽度 = 40dp 内容 + 左右各 8dp 内边距 = 56dp。
                    modifier = Modifier.onGloballyPositioned {
                        val pos = it.positionInRoot()
                        onExpandedPositioned(pos.x, pos.y)
                    },
                    // 展开区按钮统一 40dp 见方并水平居中（与主按钮同宽）
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ExpandedItems(
                        state = state,
                        onAdd = onAdd,
                        onOpenSettings = onOpenSettings,
                        onOpenOpacity = onOpenOpacity,
                        onCloseWindow = onCloseWindow,
                        onChildRunTouch = onChildRunTouch,
                        onChildTouch = onChildTouch,
                        horizontal = false,
                    )
                }
            }
        }
    }
}

/** 主悬浮总按钮：横排 / 竖排两种布局共用 */
@Composable
private fun QuickMainButton(
    state: QuickFloatingUiState,
    onMainTouch: (MotionEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    FloatingSquareButton(
        modifier = modifier
            .graphicsLayer { alpha = state.mainAlpha }
            .pointerInteropFilter { onMainTouch(it) },
        pressed = state.mainPressed,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_main),
            contentDescription = "主按钮",
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            // 深色模式下主按钮底为浅青，着 onPrimary 保证图标对比度
            colorFilter = ColorFilter.tint(scheme.onPrimary),
        )
    }
}

/**
 * 展开区内容。调用方决定外层是 Column 还是 Row：
 * 直接往当前布局里依次发出各个按钮，因此横排 / 竖排共用同一份按钮定义。
 */
@Composable
private fun ExpandedItems(
    state: QuickFloatingUiState,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOpacity: () -> Unit,
    onCloseWindow: () -> Unit,
    onChildRunTouch: (index: Int, event: MotionEvent) -> Boolean,
    onChildTouch: (index: Int, event: MotionEvent) -> Boolean,
    horizontal: Boolean,
) {
    if (state.isEditing) {
        ControlRow(
            iconRes = R.drawable.ic_add,
            desc = "添加",
            onClick = onAdd,
            horizontal = horizontal,
        )
    }
    state.configs.forEachIndexed { index, cfg ->
        ChildRow(
            cfg = cfg,
            editing = state.isEditing,
            pressed = state.pressedChildIndex == index,
            dragging = state.draggingConfig === cfg,
            dragOffsetX = if (state.draggingConfig === cfg) state.dragOffsetX else 0f,
            dragOffsetY = if (state.draggingConfig === cfg) state.dragOffsetY else 0f,
            onRunTouch = { event -> onChildRunTouch(index, event) },
            onTouch = { event -> onChildTouch(index, event) },
            horizontal = horizontal,
        )
    }
    if (state.isEditing) {
        ControlRow(
            iconRes = R.drawable.ic_folder,
            desc = "脚本管理",
            onClick = onOpenSettings,
            horizontal = horizontal,
        )
        ControlRow(
            iconRes = R.drawable.ic_opacity,
            desc = "透明度",
            onClick = onOpenOpacity,
            horizontal = horizontal,
        )
        ControlRow(
            iconRes = R.drawable.ic_exit,
            desc = "关闭",
            onClick = onCloseWindow,
            horizontal = horizontal,
        )
    }
}

/** 编辑态控制行：[+]/[脚本管理]/[透明度]/[关闭]（对应原 buildControlRow） */
@Composable
private fun ControlRow(
    iconRes: Int,
    desc: String,
    onClick: () -> Unit,
    horizontal: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    // 与主按钮同一条原始触摸链路（不使用 clickable，避免两条事件链路互相吞事件）
    val tap = rememberRawTapDetector()
    FloatingSquareButton(
        modifier = Modifier
            .padding(start = if (horizontal) 4.dp else 0.dp, top = if (horizontal) 0.dp else 4.dp)
            .pointerInteropFilter { event -> tap.onEvent(event, onTap = onClick) },
        pressed = tap.isPressed,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = desc,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            colorFilter = ColorFilter.tint(scheme.onPrimary),
        )
    }
}

/** 子按钮行：非编辑态点击触发识别，长按 / 滑动透传给下层应用；编辑态点击打开修改/删除弹框，长按可拖动排序 */
@Composable
private fun ChildRow(
    cfg: QuickClickConfig,
    editing: Boolean,
    pressed: Boolean,
    dragging: Boolean,
    dragOffsetX: Float,
    dragOffsetY: Float,
    onRunTouch: (MotionEvent) -> Boolean,
    onTouch: (MotionEvent) -> Boolean,
    horizontal: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    FloatingSquareButton(
        modifier = Modifier
            .padding(start = if (horizontal) 4.dp else 0.dp, top = if (horizontal) 0.dp else 4.dp)
            .offset { IntOffset(x = dragOffsetX.roundToInt(), y = dragOffsetY.roundToInt()) }
            .pointerInteropFilter { event ->
                if (editing) onTouch(event) else onRunTouch(event)
            },
        pressed = pressed || dragging,
        // 拖动中的按钮浮起，投影更明显
        shadowElevation = if (dragging) 10.dp else 4.dp,
    ) {
        ChildName(name = cfg.name, color = scheme.onPrimary)
    }
}

/** 名称最小字号：再小就看不清，宁可轻微裁切也不继续缩 */
private val ChildNameMinSize = 8.sp

/**
 * 子按钮名称：按钮只有 40dp 见方，3 个汉字按 labelMedium 放不下。
 * 不截断也不省略，改为按可用宽度逐档缩小字号，保证名称完整显示。
 */
@Composable
private fun ChildName(name: String, color: Color) {
    val base = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.padding(horizontal = 4.dp)) {
        val maxWidth = constraints.maxWidth
        val fontSize = remember(name, maxWidth, base) {
            var size = base.fontSize
            while (size.value > ChildNameMinSize.value) {
                val fits = measurer.measure(
                    text = AnnotatedString(name),
                    style = base.copy(fontSize = size),
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= maxWidth
                if (fits) break
                size = (size.value - 0.5f).sp
            }
            size
        }
        Text(
            text = name,
            color = color,
            style = base.copy(fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
        )
    }
}
