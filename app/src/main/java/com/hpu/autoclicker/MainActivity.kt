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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001

        // 新旧功能模式持久化：old = 旧功能（默认），new = 悬浮快捷点击
        private const val MODE_PREFS = "feature_mode"
        private const val MODE_KEY = "mode"
        private const val MODE_OLD = "old"
        private const val MODE_NEW = "new"
    }

    private lateinit var btnRequestPermission: Button
    private lateinit var btnCheckPermission: Button
    private lateinit var tvModeOld: TextView
    private lateinit var tvModeNew: TextView
    private lateinit var btnToggleService: Button
    private lateinit var tvStatus: TextView

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRequestPermission = findViewById(R.id.btnRequestPermission)
        btnCheckPermission = findViewById(R.id.btnCheckPermission)
        tvModeOld = findViewById(R.id.tvModeOld)
        tvModeNew = findViewById(R.id.tvModeNew)
        btnToggleService = findViewById(R.id.btnToggleService)
        tvStatus = findViewById(R.id.tvStatus)

        setupClickListeners()
        updateModeSwitchUI()

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

    private fun setupClickListeners() {
        // 获取权限按钮：依次检查悬浮窗权限和无障碍权限
        btnRequestPermission.setOnClickListener {
            requestAllPermissions()
        }

        btnCheckPermission.setOnClickListener {
            refreshPermissionAndServiceState()
            Toast.makeText(this, "状态已刷新", Toast.LENGTH_SHORT).show()
        }

        // 新旧功能切换（分段开关）：选择持久化，下次启动恢复；悬浮窗运行中不允许切换
        tvModeOld.setOnClickListener { requestSwitchMode(toNew = false) }
        tvModeNew.setOnClickListener { requestSwitchMode(toNew = true) }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopFloatingService()
            } else {
                startFloatingService()
            }
            updateToggleButton()
        }
    }

    /**
     * 切换功能模式并持久化；已是目标模式则不处理，悬浮窗运行中提示先关闭
     */
    private fun requestSwitchMode(toNew: Boolean) {
        if (isFeatureModeNew() == toNew) return
        if (isServiceRunning) {
            Toast.makeText(this, "请先关闭悬浮窗再切换模式", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .edit().putString(MODE_KEY, if (toNew) MODE_NEW else MODE_OLD).apply()
        updateModeSwitchUI()
        refreshPermissionAndServiceState()
        Toast.makeText(
            this,
            if (toNew) "已切换到代点器" else "已切换到连点器",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 当前是否为新功能模式（悬浮快捷点击），默认旧功能 */
    private fun isFeatureModeNew(): Boolean {
        return getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
            .getString(MODE_KEY, MODE_OLD) == MODE_NEW
    }

    /** 分段开关选中态：选中段白色滑块 + 蓝字，未选中段透明 + 白字 */
    private fun updateModeSwitchUI() {
        if (isFeatureModeNew()) {
            tvModeNew.setBackgroundResource(R.drawable.bg_mode_switch_thumb)
            tvModeNew.setTextColor(getColor(R.color.primary))
            tvModeOld.background = null
            tvModeOld.setTextColor(getColor(R.color.on_gradient))
        } else {
            tvModeOld.setBackgroundResource(R.drawable.bg_mode_switch_thumb)
            tvModeOld.setTextColor(getColor(R.color.primary))
            tvModeNew.background = null
            tvModeNew.setTextColor(getColor(R.color.on_gradient))
        }
    }

    // 广播接收器
    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.hpu.autoclicker.SERVICE_STOPPED") {
                isServiceRunning = false
                updateToggleButton()
                tvStatus.text = "当前状态：悬浮窗未启动"
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
        Toast.makeText(this, "所有权限已获取", Toast.LENGTH_SHORT).show()
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
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        } else {
            Toast.makeText(this, "系统默认支持悬浮窗", Toast.LENGTH_SHORT).show()
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
     */
    private fun refreshPermissionAndServiceState() {
        val hasOverlay = checkOverlayPermission()
        val hasAccessibility = checkAccessibilityPermission()

        // 获取权限按钮：两个权限都满足 → 绿色，否则红色
        if (hasOverlay && hasAccessibility) {
            btnRequestPermission.backgroundTintList = getColorStateList(R.color.success)
            btnRequestPermission.text = "权限已获取"
        } else {
            btnRequestPermission.backgroundTintList = getColorStateList(R.color.danger)
            btnRequestPermission.text = "获取权限"
        }

        // 启动悬浮窗按钮：只有两个权限都满足才可用
        val allPermissionsGranted = hasOverlay && hasAccessibility
        btnToggleService.isEnabled = allPermissionsGranted

        if (!allPermissionsGranted) {
            btnToggleService.backgroundTintList = getColorStateList(R.color.disabled)
            btnToggleService.setTextColor(getColor(R.color.text_secondary))
            btnToggleService.text = "启动悬浮窗"
        } else {
            isServiceRunning = isFloatingServiceRunning(currentServiceClass())
            updateToggleButton()
        }

        // 状态提示（按模式区分服务名）
        val modeName = if (isFeatureModeNew()) "代点器" else "连点器"
        val statusText = when {
            !allPermissionsGranted -> "请先获取所有权限"
            isServiceRunning -> "悬浮窗正在运行（$modeName）"
            else -> "悬浮窗未启动"
        }
        tvStatus.text = "当前状态：$statusText"
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
        Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
        updateToggleButton()
        tvStatus.text = "当前状态：悬浮窗正在运行"
    }

    /**
     * 停止悬浮窗服务（按当前模式路由）
     */
    private fun stopFloatingService() {
        val intent = Intent(this, currentServiceClass())
        stopService(intent)
        isServiceRunning = false
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        updateToggleButton()
        tvStatus.text = "当前状态：悬浮窗未启动"
    }

    private fun updateToggleButton() {
        if (isServiceRunning) {
            btnToggleService.text = "关闭悬浮窗"
            btnToggleService.backgroundTintList = getColorStateList(R.color.danger)
            btnToggleService.setTextColor(getColor(R.color.white))
        } else {
            // 深蓝渐变背景上用白底蓝字，保证按钮醒目
            btnToggleService.text = "启动悬浮窗"
            btnToggleService.backgroundTintList = getColorStateList(R.color.white)
            btnToggleService.setTextColor(getColor(R.color.primary))
        }
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
}