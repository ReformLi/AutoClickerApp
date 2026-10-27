package com.hpu.autoclicker

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.core.mode.AppMode
import com.hpu.autoclicker.feature.clicker.FloatingWindowService
import com.hpu.autoclicker.feature.main.MainScreen
import com.hpu.autoclicker.feature.main.MainUiState
import com.hpu.autoclicker.feature.quickclick.QuickClickService
import com.hpu.autoclicker.feature.recorder.RecorderService
import com.hpu.autoclicker.core.theme.AutoClickerTheme
import com.hpu.autoclicker.core.util.DebugLog

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001

        // 功能模式持久化：clicker / quick_click / recorder；
        // 旧版本的 old / new 由 AppMode.fromPref 自动迁移
        private const val MODE_PREFS = "feature_mode"
        private const val MODE_KEY = "mode"

        /** 启停 / 切模块两次点击之间的最短间隔 */
        private const val TOGGLE_GAP_MS = 600L
    }

    /** 切模块时等待旧服务先移除悬浮窗，再启动目标模块 */
    private val modeSwitchHandler = Handler(Looper.getMainLooper())

    /**
     * Compose UI 状态。
     * 原来逐个 findViewById + setText/setBackgroundTintList 的刷新方式，
     * 迁移为「单一状态源」驱动 Compose 重组（业务逻辑本身不变）。
     */
    private var uiState by mutableStateOf(MainUiState())

    private var isServiceRunning = false

    /**
     * 启停/切模块按钮的连点保护时刻。
     * 悬浮窗服务创建与销毁都是异步的：连点「启动/停止」会交叉发出
     * startForegroundService 与 stopService，出现「按钮显示已停止但悬浮窗还在」；
     * 连点切模块同理会让两个悬浮窗叠着起来。
     */
    private var lastToggleAt = 0L

    /** 距上次启停/切模块是否已过了最短间隔；过了就顺带记下本次时刻 */
    private fun toggleAccepted(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastToggleAt < TOGGLE_GAP_MS) return false
        lastToggleAt = now
        return true
    }

    /**
     * 通知栏「停止」入口依赖前台服务通知，Android 13(API 33) 起需要用户授权。
     * 拒绝授权不影响悬浮窗本身运行，因此不阻断启动流程。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("未授权通知，前台服务通知栏入口将不可见")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.syncFrom(this)
        // 全面屏适配：窗口铺满到系统栏之下（状态栏/导航栏/刘海区域），
        // 由 Compose 侧按 safeDrawing 内边距避让，避免全面屏设备上状态栏遮盖页面内容。
        enableEdgeToEdge()
        // 由 setContentView(R.layout.activity_main) 迁移为 Compose setContent
        setContent {
            AutoClickerTheme {
                MainScreen(
                    state = uiState,
                    onRequestPermission = { requestAllPermissions() },
                    onRefresh = {
                        refreshPermissionAndServiceState()
                        toast("状态已刷新")
                    },
                    onSwitchMode = { mode -> requestSwitchMode(mode) },
                    onToggleService = {
                        if (toggleAccepted()) {
                            if (isServiceRunning) stopFloatingService() else startFloatingService()
                            updateToggleButton()
                        }
                    },
                )
            }
        }

        val filter = IntentFilter("com.hpu.autoclicker.SERVICE_STOPPED")
        ContextCompat.registerReceiver(this, serviceStoppedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        modeSwitchHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(serviceStoppedReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionAndServiceState()
    }

    /**
     * 切换功能模块。
     *
     * 规则：
     * - 权限不齐（悬浮窗 / 无障碍）直接失败提示，不改变模式，主页选中态不变；
     * - 当前悬浮窗在运行 → 先停旧服务（录点器会借此保存草稿/回放现场），再自动启动目标模块；
     * - 录点器切回时由它自己弹「草稿三选一」，不自动恢复录制；
     *   回放现场只恢复为暂停态，不自动注入。
     */
    private fun requestSwitchMode(target: AppMode) {
        val current = currentMode()
        if (current == target) return

        if (!checkOverlayPermission()) {
            toast("请先授予悬浮窗权限再切换模块")
            return
        }
        if (!checkAccessibilityPermission()) {
            toast("无障碍服务未开启，无法切换模块")
            return
        }
        // 权限提示可以连点重试；真正要动服务了才吃防抖，避免两个模块的悬浮窗叠着起来
        if (!toggleAccepted()) return

        val wasRunning = isServiceRunning || isFloatingServiceRunning(serviceClassOf(current))
        if (wasRunning) {
            stopService(Intent(this, serviceClassOf(current)))
            isServiceRunning = false
        }

        getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .edit().putString(MODE_KEY, target.prefValue).apply()
        refreshPermissionAndServiceState()

        if (wasRunning) {
            // 等旧服务的悬浮窗先移除，再启动目标模块，避免两个悬浮窗短暂叠在一起
            modeSwitchHandler.postDelayed({
                if (currentMode() == target) {
                    startFloatingService(announce = false)
                    toast("已切换到${target.displayName}，悬浮窗已自动启动")
                }
            }, 250L)
        } else {
            toast("已切换到${target.displayName}")
        }
    }

    /** 当前功能模块，默认连点器 */
    private fun currentMode(): AppMode =
        AppMode.fromPref(
            getSharedPreferences(MODE_PREFS, MODE_PRIVATE).getString(MODE_KEY, null)
        )

    // 广播接收器
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.hpu.autoclicker.SERVICE_STOPPED") {
                isServiceRunning = false
                updateToggleButton()
            }
        }
    }

    /**
     * 依次检查并请求悬浮窗权限和无障碍权限
     */
    private fun requestAllPermissions() {
        // 1. 检查悬浮窗权限
        if (!checkOverlayPermission()) {
            // 没有悬浮窗权限，跳转系统悬浮窗设置
            requestOverlayPermission()
            return
        }

        // 2. 悬浮窗权限已满足，检查无障碍权限
        if (!checkAccessibilityPermission()) {
            // 没有无障碍权限，引导开启
            openAccessibilitySettings()
            return
        }

        // 3. 两个权限都已获取
        toast("所有权限已获取")
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        } else {
            toast("系统默认支持悬浮窗")
        }
    }

    /**
     * 跳转到系统无障碍设置页面，引导用户手动开启本应用的无障碍服务
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到【${getString(R.string.app_name)}】并开启无障碍服务", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            refreshPermissionAndServiceState()
        }
    }

    /**
     * 刷新所有权限状态、按钮启用条件和颜色
     * （迁移后仅计算状态并推送给 Compose，由 UI 层派生出文案/颜色/可用性）
     */
    private fun refreshPermissionAndServiceState() {
        val hasOverlay = checkOverlayPermission()
        val hasAccessibility = checkAccessibilityPermission()
        val allPermissionsGranted = hasOverlay && hasAccessibility

        if (allPermissionsGranted) {
            isServiceRunning = isFloatingServiceRunning(currentServiceClass())
        } else {
            // 权限不全时不检测服务状态（与原逻辑一致：按钮禁用并显示「启动悬浮窗」）
            isServiceRunning = false
        }

        uiState = MainUiState(
            mode = currentMode(),
            hasOverlayPermission = hasOverlay,
            hasAccessibilityPermission = hasAccessibility,
            isServiceRunning = isServiceRunning,
        )
    }

    /** 按当前模式返回对应的前台服务 */
    private fun currentServiceClass(): Class<*> = serviceClassOf(currentMode())

    private fun serviceClassOf(mode: AppMode): Class<*> = when (mode) {
        AppMode.CLICKER -> FloatingWindowService::class.java
        AppMode.QUICK_CLICK -> QuickClickService::class.java
        AppMode.RECORDER -> RecorderService::class.java
    }

    /**
     * 检查悬浮窗权限是否授权
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * 检查无障碍服务是否开启
     */
    private fun checkAccessibilityPermission(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceName = packageName + "/" + AutoClickerAccessibilityService::class.java.name
        return enabledServices?.contains(serviceName) == true
    }

    /**
     * 启动悬浮窗服务（按当前模式路由：连点器 / 代点器 / 录点器）
     */
    private fun startFloatingService(announce: Boolean = true) {
        requestNotificationPermissionIfNeeded()
        val intent = Intent(this, currentServiceClass())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        if (announce) toast("悬浮窗已启动")
        updateToggleButton()
    }

    /** Android 13+ 运行时申请通知权限，保证前台服务通知（含「停止」按钮）可见 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 停止悬浮窗服务（按当前模式路由）
     */
    private fun stopFloatingService() {
        val intent = Intent(this, currentServiceClass())
        stopService(intent)
        isServiceRunning = false
        toast("悬浮窗已关闭")
        updateToggleButton()
    }

    /** 仅同步「服务运行态」到 Compose（按钮文案/颜色由状态派生） */
    private fun updateToggleButton() {
        uiState = uiState.copy(isServiceRunning = isServiceRunning)
    }

    private fun isFloatingServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
