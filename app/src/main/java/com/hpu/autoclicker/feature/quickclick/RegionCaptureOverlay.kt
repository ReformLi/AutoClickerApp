package com.hpu.autoclicker.feature.quickclick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.theme.OverlayPalette
import com.hpu.autoclicker.core.ui.createOverlayBarBackground
import com.hpu.autoclicker.core.ui.createOverlayButton
import kotlin.math.roundToInt

/**
 * 全屏区域捕获浮层：承载 [RegionSelectView]，底部只保留「确定」。
 * 系统返回键等同取消：关闭浮层并回传 [ResultListener.onCancel]，回到配置弹框。
 *
 * 截图与相册裁剪共用这一套 UI，保证「置灰+网格+框内原图+拖动缩放」体验一致。
 * 用法：[show] 传入底图 → 用户框选 → [ResultListener.onConfirm] 收到裁剪后的 Bitmap。
 * 结果后浮层自动关闭。
 */
class RegionCaptureOverlay(
    private val context: Context,
    private val listener: ResultListener,
) : RegionSelectView.RegionSelectCallback {

    interface ResultListener {
        fun onConfirm(cropped: Bitmap, regionInScreen: Rect)
        fun onCancel()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var selectView: RegionSelectView? = null
    private var currentRect = Rect()

    /** 底图为屏幕截图时，选框像素坐标即屏幕坐标；为相册图时无屏幕坐标语义，仅回传裁剪图 */
    fun show(source: Bitmap) {
        // 浮层窗口无 Activity 承载，返回键需在根视图上自行拦截
        val overlay = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && root != null) {
                        dismiss()
                        listener.onCancel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(OverlayPalette.canvas.toArgb())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val sv = RegionSelectView(context).apply {
            callback = this@RegionCaptureOverlay
            setSource(source)
        }
        overlay.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 底部操作栏：只保留「确定」，取消统一走系统返回键
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createOverlayBarBackground(context)
            val pad = (Dimens.space16.value * context.resources.displayMetrics.density).roundToInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val btnConfirm = createOverlayButton(context, "确定", filled = true) {
            val cropped = sv.cropSelected()
            dismiss()
            if (cropped != null) listener.onConfirm(cropped, currentRect)
            else listener.onCancel()
        }
        btnConfirm.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        )
        bar.addView(btnConfirm)

        // 底部纵向容器：比例选择条 + 操作栏
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                RegionRatioBar(context) { ratio -> sv.setAspectRatio(ratio) }.view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        overlay.addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 捕获浮层需要独占触摸（拦截到下层 App），故不加 NOT_TOUCH_MODAL，可聚焦以接收返回键
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(overlay, lp)
            root = overlay
            selectView = sv
            overlay.requestFocus()
        } catch (e: Exception) {
            android.util.Log.e("RegionCapture", "addView 失败", e)
            listener.onCancel()
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    override fun onRegionChanged(rectPx: Rect) {
        currentRect = rectPx
    }

    fun dismiss() {
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        selectView = null
    }
}
