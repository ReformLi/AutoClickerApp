package com.hpu.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 连点器无障碍服务
 * 负责在指定坐标执行定时点击
 */
class AutoClickerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickService"
        // 静态实例，方便悬浮窗控制
        var instance: AutoClickerAccessibilityService? = null
            private set
    }

    // 定时器
    private val handler = Handler(Looper.getMainLooper())
    private var clickRunnable: Runnable? = null

    // 连点参数
    private var clickInterval = 100L      // 点击间隔（毫秒）
    private var targetX = 0f              // 目标 X 坐标（屏幕像素）
    private var targetY = 0f              // 目标 Y 坐标
    private var isClicking = false        // 是否正在连点

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
        // 测试全局返回动作
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d("ClickService", "执行了全局返回")
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在此监听界面变化，此处暂不需要
    }

    override fun onInterrupt() {
        Log.d(TAG, "服务中断")
        stopClicking()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopClicking()
    }

    /**
     * 开始连点
     * @param x 屏幕 X 坐标（像素）
     * @param y 屏幕 Y 坐标
     * @param interval 点击间隔时间（毫秒），默认 100ms
     */
    fun startClicking(x: Float, y: Float, interval: Long = 100) {
        if (isClicking) return
        targetX = x
        targetY = y
        clickInterval = interval
        isClicking = true

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isClicking) return
                performClick(targetX, targetY)
                handler.postDelayed(this, clickInterval)
            }
        }
        handler.post(clickRunnable!!)
        Log.d(TAG, "开始连点：($targetX, $targetY) 间隔 ${interval}ms")
    }

    /**
     * 停止连点
     */
    fun stopClicking() {
        isClicking = false
        clickRunnable?.let { handler.removeCallbacks(it) }
        clickRunnable = null
        Log.d(TAG, "停止连点")
    }

    /**
     * 更新点击坐标（无需停止）
     */
    fun updateCoordinate(x: Float, y: Float) {
        targetX = x
        targetY = y
    }

    /**
     * 更新点击间隔（单位：毫秒）
     */
    fun updateInterval(interval: Long) {
        clickInterval = interval
        // 如果需要立即生效，可以重启循环
        if (isClicking) {
            stopClicking()
            startClicking(targetX, targetY, clickInterval)
        }
    }
    fun performClick(x: Float, y: Float, duration: Long = 300) {
        Log.d("点击位置", ""+x+" "+y)
        val clickDuration = duration.coerceAtLeast(50L)

        // 坐标连点应优先使用无障碍手势，确保点击发生在用户设置的精确坐标。
        // ACTION_CLICK 依赖节点树，可能点到父容器或过期节点，表现为偶发第一次点击无效。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.5f, y)   // 极小移动，避免被误判为滑动
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, clickDuration))
                .build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(desc: GestureDescription?) {
                    Log.d("ClickDebug", "手势点击成功: x=$x, y=$y")
                }

                override fun onCancelled(desc: GestureDescription?) {
                    Log.e("ClickDebug", "手势点击被取消: x=$x, y=$y")
                }
            }, null)

            if (dispatched) {
                return
            }

            Log.e("ClickDebug", "dispatchGesture 返回 false，尝试回退控件点击")
        }

        val node = findClickableNodeAt(x, y)
        if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("ClickDebug", "控件点击回退结果: $clicked")
            node.recycle()
        } else {
            Log.w("ClickDebug", "未找到可点击控件，点击失败: x=$x, y=$y")
        }
    }

    /**
     * 查找屏幕上 (x, y) 坐标处可点击的最小控件节点
     */
    private fun findClickableNodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
//        val list = root.findAccessibilityNodeInfosByViewId(0) // 不常用，需要遍历
        // 更可靠的方式：递归查找
        return findNodeAt(root, x.toInt(), y.toInt())
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.contains(x, y) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeAt(child, x, y)
            if (result != null) return result
        }
        return null
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 500) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w("SwipeDebug", "当前系统版本不支持无障碍手势滑动")
            return
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val realSize = Point()
        display.getRealSize(realSize)

        val screenWidth = realSize.x
        val screenHeight = realSize.y
        val safeMargin = 1f // 只做屏幕边界保护，避免明显改变用户设置的滑动坐标

        // 1. 约束坐标在屏幕内
        val safeStartX = startX.coerceIn(safeMargin, screenWidth - safeMargin)
        val safeStartY = startY.coerceIn(safeMargin, screenHeight - safeMargin)
        val safeEndX = endX.coerceIn(safeMargin, screenWidth - safeMargin)
        val safeEndY = endY.coerceIn(safeMargin, screenHeight - safeMargin)

        // 2. 检查最小滑动距离
        val minSwipeLength = 100f
        val dx = safeEndX - safeStartX
        val dy = safeEndY - safeStartY
        if (sqrt(dx * dx + dy * dy) < minSwipeLength) {
            Log.w("SwipeDebug", "滑动距离过短，已忽略")
            return
        }

        val swipeDuration = duration.coerceAtLeast(200L)

        Log.d("SwipeDebug", "执行滑动: ($safeStartX,$safeStartY) → ($safeEndX,$safeEndY), duration=$swipeDuration")

        val path = Path().apply {
            moveTo(safeStartX, safeStartY)
            lineTo(safeEndX, safeEndY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, swipeDuration))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(desc: GestureDescription?) {
                Log.d("SwipeDebug", "滑动成功")
            }
            override fun onCancelled(desc: GestureDescription?) {
                Log.e("SwipeDebug", "滑动被系统取消，可能被系统手势区域、权限状态或目标应用拦截")
            }
        }, null)

        if (!dispatched) {
            Log.e("SwipeDebug", "dispatchGesture 返回 false，滑动手势未提交")
        }
    }

    /**
     * 兼容旧版本的点击方法（通过 AccessibilityNodeInfo）
     * 注意：该方法只在视图控件上有效，纯坐标点击可能不生效
     */
    private fun performClickLegacy(x: Float, y: Float) {
        val root = rootInActiveWindow ?: return
        val node = root.findAccessibilityNodeInfosByViewId(x.toInt().toString()) // 简单例子，实际不推荐
        // 更健壮的方式是遍历所有可点击节点找到坐标对应的节点，这里略
    }

    /**
     * 检查无障碍服务是否已开启
     */
    fun isServiceEnabled(): Boolean {
        return instance != null
    }
}
