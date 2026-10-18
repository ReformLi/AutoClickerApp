package com.hpu.autoclicker

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮快捷点击服务（新功能）
 *
 * 链路：用户手动点悬浮子按钮 → 无障碍文本匹配 → 拿到坐标 → dispatchGesture 模拟点击
 *
 * 三种状态：
 * - 折叠态：屏幕上只有一个总按钮，零编辑控件；单击 → 展开
 * - 展开态：总按钮 + 已配置子按钮纵向排列；再单击总按钮 → 折叠
 * - 编辑态：双击总按钮进入；出现与旧功能类似的 [+][-][设置][取消]；
 *   “+”弹框新建按钮，实体化到 + 和 - 之间；子按钮长按拖动调换位置，
 *   但始终处于 + 和 - 之间；“取消”退出编辑态
 *
 * 手势状态机：总按钮统一在 OnTouchListener 内处理单击/拖动/双击，
 * 禁止混用三套事件；位移 > touchSlop 判定为拖动，拖动过程中绝不触发识别/点击；
 * 松手边缘吸附（左/右），坐标始终用屏幕绝对坐标。
 */
class QuickClickService : Service() {

    companion object {
        const val ACTION_STOP = "com.hpu.autoclicker.STOP_QUICK_CLICK"

        /** 按钮名称限长规则：含中文 ≤2 字，纯英文/数字 ≤3 字符 */
        private fun quickNameMaxLength(text: CharSequence): Int =
            if (text.any { it.code > 0x2E80 }) 2 else 3

        /** 输入时按语言截断，避免按钮上出现省略号 */
        private val QUICK_NAME_FILTER = InputFilter { source, start, end, dest, dstart, dend ->
            val result = dest.subSequence(0, dstart).toString() +
                source.subSequence(start, end).toString() +
                dest.subSequence(dend, dest.length).toString()
            val max = quickNameMaxLength(result)
            if (result.length <= max) {
                null
            } else {
                val keep = max - (dest.length - (dend - dstart))
                if (keep <= 0) "" else source.subSequence(start, start + keep)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var expandedControls: LinearLayout
    private lateinit var btnMain: ImageView
    private lateinit var lp: WindowManager.LayoutParams

    private var rootAdded = false
    private var isExpanded = false
    private var isEditing = false

    // ===== 手势状态机变量（总按钮） =====
    private var touchSlop = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var initWindowX = 0
    private var initWindowY = 0
    private var isDragging = false
    private var lastMainTapAt = 0L   // 双击判定：两次单击间隔 < 300ms 进入编辑态

    // ===== 子按钮拖动排序变量（编辑态） =====
    private val dragHandler = Handler(Looper.getMainLooper())
    private var dragStartRunnable: Runnable? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragMoved = false        // 长按前超 touchSlop 取消长按计时
    private var dragActive = false       // 长按触发，进入拖动排序
    private var parentTopOnScreen = 0    // expandedControls 顶部屏幕绝对坐标

    // ===== 配置 =====
    private val configs = mutableListOf<QuickClickConfig>()

    // 当前按钮列表来自哪个脚本（从脚本管理加载后记录，保存时预填名称）
    private var currentScriptName: String? = null
    private val lastClickAt = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // 启动为空白编辑态：不自动恢复上次的按钮列表，已保存脚本需在「设置」中手动加载
        configs.clear()

        root = LayoutInflater.from(this).inflate(R.layout.quick_floating_root, null) as LinearLayout
        expandedControls = root.findViewById(R.id.quickExpandedControls)
        btnMain = root.findViewById(R.id.quickBtnMain)

        setupMainButton()

        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE：不抢目标应用输入焦点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        lp.gravity = Gravity.START or Gravity.TOP   // x/y 即屏幕绝对坐标
        lp.x = 0
        lp.y = (getScreenHeight() - root.measuredHeight) / 2

        try {
            windowManager.addView(root, lp)
            rootAdded = true
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "addView 失败", e)
        }

        startForegroundIfNeeded()

        // 新功能启动即进入编辑态，用户无需双击主按钮即可直接添加按钮
        enterEditMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 通知 Activity 服务已停止
        sendBroadcast(Intent("com.hpu.autoclicker.SERVICE_STOPPED"))
        dragHandler.removeCallbacksAndMessages(null)
        if (rootAdded) {
            try {
                windowManager.removeView(root)
            } catch (e: Exception) {
                android.util.Log.e("QuickClick", "removeView 失败", e)
            }
            rootAdded = false
        }
        super.onDestroy()
    }

    // ==================== 总按钮（样式与旧功能一致） ====================

    /**
     * 总按钮手势状态机：单击展开/折叠、双击进编辑态、位移超阈值判定为拖动。
     * 不使用 onClick/LongClick，避免事件互相吞。
     */
    private fun setupMainButton() {
        btnMain.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX           // 屏幕绝对坐标，防多窗口偏移
                    downRawY = event.rawY
                    initWindowX = lp.x
                    initWindowY = lp.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                        isDragging = true           // 超过 touchSlop 立即进入拖动态
                    }
                    if (isDragging) {
                        lp.x = initWindowX + dx.toInt()
                        lp.y = (initWindowY + dy.toInt())
                            .coerceIn(0, (getScreenHeight() - root.height).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(root, lp)  // 跟手移动，不重建 View
                        } catch (e: Exception) {
                            android.util.Log.e("QuickClick", "updateViewLayout 失败", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            snapToEdge()            // 拖动结束：吸附最近左/右边缘，y 保持
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handleMainTap()             // 未拖动且非取消 → 单击/双击判定
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** 单击/双击判定：双击（<300ms）进编辑态；单击按状态展开/折叠/退出编辑 */
    private fun handleMainTap() {
        val now = System.currentTimeMillis()
        if (now - lastMainTapAt < ViewConfiguration.getDoubleTapTimeout()) {
            lastMainTapAt = 0
            enterEditMode()                         // 双击 → 编辑态
        } else {
            lastMainTapAt = now
            when {
                isEditing -> exitEditMode()         // 编辑态单击总按钮 → 退出编辑（保持展开）
                isExpanded -> collapse()            // 展开态单击 → 折叠
                else -> expand()                    // 折叠态单击 → 展开
            }
        }
    }

    /** 拖动结束：吸附到最近的左/右屏幕边缘 */
    private fun snapToEdge() {
        val screenW = getScreenWidth()
        val viewW = root.width.coerceAtLeast(root.measuredWidth)
        lp.x = if (lp.x + viewW / 2 < screenW / 2) 0 else (screenW - viewW).coerceAtLeast(0)
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "snapToEdge 失败", e)
        }
    }

    // ==================== 展开 / 折叠 / 编辑态 ====================

    private fun expand() {
        // 无障碍服务未开启/被系统杀掉：总按钮变灰并提示
        val available = AutoClickerAccessibilityService.instance != null
        setMainAvailable(available)
        if (!available) {
            Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
        }
        // 尚无子按钮时展开无可展示内容，直接进入编辑态让用户先添加按钮
        if (configs.isEmpty()) {
            enterEditMode()
            return
        }
        isExpanded = true
        rebuildExpanded()
        expandedControls.visibility = View.VISIBLE
        clampWindowY()
    }

    private fun collapse() {
        expandedControls.removeAllViews()
        expandedControls.visibility = View.GONE
        isExpanded = false
        isEditing = false   // 折叠即退出编辑态，编辑控件不常驻
    }

    /** 重建展开区；编辑态结构：[+] 子按钮列表 [-] [设置] [取消] */
    private fun rebuildExpanded() {
        expandedControls.removeAllViews()
        if (isEditing) {
            expandedControls.addView(
                buildControlRow(R.drawable.ic_add, "添加") { showConfigDialog() }
            )
        }
        configs.forEach { expandedControls.addView(buildChildRow(it)) }
        if (isEditing) {
            expandedControls.addView(
                buildControlRow(R.drawable.ic_remove, "减少") { removeLastConfig() }
            )
            expandedControls.addView(
                buildControlRow(R.drawable.ic_settings, "设置") { showScriptManagerDialog() }
            )
            expandedControls.addView(
                buildControlRow(R.drawable.ic_exit, "取消") { exitEditMode() }
            )
        }
    }

    private fun enterEditMode() {
        if (!isExpanded) {
            isExpanded = true
            expandedControls.visibility = View.VISIBLE
        }
        isEditing = true
        lastMainTapAt = 0L   // 重置双击计时，避免刚进编辑态的下一次点击被误判为双击
        setMainAvailable(true)
        rebuildExpanded()
        clampWindowY()
    }

    /**
     * 退出编辑态：重置双击计时避免下一次点击被误判为双击；
     * 无子按钮时回到折叠态（只剩总按钮），保证编辑态始终可退出。
     */
    private fun exitEditMode() {
        isEditing = false
        lastMainTapAt = 0L
        if (configs.isEmpty()) {
            collapse()
        } else {
            rebuildExpanded()
            clampWindowY()
        }
    }

    /** 旧功能式“-”：删除最后一个子按钮 */
    private fun removeLastConfig() {
        if (configs.isEmpty()) {
            Toast.makeText(this, "没有可删除的按钮", Toast.LENGTH_SHORT).show()
            return
        }
        val removed = configs.removeAt(configs.size - 1)
        rebuildExpanded()
        clampWindowY()
        Toast.makeText(this, "已删除「${removed.name}」", Toast.LENGTH_SHORT).show()
    }

    // ==================== 行视图构建 ====================

    /** 编辑态控制行：与旧功能一致的 32dp 图标行（+/-/设置/取消） */
    private fun buildControlRow(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
        val row = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
            ).apply { topMargin = dp(4) }
            setImageResource(iconRes)
            contentDescription = desc
            background = getDrawableCompat(R.drawable.bg_floating_button)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onClick() }
        }
        return row
    }

    /**
     * 子按钮行：非编辑态点击触发识别；编辑态长按拖动排序（始终处于 + 和 - 之间）
     */
    private fun buildChildRow(cfg: QuickClickConfig): TextView {
        val row = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
            ).apply { topMargin = dp(4) }
            text = cfg.name
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true   // 名称已限长（中文≤3、英文/数字≤4），完整展示不用省略号
            background = getDrawableCompat(R.drawable.bg_floating_button)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            tag = cfg
        }
        if (isEditing) {
            row.setOnTouchListener { v, event -> handleChildDragTouch(v, event) }
        } else {
            row.setOnClickListener { onChildClicked(cfg) }
        }
        return row
    }

    private fun getDrawableCompat(resId: Int) = resources.getDrawable(resId, theme)

    // ==================== 子按钮长按拖动排序 ====================

    /** 编辑态子按钮触摸：长按（默认 500ms）进入拖动，未长按则忽略（编辑态不触发点击） */
    private fun handleChildDragTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragMoved = false
                dragActive = false
                val loc = IntArray(2)
                expandedControls.getLocationOnScreen(loc)
                parentTopOnScreen = loc[1]
                dragStartRunnable = Runnable {
                    dragActive = true
                    view.elevation = dp(6).toFloat()   // 拖动中的按钮浮起
                }.also { dragHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragActive && !dragMoved && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragMoved = true                   // 一移动就取消长按/拖动判定
                    dragStartRunnable?.let { dragHandler.removeCallbacks(it) }
                }
                if (dragActive) performChildDrag(view, event)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragStartRunnable?.let { dragHandler.removeCallbacks(it) }
                dragStartRunnable = null
                if (dragActive) {
                    view.translationY = 0f
                    view.elevation = 0f
                    syncConfigsFromViews()             // 落位后按视图顺序持久化
                    rebuildExpanded()
                    clampWindowY()
                }
                dragActive = false
                dragMoved = false
                true
            }
            else -> false
        }

    /**
     * 拖动排序核心：子按钮跟随手指上下移动，中心越过相邻子按钮时交换位置；
     * 只在 +（索引 0）和 -（倒数第 3）之间的子按钮区间内移动
     */
    private fun performChildDrag(view: View, event: MotionEvent) {
        val parent = expandedControls
        val first = 1                      // 索引 0 是 “+”
        val last = parent.childCount - 4   // 倒数 3 个是 “-”“设置”“取消”
        if (last < first) return
        val idx = parent.indexOfChild(view)
        if (idx < first || idx > last) return

        val dy = event.rawY - dragStartRawY
        view.translationY = dy

        // 手指相对父容器的 Y 坐标（屏幕绝对坐标换算，防多窗口偏移）
        val fingerY = event.rawY - parentTopOnScreen

        if (idx > first) {
            val above = parent.getChildAt(idx - 1)
            if (fingerY < above.top + above.height / 2f) {
                parent.removeView(view)
                parent.addView(view, idx - 1)
                view.translationY = dy + dp(36)   // 上移一格后补偿，保持视图跟手
                return
            }
        }
        if (idx < last) {
            val below = parent.getChildAt(idx + 1)
            if (fingerY > below.top + below.height / 2f) {
                parent.removeView(view)
                parent.addView(view, idx + 1)
                view.translationY = dy - dp(36)   // 下移一格后补偿，保持视图跟手
                return
            }
        }
    }

    /** 拖动落位：按 expandedControls 中子按钮的当前顺序回写配置并持久化 */
    private fun syncConfigsFromViews() {
        val parent = expandedControls
        val first = 1
        val last = parent.childCount - 4
        if (last < first) return
        val ordered = mutableListOf<QuickClickConfig>()
        for (i in first..last) {
            (parent.getChildAt(i).tag as? QuickClickConfig)?.let { ordered.add(it) }
        }
        configs.clear()
        configs.addAll(ordered)
    }

    // ==================== 识别 → 点击链路 ====================

    /** 子按钮点击：防抖 → 文本匹配 → dispatchGesture 点击 */
    private fun onChildClicked(cfg: QuickClickConfig) {
        if (isEditing) return   // 编辑态不触发识别/点击

        // 防抖：间隔内不重复执行同一按钮（间隔可在“设置”中调整）
        val now = System.currentTimeMillis()
        val debounce = QuickClickStore.getDebounce(this)
        val last = lastClickAt[cfg.name] ?: 0L
        if (now - last < debounce) return
        lastClickAt[cfg.name] = now

        when (cfg.type) {
            QuickClickConfig.TYPE_TEXT -> executeTextClick(cfg)
            QuickClickConfig.TYPE_IMAGE -> Toast.makeText(this, "图像识别即将支持", Toast.LENGTH_SHORT).show()
            QuickClickConfig.TYPE_COORD -> Toast.makeText(this, "坐标点击即将支持", Toast.LENGTH_SHORT).show()
        }
    }

    /** 文本匹配 → 拿坐标 → 模拟点击（整条链路 try-catch，不崩溃） */
    private fun executeTextClick(cfg: QuickClickConfig) {
        try {
            val svc = AutoClickerAccessibilityService.instance
            if (svc == null) {
                setMainAvailable(false)
                Toast.makeText(this, "无障碍服务未连接，请到系统设置重新开启", Toast.LENGTH_LONG).show()
                return
            }
            // rootInActiveWindow 为 null 直接兜底提示，不狂点
            if (svc.rootInActiveWindow == null) {
                Toast.makeText(this, "无法获取当前界面内容，请重试", Toast.LENGTH_SHORT).show()
                return
            }

            val pt = svc.findTargetByText(cfg.text)
            if (pt == null) {
                Toast.makeText(this, "未找到目标「${cfg.text}」", Toast.LENGTH_SHORT).show()
                return
            }

            // 坐标用屏幕绝对坐标（findTargetByText 返回 getBoundsInScreen 中心点）
            val duration = QuickClickStore.getClickDuration(this).toLong()
            svc.performClick(pt.x, pt.y, duration) { ok ->
                if (!ok) {
                    Toast.makeText(this, "点击被系统取消（可能被 ROM 拦截）", Toast.LENGTH_SHORT).show()
                } else if (cfg.autoCollapse) {
                    collapse()   // 点击后自动收起悬浮窗
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "点击链路异常", e)
            Toast.makeText(this, "执行失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /** 无障碍断连时总按钮变灰（半透明） */
    private fun setMainAvailable(available: Boolean) {
        btnMain.alpha = if (available) 1f else 0.4f
    }

    // ==================== “+”新建配置弹框 ====================

    /** “+”弹框：填写必要设置 → 点“新建”→ 实体化到 + 和 - 之间 */
    private fun showConfigDialog() {
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_quick_click_config, null)
            val etName = view.findViewById<EditText>(R.id.etQuickName)
            val etText = view.findViewById<EditText>(R.id.etQuickText)
            val swAuto = view.findViewById<CheckBox>(R.id.swQuickAutoCollapse)
            // 名称限长：含中文 ≤3 字、纯英文/数字 ≤4 字符，保证按钮上完整显示
            etName.filters = arrayOf(QUICK_NAME_FILTER)

            val dialog = AlertDialog.Builder(this)
                .setTitle("新建快捷按钮")
                .setView(view)
                .setPositiveButton("新建", null)
                .setNegativeButton("取消", null)
                .create()

            dialog.setOnShowListener {
                // 手动接管新建按钮：校验不通过时不关闭弹窗
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val text = etText.text.toString().trim()
                    if (text.isEmpty()) {
                        Toast.makeText(this, "请输入目标文字", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val name = limitQuickName(
                        etName.text.toString().trim().ifEmpty { "按钮${configs.size + 1}" }
                    )
                    // 新按钮插到列表末尾：编辑态视图中即位于 + 和 - 之间
                    configs.add(
                        QuickClickConfig(
                            name = name,
                            type = QuickClickConfig.TYPE_TEXT,
                            text = text,
                            autoCollapse = swAuto.isChecked
                        )
                    )
                    rebuildExpanded()
                    clampWindowY()
                    Toast.makeText(this, "已新建「$name」", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            // Service 弹窗必须挂到悬浮窗层，否则 BadTokenException（与旧功能处理一致）
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
            )
            dialog.show()
            dialog.window?.setLayout(
                (getScreenWidth() * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开配置弹框失败", e)
        }
    }

    // ==================== “设置” → 脚本管理弹框 ====================

    /**
     * 脚本管理：另存为 / 加载切换 / 重命名 / 删除。
     * 悬浮框上的按钮列表只存在于内存中（启动为空白编辑态），
     * 只有另存为命名后的脚本才会被保存，下次启动可在本面板加载。
     */
    private fun showScriptManagerDialog() {
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_script_manager, null)
            val btnSaveAs = view.findViewById<Button>(R.id.btnQuickSaveAs)
            val tvEmpty = view.findViewById<TextView>(R.id.tvQuickScriptEmpty)
            val listBox = view.findViewById<LinearLayout>(R.id.llQuickScriptList)

            val dialog = AlertDialog.Builder(this)
                .setTitle("脚本管理")
                .setView(view)
                .setNegativeButton("关闭", null)
                .create()

            fun refresh() {
                listBox.removeAllViews()
                val scripts = QuickClickStore.loadScripts(this)
                tvEmpty.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
                scripts.forEach { listBox.addView(buildScriptRow(it, dialog) { refresh() }) }
            }

            btnSaveAs.setOnClickListener {
                if (configs.isEmpty()) {
                    Toast.makeText(this, "当前没有按钮，请先添加", Toast.LENGTH_SHORT).show()
                } else {
                    // 从脚本加载来的按钮，预填该脚本名（可改可直接保存）
                    showInputDialog("保存脚本", currentScriptName ?: "", "输入脚本名称", 12) { scriptName ->
                        if (QuickClickStore.loadScripts(this).any { it.name == scriptName }) {
                            // 名称已存在：确认是否覆盖
                            confirmOverwrite(scriptName) {
                                QuickClickStore.saveScript(this, scriptName, configs)
                                currentScriptName = scriptName
                                Toast.makeText(this, "已覆盖脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        } else {
                            QuickClickStore.saveScript(this, scriptName, configs)
                            currentScriptName = scriptName
                            Toast.makeText(this, "已保存脚本「$scriptName」", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                }
            }

            refresh()
            showOverlayDialog(dialog)
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "打开脚本管理失败", e)
        }
    }

    /** 脚本列表行：点名称加载；右侧「改名」「删除」 */
    private fun buildScriptRow(script: QuickScript, dialog: AlertDialog, onChanged: () -> Unit): View {
        val nameView = TextView(this).apply {
            text = script.name
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            isSingleLine = true
            setPadding(dp(4), 0, 0, 0)
        }
        val btnRename = TextView(this).apply {
            text = "改名"
            setTextColor(getColor(R.color.primary))
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val btnDelete = TextView(this).apply {
            text = "删除"
            setTextColor(getColor(R.color.danger))
            textSize = 14f
            setPadding(dp(12), dp(6), dp(4), dp(6))
        }

        // 点名称 = 加载/切换：用脚本内容替换当前悬浮框上的按钮
        nameView.setOnClickListener {
            when {
                script.configs.isEmpty() ->
                    Toast.makeText(this, "脚本「${script.name}」没有按钮", Toast.LENGTH_SHORT).show()
                // 悬浮框上已有按钮：先确认覆盖，避免未保存的节点被直接替换掉
                configs.isNotEmpty() -> confirmReplaceByScript(script, dialog)
                else -> loadScript(script, dialog)
            }
        }
        btnRename.setOnClickListener {
            showInputDialog("重命名脚本", script.name, "输入新名称", 12) { newName ->
                if (QuickClickStore.renameScript(this, script.name, newName)) {
                    if (currentScriptName == script.name) currentScriptName = newName
                    onChanged()
                    Toast.makeText(this, "已重命名为「$newName」", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "脚本名「$newName」已存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除脚本")
                .setMessage("确定删除脚本「${script.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    QuickClickStore.deleteScript(this, script.name)
                    onChanged()
                    Toast.makeText(this, "已删除脚本「${script.name}」", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .create()
                .also { showOverlayDialog(it) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            )
            addView(nameView)
            addView(btnRename)
            addView(btnDelete)
        }
    }

    /** 载入脚本：替换悬浮框上的按钮，并关闭脚本管理弹框 */
    private fun loadScript(script: QuickScript, dialog: AlertDialog) {
        configs.clear()
        configs.addAll(script.configs)
        currentScriptName = script.name
        rebuildExpanded()
        clampWindowY()
        dialog.dismiss()
        Toast.makeText(this, "已加载脚本「${script.name}」", Toast.LENGTH_SHORT).show()
    }

    /** 悬浮框上已有按钮时，加载脚本前确认覆盖 */
    private fun confirmReplaceByScript(script: QuickScript, dialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("加载脚本")
            .setMessage("当前按键节点未保存，加载脚本「${script.name}」将覆盖它们，是否覆盖？")
            .setPositiveButton("覆盖") { _, _ -> loadScript(script, dialog) }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
    }

    /** 同名脚本覆盖确认 */
    private fun confirmOverwrite(scriptName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("覆盖脚本")
            .setMessage("脚本「$scriptName」已存在，是否覆盖？")
            .setPositiveButton("覆盖") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
            .also { showOverlayDialog(it) }
    }

    /**
     * Service 内弹窗统一挂到悬浮窗层（否则 BadTokenException，与旧功能处理一致），
     * 并统一设置宽度
     */
    private fun showOverlayDialog(dialog: AlertDialog, widthRatio: Float = 0.9f) {
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
        dialog.window?.setLayout(
            (getScreenWidth() * widthRatio).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 单行输入弹框（另存为 / 重命名共用） */
    private fun showInputDialog(
        title: String,
        initial: String,
        hint: String,
        maxLen: Int,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(initial)
            setHint(hint)
            isSingleLine = true
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLen))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onConfirm(value)
                dialog.dismiss()
            }
        }
        showOverlayDialog(dialog)
    }

    /** 兜底截断按钮名称（防止绕过输入过滤器） */
    private fun limitQuickName(name: String): String {
        val max = quickNameMaxLength(name)
        return if (name.length > max) name.substring(0, max) else name
    }

    // ==================== 前台服务 / 工具 ====================

    /** 前台服务：维持悬浮窗与识别链路存活 */
    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "quick_click"
            val channel = NotificationChannel(
                channelId, "快捷点击", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

            val stopIntent = Intent(this, QuickClickService::class.java).apply {
                action = ACTION_STOP
            }
            val pendingStop = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("悬浮快捷点击运行中")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pendingStop)
                .build()
            startForeground(2, notification)
        }
    }

    /** 展开区在主按钮下方，展开/编辑后校正窗口位置避免超出屏幕底部 */
    private fun clampWindowY() {
        root.post {
            if (!rootAdded) return@post
            val maxY = (getScreenHeight() - root.height).coerceAtLeast(0)
            val newY = lp.y.coerceIn(0, maxY)
            if (newY != lp.y) {
                lp.y = newY
                try {
                    windowManager.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    android.util.Log.e("QuickClick", "clampWindowY 失败", e)
                }
            }
        }
    }

    private fun getScreenWidth(): Int {
        val p = Point()
        windowManager.defaultDisplay.getRealSize(p)
        return p.x
    }

    private fun getScreenHeight(): Int {
        val p = Point()
        windowManager.defaultDisplay.getRealSize(p)
        return p.y
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
