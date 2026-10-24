package com.hpu.autoclicker.core.ui

import android.app.AlertDialog
import android.app.Service
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.hpu.autoclicker.core.theme.AutoClickerTheme

/**
 * Service 内弹框的统一宿主 —— 两个悬浮窗服务（连点器 / 代点器）共用。
 *
 * 为什么仍用平台 AlertDialog：Service 没有 Activity 上下文，
 * Compose 的 Dialog / Popup 需要 Activity 的 window token，否则 BadTokenException；
 * 平台 AlertDialog 能挂 TYPE_APPLICATION_OVERLAY 窗口类型，正好满足悬浮窗场景。
 *
 * 窗口背景设为透明：内容用统一弹框面板（[com.hpu.autoclicker.core.components.AppDialogPanel]）
 * 自绘圆角与底色，若保留平台面板底色会叠成两层圆角白卡。
 */
object ServiceDialogs {

    /**
     * 弹出一个统一外框的对话框。
     *
     * @param widthRatio 窗口宽度占屏宽比例（弹框内部靠 fillMaxWidth 铺满）
     * @param onDismiss 弹框关闭时回调（用于释放 Compose 宿主）
     */
    fun show(
        service: Service,
        host: ServiceComposeHost,
        widthRatio: Float = 0.9f,
        onDismiss: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ): AlertDialog {
        val compose = ComposeView(service)
        host.attach(compose)
        compose.setContent {
            AutoClickerTheme { content() }
        }

        val dialog = AlertDialog.Builder(service)
            .setView(compose)
            .setCancelable(true)
            .create()

        // 窗口根视图挂宿主 Owner（必须在 show() 之前，原因见 ServiceComposeHost.attachWindowRoot）
        dialog.window?.decorView?.let { host.attachWindowRoot(it) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener { onDismiss?.invoke() }

        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
        // overlay 弹框默认带 FLAG_ALT_FOCUSABLE_IM，输入框会拿到焦点却不弹软键盘
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setLayout(
            (screenWidth(service) * widthRatio).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.stripPlatformPanel()
        return dialog
    }

    private fun screenWidth(service: Service): Int {
        val p = Point()
        val wm = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p.x
    }
}

/**
 * 去掉平台 AlertDialog 自带的面板底 —— 它的 parentPanel 用了
 * `@android:drawable/panel_background`（圆角 9-patch 描边 + 透明内芯），
 * 与我们自绘的圆角面板叠在一起会变成「双层框」，
 * 在 Activity 场景尤其明显（浮层窗口不渲染平台描边，所以之前没暴露）。
 *
 * `android.R.id` 未暴露 parentPanel，只能按 id 资源名查找。
 */
internal fun AlertDialog.stripPlatformPanel() {
    val decor = window?.decorView ?: return
    decor.findPanelContainer()?.setBackgroundDrawable(null)
}

private fun View.findPanelContainer(): View? {
    if (idName() == "parentPanel") return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).findPanelContainer()?.let { return it }
        }
    }
    return null
}

/** 取 id 对应的资源名（框架的 parentPanel 未在 android.R.id 暴露，只能这样定位） */
private fun View.idName(): String? = try {
    if (id == View.NO_ID) null else resources.getResourceEntryName(id)
} catch (e: Exception) {
    null
}
