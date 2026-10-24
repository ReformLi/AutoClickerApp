package com.hpu.autoclicker.feature.clicker

import android.graphics.Rect
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.ui.FloatingSquareButton
import com.hpu.autoclicker.core.ui.rememberRawTapDetector

/**
 * 连点器悬浮窗（旧功能）的 Compose 内容（由 activity_floating_window_service.xml 迁移）
 *
 * 与旧实现差异说明：
 * - 原来通过 ImageView + visibility + findViewById().isEnabled/setImageResource 手工刷新，
 *   现改为「状态驱动」：按钮的可用性、图标（启动/停止、隐藏/显示）全部由
 *   [FloatingServiceUiState] 派生，Compose 自动重组。
 * - 触摸事件仍沿用 MotionEvent 原始坐标（pointerInteropFilter 直接拿到 rawX/rawY），
 *   主按钮的单击展开/折叠、拖动、边缘吸附逻辑与原来完全一致。
 * - 按钮在窗口内的位置通过 onGloballyPositioned 上报给 Service，
 *   用于运行期判断「触摸是否落在允许的按钮上」（替代原 findViewById + getLocationOnScreen）。
 */
@Stable
class FloatingServiceUiState {
    /** 操作栏是否展开 */
    var isExpanded by mutableStateOf(false)

    /** 是否正在连点（运行中：启动按钮变停止图标） */
    var isRunning by mutableStateOf(false)

    /** 是否隐藏点击点（隐藏中：隐藏按钮变显示图标） */
    var isHidden by mutableStateOf(false)

    /** 主按钮按下态（等价原 bg_floating_button 的 pressed 态） */
    var mainPressed by mutableStateOf(false)

    /** 添加 / 减少 / 滑动 / 设置：运行中或隐藏时置灰不可点 */
    val editEnabled: Boolean get() = !isRunning && !isHidden

    /** 隐藏按钮：运行中不可点 */
    val hideEnabled: Boolean get() = !isRunning

    /** 启动 / 停止按钮：隐藏时不可点 */
    val startEnabled: Boolean get() = !isHidden
}

@Composable
fun FloatingServiceContent(
    state: FloatingServiceUiState,
    /** 主按钮原始触摸事件（单击展开/折叠、拖动、边缘吸附状态机在 Service 内维护） */
    onMainTouch: (MotionEvent) -> Boolean,
    onStart: () -> Unit,
    onAdd: () -> Unit,
    onSlide: () -> Unit,
    onReduce: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit,
    onExit: () -> Unit,
    /** 上报主按钮在窗口内的位置（屏幕坐标由 Service 用窗口偏移换算） */
    onMainBounds: (Rect) -> Unit,
    /** 上报退出按钮在窗口内的位置 */
    onExitBounds: (Rect) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        // 宽度改为「随内容自适应」：按钮统一收窄到 40dp 后，若窗口仍固定 120dp，
        // 吸附屏幕边缘时按钮会因水平居中而在窗口内留下约 40dp 透明间隙，看起来就不贴边了。
        // 随内容收缩后，窗口宽度 ≈ 40dp + 左右各 8dp 内边距，吸附时按钮紧贴边缘。
        modifier = Modifier
            .background(Color.Transparent)
            .padding(8.dp),
    ) {
        // 主悬浮按钮（40dp 品牌底圆角方块 + 白色图标，与代点器悬浮窗同一套样式）
        FloatingSquareButton(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .onGloballyPositioned { onMainBounds(it.boundsInWindow().toRect()) }
                .pointerInteropFilter { onMainTouch(it) },
            pressed = state.mainPressed,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_main),
                contentDescription = "主按钮",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                // 图标 drawable 是白色填充；深色模式下主按钮底为浅青，着 onPrimary 保证对比度
                colorFilter = ColorFilter.tint(scheme.onPrimary),
            )
        }

        // 展开的操作栏：启动 / 添加 / 滑动 / 减少 / 设置 / 隐藏 / 退出
        if (state.isExpanded) {
            Column(
                // 注意：这里不能加 fillMaxWidth()。悬浮窗是 WRAP_CONTENT，fillMaxWidth 会解析为
                // 父约束的最大宽度（≈屏幕宽度），把整个窗口撑成全屏宽，按钮看起来就「只能居中」、
                // 且 lp.x 被压成 0 无法贴边。去掉后窗口宽度 = 40dp 内容 + 左右各 8dp 内边距 = 56dp。
                // 操作栏按钮统一 40dp 见方并水平居中（与主按钮同宽）
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FloatingIconRow(
                    iconRes = if (state.isRunning) R.drawable.ic_stop else R.drawable.ic_play,
                    desc = if (state.isRunning) "停止" else "启动",
                    enabled = state.startEnabled,
                    onClick = onStart,
                )
                FloatingIconRow(
                    iconRes = R.drawable.ic_add,
                    desc = "添加",
                    enabled = state.editEnabled,
                    onClick = onAdd,
                )
                FloatingIconRow(
                    iconRes = R.drawable.ic_slide,
                    desc = "滑动",
                    enabled = state.editEnabled,
                    onClick = onSlide,
                )
                FloatingIconRow(
                    iconRes = R.drawable.ic_remove,
                    desc = "减少",
                    enabled = state.editEnabled,
                    onClick = onReduce,
                )
                FloatingIconRow(
                    iconRes = R.drawable.ic_settings,
                    desc = "设置",
                    enabled = state.editEnabled,
                    onClick = onSettings,
                )
                FloatingIconRow(
                    iconRes = if (state.isHidden) R.drawable.ic_display else R.drawable.ic_hide,
                    desc = if (state.isHidden) "显示" else "隐藏",
                    enabled = state.hideEnabled,
                    onClick = onHide,
                )
                FloatingIconRow(
                    iconRes = R.drawable.ic_exit,
                    desc = "退出",
                    enabled = true,   // 退出按钮始终可用
                    onClick = onExit,
                    onBounds = onExitBounds,
                )
            }
        }
    }
}

/** 操作栏图标行：40dp 见方、12dp 圆角、4dp 上间距（对应原 7 个 ImageView） */
@Composable
private fun FloatingIconRow(
    iconRes: Int,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onBounds: ((Rect) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    // 与主按钮同一条原始触摸链路（不使用 clickable，避免两条事件链路互相吞事件）
    val tap = rememberRawTapDetector()
    FloatingSquareButton(
        modifier = Modifier
            .padding(top = 4.dp)
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned { onBounds(it.boundsInWindow().toRect()) }
                } else {
                    Modifier
                }
            )
            .pointerInteropFilter { event -> tap.onEvent(event, enabled = enabled, onTap = onClick) },
        pressed = enabled && tap.isPressed,
        // 禁用态置灰（对应原 btn.alpha = 0.4f）
        enabled = enabled,
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

/** Compose 的 Rect（androidx.compose.ui.geometry.Rect）转成 android.graphics.Rect */
private fun androidx.compose.ui.geometry.Rect.toRect(): Rect =
    Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
