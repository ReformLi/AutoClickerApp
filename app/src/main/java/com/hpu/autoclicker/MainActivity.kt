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
    }

    private lateinit var btnRequestPermission: Button
    private lateinit var btnCheckPermission: Button
    private lateinit var btnToggleService: Button
    private lateinit var tvStatus: TextView

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRequestPermission = findViewById(R.id.btnRequestPermission)
        btnCheckPermission = findViewById(R.id.btnCheckPermission)
        btnToggleService = findViewById(R.id.btnToggleService)
        tvStatus = findViewById(R.id.tvStatus)

        setupClickListeners()

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

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopFloatingService()
            } else {
                startFloatingService()
            }
            updateToggleButton()
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
            btnRequestPermission.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            btnRequestPermission.text = "权限已获取"
        } else {
            btnRequestPermission.backgroundTintList = getColorStateList(android.R.color.holo_red_light)
            btnRequestPermission.text = "获取权限"
        }

        // 启动悬浮窗按钮：只有两个权限都满足才可用
        val allPermissionsGranted = hasOverlay && hasAccessibility
        btnToggleService.isEnabled = allPermissionsGranted

        if (!allPermissionsGranted) {
            btnToggleService.backgroundTintList = getColorStateList(android.R.color.darker_gray)
            btnToggleService.text = "启动悬浮窗"
        } else {
            isServiceRunning = isFloatingServiceRunning()
            updateToggleButton()
        }

        // 状态提示
        val statusText = when {
            !allPermissionsGranted -> "请先获取所有权限"
            isServiceRunning -> "悬浮窗正在运行"
            else -> "悬浮窗未启动"
        }
        tvStatus.text = "当前状态：$statusText"
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
     * 启动悬浮窗服务
     */
    private fun startFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
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
     * 停止悬浮窗服务
     */
    private fun stopFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        stopService(intent)
        isServiceRunning = false
        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        updateToggleButton()
        tvStatus.text = "当前状态：悬浮窗未启动"
    }

    private fun updateToggleButton() {
        if (isServiceRunning) {
            btnToggleService.text = "关闭悬浮窗"
            btnToggleService.backgroundTintList = getColorStateList(android.R.color.holo_red_light)
        } else {
            btnToggleService.text = "启动悬浮窗"
            btnToggleService.backgroundTintList = getColorStateList(android.R.color.holo_blue_light)
        }
    }

    private fun isFloatingServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (FloatingWindowService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}