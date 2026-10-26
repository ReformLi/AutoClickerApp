package com.hpu.autoclicker.feature.recorder

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import kotlin.math.hypot

/**
 * 录点器回放引擎。
 *
 * 节奏规则：
 * - 每一步先等待 `delayBefore / 倍速`，再注入手势；
 * - 2.0× 即等待时间减半，倍速只作用于等待，不改手势本身时长；
 * - 逐步注入，每一步之前重新检查无障碍连接；
 * - 暂停发生在「当前步结束之后」：正在注入的手势不打断，等待中的下一步立即取消；
 * - 停止/取消立即停止调度，已在系统里的手势无法撤回，但不会再注入下一步。
 *
 * 引擎只调用 [AutoClickerAccessibilityService.dispatchInjectedGesture]，
 * 不接触连点器 / 代点器的任何脚本或规则。
 */
class RecorderPlaybackEngine(
    private val handler: Handler,
    private val serviceProvider: () -> AutoClickerAccessibilityService?,
    private val listener: Listener,
) {
    interface Listener {
        fun onProgress(stepIndex: Int, repeatIndex: Int)
        fun onPaused()
        fun onFinished()
        fun onError(message: String)

        /** 手势即将注入 / 已结束：悬浮窗据此在冲突时临时让出触摸 */
        fun onStepDispatched(step: RecorderStep) {}
        fun onStepCompleted() {}

        /** 注入前的额外等待：给悬浮窗留一帧让 NOT_TOUCHABLE 生效，0 表示不等待 */
        fun injectionGuardDelayMs(): Long = 0L
    }

    enum class State { IDLE, RUNNING, PAUSED }

    var state: State = State.IDLE
        private set

    private var script: RecorderScript? = null
    private var speed = 1f
    private var running = false
    private var paused = false
    private var stepIndex = 0
    private var repeatIndex = 0
    private var pending: Runnable? = null
    private var injectionGuard: Runnable? = null
    private var inFlight = false
    private var generation = 0

    val currentStepIndex: Int get() = stepIndex
    val currentRepeatIndex: Int get() = repeatIndex
    val isRunning: Boolean get() = running
    val isPaused: Boolean get() = running && paused

    /**
     * 开始/恢复回放。
     * @param startStep 恢复现场时的下一步下标
     * @param startRepeat 恢复现场时的重复轮次（0 起）
     * @param startPaused 恢复现场时保持暂停，不自动注入
     */
    fun start(
        script: RecorderScript,
        speed: Float,
        startStep: Int = 0,
        startRepeat: Int = 0,
        startPaused: Boolean = false,
    ): Boolean {
        if (script.steps.isEmpty()) {
            listener.onError("脚本没有可回放的步骤")
            return false
        }
        cancelPending()
        cancelInjectionGuard()
        generation++
        this.script = script
        this.speed = sanitizeSpeed(speed)
        this.stepIndex = startStep.coerceIn(0, script.steps.size - 1)
        this.repeatIndex = startRepeat.coerceIn(0, script.repeatCount - 1)
        running = true
        paused = startPaused
        state = if (paused) State.PAUSED else State.RUNNING
        listener.onProgress(stepIndex, repeatIndex)
        if (!paused) scheduleNext()
        return true
    }

    /** 暂停：等待中的下一步立即取消；已提交的手势等它结束，不再排下一步 */
    fun pause() {
        if (!running || paused) return
        paused = true
        state = State.PAUSED
        cancelPending()
        cancelInjectionGuard()
        if (!inFlight) listener.onPaused()
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        state = State.RUNNING
        // 暂停时若有手势在飞行中：等它结束由 advanceAfterStep 续排，避免同一步被排两次
        if (!inFlight) scheduleNext()
    }

    /** 停止并复位；用于「停止 / 重置」和返回编辑 */
    fun stop() {
        running = false
        paused = false
        state = State.IDLE
        generation++
        cancelPending()
        cancelInjectionGuard()
        inFlight = false
        stepIndex = 0
        repeatIndex = 0
        script = null
    }

    /** 回放中修改重复次数：当前轮跑完后按新值决定是否继续 */
    fun setRepeatCount(count: Int) {
        val current = script ?: return
        script = current.copy(
            repeatCount = count.coerceIn(RecorderScript.MIN_REPEAT, RecorderScript.MAX_REPEAT),
        )
    }

    /** 回放中修改倍速：只影响之后还没开始的等待 */
    fun setSpeed(value: Float) {
        speed = sanitizeSpeed(value)
    }

    private fun scheduleNext() {
        if (!running || paused) return
        val current = script ?: return

        if (stepIndex >= current.steps.size) {
            val nextRepeat = repeatIndex + 1
            if (nextRepeat >= current.repeatCount) {
                finish()
                return
            }
            repeatIndex = nextRepeat
            stepIndex = 0
            listener.onProgress(stepIndex, repeatIndex)
            scheduleNext()
            return
        }

        val step = current.steps[stepIndex]
        listener.onProgress(stepIndex, repeatIndex)
        val wait = (step.delayBefore / speed).toLong().coerceAtLeast(0L)
        pending = Runnable { executeCurrent() }.also { handler.postDelayed(it, wait) }
    }

    private fun executeCurrent() {
        pending = null
        if (!running || paused) return
        val current = script ?: return
        if (stepIndex >= current.steps.size) {
            scheduleNext()
            return
        }
        val step = current.steps[stepIndex]
        if (step.type == RecorderStepType.WAIT) {
            val wait = (step.duration / speed).toLong().coerceAtLeast(0L)
            pending = Runnable { advanceAfterStep() }.also { handler.postDelayed(it, wait) }
            return
        }

        val service = serviceProvider()
        if (service == null) {
            pauseWithError("无障碍服务未连接，回放已暂停")
            return
        }
        val gesture = buildRecorderGesture(step) ?: run {
            advanceAfterStep()
            return
        }
        val gen = generation
        inFlight = true
        listener.onStepDispatched(step)
        val dispatch = Runnable {
            injectionGuard = null
            if (gen != generation || !running || paused) return@Runnable
            service.dispatchInjectedGesture(gesture) { ok ->
                handler.post {
                    listener.onStepCompleted()
                    if (gen != generation) return@post
                    inFlight = false
                    if (!ok) {
                        pauseWithError(
                            "第 ${stepIndex + 1} 步被系统取消或提交失败（可能是安全界面/系统手势区）"
                        )
                        return@post
                    }
                    advanceAfterStep()
                }
            }
        }
        val guard = listener.injectionGuardDelayMs().coerceIn(0L, 200L)
        if (guard > 0L) {
            injectionGuard = dispatch
            handler.postDelayed(dispatch, guard)
        } else {
            dispatch.run()
        }
    }

    private fun advanceAfterStep() {
        pending = null
        if (!running) return
        stepIndex++
        if (paused) {
            listener.onPaused()
            return
        }
        scheduleNext()
    }

    private fun pauseWithError(message: String) {
        pauseInternal()
        listener.onError(message)
    }

    private fun pauseInternal() {
        if (!running) return
        paused = true
        state = State.PAUSED
        cancelPending()
        cancelInjectionGuard()
        listener.onPaused()
    }

    private fun finish() {
        running = false
        paused = false
        state = State.IDLE
        generation++
        cancelPending()
        cancelInjectionGuard()
        inFlight = false
        stepIndex = 0
        repeatIndex = 0
        script = null
        listener.onFinished()
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /** 注入前的保护等待被取消：这一步还没真正注入，按未开始处理并让悬浮窗恢复触摸 */
    private fun cancelInjectionGuard() {
        val guard = injectionGuard ?: return
        handler.removeCallbacks(guard)
        injectionGuard = null
        inFlight = false
        listener.onStepCompleted()
    }

    private fun sanitizeSpeed(value: Float): Float =
        if (value.isFinite() && value > 0f) value.coerceIn(0.1f, 10f) else 1f
}

/**
 * 把一步转成系统手势。回放与录制时的「立即回显」共用，
 * 保证用户看到的即时效果与正式回放一致。
 */
internal fun buildRecorderGesture(step: RecorderStep): GestureDescription? {
    val path = Path()
    val duration: Long
    when (step.type) {
        RecorderStepType.CLICK -> {
            // 录制到的按压时长按点击回放，但不让它长到被系统当成长按
            duration = step.duration.coerceIn(50L, 600L)
            path.moveTo(step.x.toFloat(), step.y.toFloat())
            path.lineTo(step.x + 0.5f, step.y.toFloat())
        }

        RecorderStepType.LONG_PRESS -> {
            duration = step.duration.coerceIn(200L, RecorderStep.MAX_GESTURE_DURATION)
            path.moveTo(step.x.toFloat(), step.y.toFloat())
            path.lineTo(step.x + 0.5f, step.y.toFloat())
        }

        RecorderStepType.SWIPE -> {
            duration = step.duration.coerceIn(50L, RecorderStep.MAX_GESTURE_DURATION)
            path.moveTo(step.x1.toFloat(), step.y1.toFloat())
            if (hypot(
                    (step.x2 - step.x1).toFloat(),
                    (step.y2 - step.y1).toFloat(),
                ) < 1f
            ) {
                path.lineTo(step.x1 + 0.5f, step.y1.toFloat())
            } else {
                path.lineTo(step.x2.toFloat(), step.y2.toFloat())
            }
        }

        RecorderStepType.WAIT -> return null
    }
    return GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
        .build()
}
