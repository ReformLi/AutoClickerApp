package com.hpu.autoclicker

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hpu.autoclicker.core.accessibility.AutoClickerAccessibilityService
import com.hpu.autoclicker.feature.clicker.FloatingWindowService
import com.hpu.autoclicker.feature.main.MainScreen
import com.hpu.autoclicker.feature.main.MainUiState
import com.hpu.autoclicker.feature.quickclick.QuickClickService
import com.hpu.autoclicker.core.theme.AutoClickerTheme

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001

        // 新旧功能模式持久化：old = 旧功能（默认），new = 悬浮快捷点击
        private const val MODE_PREFS = "feature_mode"
        private const val MODE_KEY = "mode"
        private const val MODE_OLD = "old"
        private const val MODE_NEW = "new"
    }

    /**
     * Compose UI 状态。
     * 原来逐个 findViewById + setText/setBackgroundTintList 的刷新方式，
     * 迁移为「单一状态源」驱动 Compose 重组（业务逻辑本身不变）。
     */
    private var uiState by mutableStateOf(MainUiState())

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onSwitchMode = { toNew -> requestSwitchMode(toNew) },
                    onToggleService = {
                        if (isServiceRunning) stopFloatingService() else startFloatingService()
                        updateToggleButton()
                    },
                )
            }
        }

        val filter = IntentFilter("com.hpu.autoclicker.SERVICE_STOPPED")
        ContextCompat.registerReceiver(this, serviceStoppedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceStoppedReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionAndServiceState()
    }

    /**
     * 切换功能模式并持久化；已是目标模式则不处理，悬浮窗运行中提示先关闭
     */
    private fun requestSwitchMode(toNew: Boolean) {
        if (isFeatureModeNew() == toNew) return
        if (isServiceRunning) {
            toast("请先关闭悬浮窗再切换模式")
            return
        }
        getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .edit().putString(MODE_KEY, if (toNew) MODE_NEW else MODE_OLD).apply()
        // 原 updateModeSwitchUI() 的分段开关选中态渲染已由 Compose 依据状态派生，此处无需手动切换
        refreshPermissionAndServiceState()
        toast(if (toNew) "已切换到代点器" else "已切换到连点器")
    }

    /** 当前是否为新功能模式（悬浮快捷点击），默认旧功能 */
    private fun isFeatureModeNew(): Boolean {
        return getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .getString(MODE_KEY, MODE_OLD) == MODE_NEW
    }

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
            isNewMode = isFeatureModeNew(),
            hasOverlayPermission = hasOverlay,
            hasAccessibilityPermission = hasAccessibility,
            isServiceRunning = isServiceRunning,
        )
    }

    /** 按当前模式返回对应的前台服务 */
    private fun currentServiceClass(): Class<*> {
        return if (isFeatureModeNew()) QuickClickService::class.java
        else FloatingWindowService::class.java
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
     * 启动悬浮窗服务（按当前模式路由：旧功能 / 新功能快捷点击）
     */
    private fun startFloatingService() {
        val intent = Intent(this, currentServiceClass())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        toast("悬浮窗已启动")
        updateToggleButton()
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
