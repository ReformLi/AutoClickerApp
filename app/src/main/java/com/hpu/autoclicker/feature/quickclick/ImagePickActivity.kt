package com.hpu.autoclicker.feature.quickclick

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.ui.createOverlayBarBackground
import com.hpu.autoclicker.core.ui.createOverlayButton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 相册选图 + 区域裁剪的透明中转 Activity。
 * Service 无法直接启动系统 Photo Picker（需 ActivityResultLauncher），故经此 Activity：
 * Pick → 解码 → 复用 [RegionSelectView] 框选裁剪 → 存图 → 通过 [pendingResult] 回传文件名 → finish。
 *
 * 采用系统 Photo Picker（PickVisualMedia），无需存储权限，符合「轻量不打扰」。
 * 主题在 Manifest 里指定为透明，故用 ComponentActivity 即可提供 ActivityResultLauncher。
 */
class ImagePickActivity : ComponentActivity() {

    companion object {
        /** Service 拉起前设置的回调；Activity 结果后清空 */
        var pendingResult: ((fileName: String?, bitmapWidth: Int, bitmapHeight: Int) -> Unit)? = null
    }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri == null) {
                finishWithResult(null)
                return@registerForActivityResult
            }
            val bmp = decodeScaled(uri, 1600)
            if (bmp == null) {
                finishWithResult(null)
                return@registerForActivityResult
            }
            showCropper(bmp)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 裁剪页按返回键：回传「未选择」并关闭本页，让配置弹框恢复显示
        onBackPressedDispatcher.addCallback(this) { finishWithResult(null) }
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    override fun onDestroy() {
        // 兜底：若中途被关闭而未走 finishWithResult，也必须通知服务恢复弹框
        if (!resultDelivered && isFinishing) {
            pendingResult?.invoke(null, 0, 0)
            pendingResult = null
        }
        super.onDestroy()
    }

    /** 用 RegionSelectView + 底部栏 作为 Activity 内容，完成相册图裁剪 */
    private fun showCropper(source: android.graphics.Bitmap) {
        val overlay = FrameLayout(this)
        val sv = RegionSelectView(this).apply { setSource(source) }
        overlay.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createOverlayBarBackground(this@ImagePickActivity)
            val pad = (Dimens.space16.value * resources.displayMetrics.density).roundToInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val btnCancel = createOverlayButton(this, "取消", filled = false) {
            finishWithResult(null)
        }
        val btnOk = createOverlayButton(this, "裁剪并确定", filled = true) {
            val cropped = sv.cropSelected()
            if (cropped == null) {
                finishWithResult(null)
                return@createOverlayButton
            }
            val name = ImageStore.generateFileName("pick")
            val saved = ImageStore.saveImage(this@ImagePickActivity, cropped, name)
            finishWithResult(if (saved) name else null, cropped.width, cropped.height)
        }
        btnCancel.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(8)
        }
        btnOk.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        bar.addView(btnCancel)
        bar.addView(btnOk)

        // 底部纵向容器：比例选择条 + 操作栏
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                RegionRatioBar(this@ImagePickActivity) { ratio -> sv.setAspectRatio(ratio) }.view,
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
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM)
        )
        setContentView(overlay)
    }

    /** 是否已经回传结果；防止返回键与 onDestroy 重复回调 */
    private var resultDelivered = false

    private fun finishWithResult(name: String?, w: Int = 0, h: Int = 0) {
        if (resultDelivered) {
            finish()
            return
        }
        resultDelivered = true
        pendingResult?.invoke(name, w, h)
        pendingResult = null
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** 按比例下采样解码，避免大图 OOM；最长边不超过 maxSide */
    private fun decodeScaled(uri: Uri, maxSide: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = max(1, max(bounds.outWidth, bounds.outHeight) / maxSide)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            android.util.Log.e("ImagePick", "解码失败", e)
            null
        }
    }
}
