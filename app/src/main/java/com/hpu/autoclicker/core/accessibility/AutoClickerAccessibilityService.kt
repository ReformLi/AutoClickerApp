package com.hpu.autoclicker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
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
        /** 节点树递归上限，防止异常深的树把栈打爆 */
        private const val MAX_TREE_DEPTH = 60
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
    fun performClick(x: Float, y: Float, duration: Long = 300, onResult: ((Boolean) -> Unit)? = null) {
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
                    onResult?.invoke(true)
                }

                override fun onCancelled(desc: GestureDescription?) {
                    Log.e("ClickDebug", "手势点击被取消: x=$x, y=$y")
                    onResult?.invoke(false)
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
            onResult?.invoke(clicked)
        } else {
            Log.w("ClickDebug", "未找到可点击控件，点击失败: x=$x, y=$y")
            onResult?.invoke(false)
        }
    }

    /**
     * 代点器通用手势注入：点击 / 双击 / 长按 / 滑动共用。
     * 手势（坐标、偏移、方向、时长）由代点器层构造好，这里只负责提交并回传结果。
     *
     * @return dispatchGesture 是否成功提交；false 表示服务断连或被系统拒绝，此时不会再有回调
     */
    fun dispatchQuickGesture(
        gesture: GestureDescription,
        onResult: ((Boolean) -> Unit)? = null,
    ): Boolean = dispatchInjectedGesture(gesture, onResult)

    /**
     * 通用手势注入（录点器回放 / 即时回显共用）。
     * 录点器只负责构造 GestureDescription，不依赖任何业务模块；
     * 这里同样只提交并回传结果，不做坐标/时长解释。
     *
     * @return dispatchGesture 是否成功提交；false 表示服务断连或被系统拒绝，此时不会再有回调
     */
    fun dispatchInjectedGesture(
        gesture: GestureDescription,
        onResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onResult?.invoke(false)
            return false
        }
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(desc: GestureDescription?) {
                onResult?.invoke(true)
            }

            override fun onCancelled(desc: GestureDescription?) {
                onResult?.invoke(false)
            }
        }, null)
        if (!dispatched) onResult?.invoke(false)
        return dispatched
    }

    /**
     * 文本匹配查找目标：返回目标节点的屏幕中心点坐标，null 表示匹配失败。
     *
     * 匹配分三层，逐层放宽（全部按「去掉空白后包含」比较，规避中英文空格/换行差异）：
     * 1. 系统检索 findAccessibilityNodeInfosByText：一次调用覆盖绝大多数界面，
     *    但它会混入大量“有壳无字”的父节点（Android 14+ 敏感节点还可能返回空文本），必须二次筛选；
     * 2. 系统检索没命中时自行遍历整棵节点树：部分界面的文字挂在深层/虚拟节点上，系统接口会漏；
     * 3. 文字被拆成多个节点时（如「立即」+「抢购」），`contains` 永远命不中，
     *    改为找「子树文字拼接后包含目标」的最深层可点击节点。
     * 全部落空时把窗口内可见文字打进日志，便于对照目标文字实际写成了什么。
     */
    fun findTargetByText(targetText: String): PointF? {
        val target = normalizeText(targetText)
        if (target.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val rect = android.graphics.Rect()
        val screen = screenBounds()

        // 1. 系统检索
        val fromApi = ArrayList<AccessibilityNodeInfo>()
        try {
            root.findAccessibilityNodeInfosByText(targetText.trim())?.forEach { n ->
                if (nodeMatchesText(n, target)) fromApi.add(n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "系统文本检索异常", e)
        }
        pickTarget(fromApi, target, rect, screen)?.let { return it }

        // 2. 自行遍历整棵树兜底
        val walked = ArrayList<AccessibilityNodeInfo>()
        val grouped = ArrayList<AccessibilityNodeInfo>()
        walkTextNodes(root, target, walked, grouped, 0)
        pickTarget(walked, target, rect, screen)?.let { return it }

        // 3. 文字被拆成多个节点：取子树拼接命中的最深层可点击节点
        val groupCandidates = grouped.filter { hasValidBounds(it, rect, screen) }
        groupCandidates
            .map { it to nodeDepth(it) }
            .maxByOrNull { it.second }
            ?.first
            ?.let { return centerOf(it, rect) }

        Log.i(TAG, "文本未命中「$targetText」，窗口=${root.packageName}，可见文字=${visibleTexts(root)}")
        return null
    }

    /** 从候选节点里挑点击目标：可点击优先 → 文本完全相等 → 最深；均取可见节点 */
    private fun pickTarget(
        candidates: List<AccessibilityNodeInfo>,
        target: String,
        rect: android.graphics.Rect,
        screen: android.graphics.Rect,
    ): PointF? {
        val visible = candidates.filter { hasValidBounds(it, rect, screen) }
        if (visible.isEmpty()) return null

        val clickable = visible.filter { it.isClickable }
        if (clickable.isNotEmpty()) {
            val exact = clickable.firstOrNull { nodeTextEquals(it, target) }
            return centerOf(exact ?: clickable.first(), rect)
        }

        val exact = visible.filter { nodeTextEquals(it, target) }
        val pool = if (exact.isNotEmpty()) exact else visible
        val deepest = pool.map { it to nodeDepth(it) }.maxByOrNull { it.second }?.first
        return deepest?.let { centerOf(it, rect) }
    }

    /** 遍历整棵树：收集自身命中文本的节点，以及子树拼接命中的可点击节点 */
    private fun walkTextNodes(
        node: AccessibilityNodeInfo,
        target: String,
        direct: MutableList<AccessibilityNodeInfo>,
        grouped: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        if (nodeMatchesText(node, target)) {
            direct.add(node)
        } else if (node.isClickable && subtreeTextContains(node, target)) {
            grouped.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkTextNodes(child, target, direct, grouped, depth + 1)
        }
    }

    /** 节点自身文字是否包含目标（忽略空白与大小写） */
    private fun nodeMatchesText(node: AccessibilityNodeInfo, target: String): Boolean {
        if (target.isEmpty()) return false
        return normalizeText(node.text?.toString()).contains(target, ignoreCase = true) ||
            normalizeText(node.contentDescription?.toString()).contains(target, ignoreCase = true)
    }

    private fun nodeTextEquals(node: AccessibilityNodeInfo, target: String): Boolean {
        return normalizeText(node.text?.toString()).equals(target, ignoreCase = true) ||
            normalizeText(node.contentDescription?.toString()).equals(target, ignoreCase = true)
    }

    /** 去掉所有空白：中文界面常在文字里插空格/换行，逐字比较必然命不中 */
    private fun normalizeText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (c in raw) if (!c.isWhitespace()) sb.append(c)
        return sb.toString()
    }

    /** 子树所有文字拼接后是否包含目标（处理文字被拆成多个节点的情况） */
    private fun subtreeTextContains(node: AccessibilityNodeInfo, target: String): Boolean {
        val sb = StringBuilder()
        collectSubtreeText(node, sb, 0)
        return normalizeText(sb.toString()).contains(target, ignoreCase = true)
    }

    private fun collectSubtreeText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > MAX_TREE_DEPTH / 2) return
        node.text?.toString()?.let { if (it.isNotBlank()) sb.append(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) sb.append(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSubtreeText(child, sb, depth + 1)
        }
    }

    /** 未命中时导出窗口内可见文字，方便对照目标文字到底写成了什么 */
    private fun visibleTexts(root: AccessibilityNodeInfo, limit: Int = 40): String {
        val out = ArrayList<String>()
        collectVisibleTexts(root, out, limit, 0)
        return if (out.isEmpty()) "（无任何可读文字，该界面可能不向无障碍暴露文字）"
        else out.joinToString(" | ")
    }

    private fun collectVisibleTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        limit: Int,
        depth: Int,
    ) {
        if (depth > MAX_TREE_DEPTH || out.size >= limit) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) return
            val child = node.getChild(i) ?: continue
            collectVisibleTexts(child, out, limit, depth + 1)
        }
    }

    /** 节点边界是否有效（非零尺寸且落在屏幕范围内） */
    private fun hasValidBounds(
        node: AccessibilityNodeInfo,
        rect: android.graphics.Rect,
        screen: android.graphics.Rect
    ): Boolean {
        return try {
            node.getBoundsInScreen(rect)
            rect.width() > 0 && rect.height() > 0 && android.graphics.Rect.intersects(rect, screen)
        } catch (e: Exception) {
            false
        }
    }

    /** 取节点屏幕边界的中心点 */
    private fun centerOf(node: AccessibilityNodeInfo, rect: android.graphics.Rect): PointF {
        node.getBoundsInScreen(rect)
        return PointF(rect.exactCenterX(), rect.exactCenterY())
    }

    /** 计算节点在节点树中的深度 */
    private fun nodeDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0
        var cur = node
        while (true) {
            val p = try { cur.parent } catch (e: Exception) { null } ?: break
            depth++
            cur = p
        }
        return depth
    }

    /** 无障碍服务可用的屏幕范围 */
    private fun screenBounds(): android.graphics.Rect {
        val dm = resources.displayMetrics
        return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
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

    /** 截图能力是否可用（依赖无障碍 takeScreenshot，仅 Android 11 / API 30+） */
    fun isScreenshotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 异步截取当前屏幕，结果回调到主线程；失败或不支持时回调 null。
     * @param onResult 截图 Bitmap（ARGB_8888，可安全像素读取）或 null
     */
    fun takeScreenshotAsync(onResult: (Bitmap?) -> Unit) {
        if (!isScreenshotSupported()) {
            Log.w(TAG, "takeScreenshot 需要 Android 11(API 30) 及以上")
            handler.post { onResult(null) }
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        // 硬件缓冲需 close，先转成软件 ARGB Bitmap 供匹配引擎读取
                        val hwBuffer = screenshot.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hwBuffer.close()
                        }
                        handler.post { onResult(bitmap) }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截图失败 errorCode=$errorCode")
                        handler.post { onResult(null) }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot 调用异常", e)
            handler.post { onResult(null) }
        }
    }
}
