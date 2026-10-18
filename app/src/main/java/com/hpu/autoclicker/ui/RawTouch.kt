package com.hpu.autoclicker.ui

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * 悬浮窗按钮的原始触摸点击判定器（替代 Modifier.clickable）
 *
 * 为什么不用 Modifier.clickable：
 * 悬浮窗根视图里「主按钮」走的是 Modifier.pointerInteropFilter（要拿 rawX/rawY 做拖动/双击状态机），
 * 如果同一棵 ComposeView 里其他按钮用 Modifier.clickable，两条事件链路会互相干扰——
 * 这正是项目既有硬约束「总按钮手势统一在单一 OnTouchListener 中维护状态机，禁止混用 click 与 onTouch」所禁止的写法。
 * 因此悬浮窗内所有按钮统一只用 pointerInteropFilter 这一条链路，点击判定在这里手工完成。
 *
 * 判定规则与 Android View 的 click 完全一致：
 * ACTION_DOWN 记录位置与时间；ACTION_UP 时位移未超过 touchSlop、且未达到长按时长，即为一次点击。
 * 注意：判定上限用 LONG_PRESS 时长（对应 View 的 mHasPerformedLongPress 抑制 click），
 * 不能用 getTapTimeout()（仅 100ms，会把大部分正常点击误判为无效导致「点击无响应」）。
 *
 * 禁用态的返回值同样对齐 View：可点击的 View 被 isEnabled=false 后依旧消费事件（只是不响应），
 * 因此这里禁用态返回 true 消费事件，避免误落到悬浮窗根视图监听上。
 */
@Stable
class RawTapDetector(private val touchSlop: Float) {

    /** 是否处于按下态（用于按钮背景色反馈，等价旧 XML selector 的 pressed 态） */
    var isPressed by mutableStateOf(false)
        private set

    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L

    fun onEvent(event: MotionEvent, enabled: Boolean = true, onTap: () -> Unit): Boolean {
        if (!enabled) {
            // 等价 View.onTouchEvent：禁用但仍可点击的 View 依旧消费触摸，只是不产生点击
            isPressed = false
            return true
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = SystemClock.uptimeMillis()
                isPressed = true
                Log.d("TouchDiag", "DOWN rawX=${event.rawX} rawY=${event.rawY}")
                true
            }

            MotionEvent.ACTION_MOVE -> {
                // 手指移出 touchSlop 即取消按下反馈
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (dx * dx + dy * dy > touchSlop * touchSlop) isPressed = false
                true
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val withinSlop = dx * dx + dy * dy <= touchSlop * touchSlop
                // 超过 LONG_PRESS 时长视为长按，抑制 click（对齐 View.onTouchEvent）
                val isLongPress =
                    SystemClock.uptimeMillis() - downAt > ViewConfiguration.getLongPressTimeout().toLong()
                if (withinSlop && !isLongPress) {
                    Log.d("TouchDiag", "按钮点击命中 rawX=${event.rawX} rawY=${event.rawY}")
                    onTap()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                true
            }

            else -> false
        }
    }
}

/** 在 Composable 内按当前 ViewConfiguration 创建点击判定器 */
@Composable
fun rememberRawTapDetector(): RawTapDetector {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    return remember(touchSlop) { RawTapDetector(touchSlop) }
}
