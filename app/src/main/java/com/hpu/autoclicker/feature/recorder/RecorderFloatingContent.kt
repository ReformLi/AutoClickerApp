package com.hpu.autoclicker.feature.recorder

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.ui.FloatingSquareButton
import com.hpu.autoclicker.core.ui.rememberRawTapDetector

enum class RecorderPanelMode { EDIT, RUNNING }

/** 悬浮窗 UI 状态：全部由 Service 更新，Compose 自动重组 */
@Stable
class RecorderFloatingUiState {
    var isExpanded by mutableStateOf(false)
    var mode by mutableStateOf(RecorderPanelMode.EDIT)

    /** 横屏高度放不下竖向面板时改为横排展开，由 Service 按屏幕尺寸切换 */
    var horizontal by mutableStateOf(false)

    /** 竖排贴的是哪条竖边：信息条向屏幕内侧延伸，按钮列始终贴着停靠边，主按钮才不会横向跳动 */
    var dockedLeft by mutableStateOf(true)

    var mainPressed by mutableStateOf(false)

    var scriptName by mutableStateOf("未命名脚本")
    var scriptSaved by mutableStateOf(false)
    var stepCount by mutableStateOf(0)
    var repeatCount by mutableStateOf(1)
    var speed by mutableStateOf(1f)
    var draftAvailable by mutableStateOf(false)

    /** 回放现场：playing 含暂停态，paused 单独标记 */
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var currentStep by mutableStateOf(0)
    var currentRepeat by mutableStateOf(0)
}

/**
 * 录点器悬浮窗内容：主按钮固定在首位（竖排在顶部、横排在左端），
 * 展开时面板向下 / 向右生长，和代点器一致——窗口原点不动，
 * 因此展开 / 折叠 / 切换运行态都不会出现整窗平移。
 *
 * 编辑态：主按钮 + 信息条（有脚本时）+ 录制 / 启动 / 文件 / 设置 / 关闭；
 * 运行态：主按钮 + 信息条（有脚本时）+ 启动或暂停 / 停止 / 返回。
 *
 * 所有可点元件都走 pointerInteropFilter + [rememberRawTapDetector]，
 * 与主按钮共用一条原始触摸链路，避免 Compose clickable 与拖动状态机抢事件。
 */
@Composable
fun RecorderFloatingContent(
    state: RecorderFloatingUiState,
    onMainTouch: (MotionEvent) -> Boolean,
    onRecord: () -> Unit,
    onRun: () -> Unit,
    onScripts: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopPlay: () -> Unit,
    onReturnEdit: () -> Unit,
) {
    if (state.horizontal) {
        Row(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainButton(state, onMainTouch)
            if (state.isExpanded) {
                if (state.stepCount > 0) InfoStrip(state, horizontal = true)
                ActionButtons(
                    state = state,
                    onRecord = onRecord,
                    onRun = onRun,
                    onScripts = onScripts,
                    onSettings = onSettings,
                    onClose = onClose,
                    onTogglePlay = onTogglePlay,
                    onStopPlay = onStopPlay,
                    onReturnEdit = onReturnEdit,
                    horizontal = true,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(Dimens.space8),
            horizontalAlignment = if (state.dockedLeft) Alignment.Start else Alignment.End,
        ) {
            MainButton(state, onMainTouch)
            if (state.isExpanded) {
                if (state.stepCount > 0) InfoStrip(state, horizontal = false)
                ActionButtons(
                    state = state,
                    onRecord = onRecord,
                    onRun = onRun,
                    onScripts = onScripts,
                    onSettings = onSettings,
                    onClose = onClose,
                    onTogglePlay = onTogglePlay,
                    onStopPlay = onStopPlay,
                    onReturnEdit = onReturnEdit,
                    horizontal = false,
                )
            }
        }
    }
}

@Composable
private fun MainButton(
    state: RecorderFloatingUiState,
    onMainTouch: (MotionEvent) -> Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    FloatingSquareButton(
        modifier = Modifier.pointerInteropFilter { onMainTouch(it) },
        pressed = state.mainPressed,
    ) {
        Image(
            // 与连点器 / 代点器主按钮同款「总按钮」图标；录制操作仍用 ic_record，两者不再混淆
            painter = painterResource(R.drawable.ic_main),
            contentDescription = "录点器",
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.space8),
            colorFilter = ColorFilter.tint(scheme.onPrimary),
        )
    }
}

/**
 * 展开后的操作项。调用方决定外层是 Column 还是 Row：
 * 直接往当前布局里依次发出各个方块，横排 / 竖排共用同一份定义。
 */
@Composable
private fun ActionButtons(
    state: RecorderFloatingUiState,
    onRecord: () -> Unit,
    onRun: () -> Unit,
    onScripts: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopPlay: () -> Unit,
    onReturnEdit: () -> Unit,
    horizontal: Boolean,
) {
    if (state.mode == RecorderPanelMode.EDIT) {
        IconAction(
            iconRes = R.drawable.ic_record,
            desc = "录制",
            enabled = true,
            onClick = onRecord,
            horizontal = horizontal,
        )
        IconAction(
            iconRes = R.drawable.ic_play,
            desc = "启动",
            enabled = state.stepCount > 0,
            onClick = onRun,
            horizontal = horizontal,
        )
        IconAction(
            iconRes = R.drawable.ic_folder,
            desc = "文件",
            enabled = true,
            onClick = onScripts,
            horizontal = horizontal,
        )
        IconAction(
            iconRes = R.drawable.ic_settings,
            desc = "设置",
            enabled = true,
            onClick = onSettings,
            horizontal = horizontal,
        )
        IconAction(
            // 与连点器 / 代点器同一个关闭图标，固定排在编辑操作的最后
            iconRes = R.drawable.ic_exit,
            desc = "关闭",
            enabled = true,
            onClick = onClose,
            horizontal = horizontal,
        )
    } else {
        val playingNow = state.playing && !state.paused
        IconAction(
            iconRes = if (playingNow) R.drawable.ic_pause else R.drawable.ic_play,
            desc = when {
                playingNow -> "暂停"
                state.playing -> "继续"
                else -> "启动"
            },
            enabled = state.stepCount > 0,
            onClick = onTogglePlay,
            horizontal = horizontal,
        )
        IconAction(
            iconRes = R.drawable.ic_stop,
            desc = "停止",
            enabled = state.playing,
            onClick = onStopPlay,
            horizontal = horizontal,
        )
        IconAction(
            iconRes = R.drawable.ic_back,
            desc = "返回",
            enabled = true,
            onClick = onReturnEdit,
            horizontal = horizontal,
        )
    }
}

/**
 * 信息条：只读展示，不可点，尺寸与主按钮完全一致（40dp 方块）。
 * 没有任何脚本（0 步）时整个信息条不渲染，悬浮窗上不会出现「未命名脚本」之类的占位。
 *
 * 底色用品牌浅色（primaryContainer）而不是白色：白色方块夹在一排品牌实心按钮中间很突兀，
 * 浅青既与按钮同族，又靠「浅底 + 深字」表明它是展示块而非可点按钮（与主页模块徽标同一套语言）。
 *
 * 编辑态：脚本名；
 * 运行态：脚本名 / 第 X/Y 轮 / 第 N/M 步，每行独立按可用宽度缩字号。
 */
@Composable
private fun InfoStrip(state: RecorderFloatingUiState, horizontal: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            .padding(
                start = if (horizontal) Dimens.space4 else 0.dp,
                top = if (horizontal) 0.dp else Dimens.space4,
            )
            .size(40.dp)
            .shadow(elevation = 4.dp, shape = shape, clip = false)
            .background(scheme.primaryContainer, shape)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StripLine(text = state.scriptName, color = scheme.onPrimaryContainer)
        if (state.mode == RecorderPanelMode.RUNNING) {
            val step = if (state.stepCount > 0) {
                (state.currentStep + 1).coerceIn(1, state.stepCount)
            } else {
                0
            }
            val repeat = (state.currentRepeat + 1).coerceIn(1, state.repeatCount)
            StripLine(text = "第 $repeat/${state.repeatCount} 轮", color = scheme.onPrimaryContainer)
            StripLine(text = "第 $step/${state.stepCount} 步", color = scheme.onPrimaryContainer)
        }
    }
}

private val StripLineMaxSize = 9.sp
private val StripLineMinSize = 6.sp

/**
 * 信息条单行：40dp 方块里放不下时逐档缩小字号（每行独立判定），
 * 三行可以出现不同字号；缩到 6sp 仍超出才省略。
 */
@Composable
private fun StripLine(text: String, color: Color) {
    val base = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxWidth = constraints.maxWidth
        val fontSize = remember(text, maxWidth) {
            var size = StripLineMaxSize
            while (size.value > StripLineMinSize.value) {
                val fits = measurer.measure(
                    text = AnnotatedString(text),
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
            text = text,
            color = color,
            style = base.copy(
                fontSize = fontSize,
                lineHeight = (fontSize.value * 1.1f).sp,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 操作栏方块：40dp 见方、12dp 圆角；竖排 4dp 上间距 / 横排 4dp 左间距 */
@Composable
private fun IconAction(
    iconRes: Int,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    horizontal: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    // 与主按钮同一条原始触摸链路（不使用 clickable，避免两条事件链路互相吞事件）
    val tap = rememberRawTapDetector()
    FloatingSquareButton(
        modifier = Modifier
            .padding(
                start = if (horizontal) Dimens.space4 else 0.dp,
                top = if (horizontal) 0.dp else Dimens.space4,
            )
            .pointerInteropFilter { event -> tap.onEvent(event, enabled = enabled, onTap = onClick) },
        pressed = enabled && tap.isPressed,
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
