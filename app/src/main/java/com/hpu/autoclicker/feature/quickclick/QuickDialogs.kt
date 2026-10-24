package com.hpu.autoclicker.feature.quickclick

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppCheckboxRow
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppEmptyState
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppRadioButtonRow
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens
import kotlin.math.roundToInt

/**
 * 代点器（快捷点击）的两个服务内弹框（原 dialog_quick_click_config.xml / dialog_script_manager.xml）。
 * 经 AlertDialog.setView(ComposeView) 挂载，取值改为读写状态对象。
 *
 * UI 美化（阶段三）：全部走通用组件 + token；输入框用 AppTextField
 * （保留原生 EditText 内核，保证 overlay 内软键盘可弹）。
 */

// ==================== 新建快捷按钮（dialog_quick_click_config.xml） ====================

/** 新建快捷按钮表单状态（替代原来对 EditText / CheckBox 的 findViewById 取值） */
@Stable
class QuickConfigFormState {
    var name by mutableStateOf("")
    var targetText by mutableStateOf("")
    var autoCollapse by mutableStateOf(false)

    // ===== 坐标点击相关 =====
    /** 当前动作类型：TYPE_TEXT / TYPE_IMAGE / TYPE_COORD */
    var type by mutableStateOf(QuickClickConfig.TYPE_TEXT)
    var coordXText by mutableStateOf("")            // 坐标点击 X（屏幕像素）
    var coordYText by mutableStateOf("")            // 坐标点击 Y（屏幕像素）

    // ===== 图像识别相关 =====
    var imageFileName by mutableStateOf("")          // 已选/已截模板图文件名
    var imageRegionRect by mutableStateOf("")        // 截图捕获时的框选区域 "l,t,r,b"（屏幕像素）
    var thresholdText by mutableStateOf("0.85")     // 相似度阈值
    var scaleText by mutableStateOf("2")            // 降采样倍率
    var regionFull by mutableStateOf(true)          // true=全屏匹配，false=仅上次框选区域
    var grayMode by mutableStateOf(true)            // 灰度匹配
    var retryText by mutableStateOf("1")            // 重试次数
    var retryDelayText by mutableStateOf("300")     // 重试间隔 ms
    var showAdvanced by mutableStateOf(false)       // 高级设置展开

    /** 表单图像参数是否合法（供保存前校验） */
    fun thresholdOrDefault(): Float = thresholdText.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f
    fun scaleOrDefault(): Int = scaleText.toIntOrNull()?.coerceIn(1, 4) ?: 2
    fun retryOrDefault(): Int = retryText.toIntOrNull()?.coerceAtLeast(0) ?: 1
    fun retryDelayOrDefault(): Long = retryDelayText.toLongOrNull()?.coerceAtLeast(0) ?: 300L
}

@Composable
fun QuickConfigDialogContent(
    state: QuickConfigFormState,
    nameFilter: (String) -> String = { it },
    onCaptureScreenshot: () -> Unit = {},
    onPickFromGallery: () -> Unit = {},
    onPickCoordinate: () -> Unit = {},
    screenshotSupported: Boolean = true,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 与原 XML 一致的边距：左右 24dp、上下 16dp
            .padding(horizontal = Dimens.dialogHPadding, vertical = Dimens.dialogVPadding),
    ) {
        AppFieldLabel(text = "按钮名称（可选）")
        AppTextField(
            value = state.name,
            onValueChange = { state.name = nameFilter(it) },
            modifier = Modifier.padding(top = Dimens.space4),
            hint = "默认为“按钮N”",
            maxLength = 10,
        )

        AppFieldLabel(text = "动作类型", modifier = Modifier.padding(top = Dimens.space16))
        Column(modifier = Modifier.padding(top = Dimens.space4)) {
            AppRadioButtonRow(
                text = "文本匹配",
                selected = state.type == QuickClickConfig.TYPE_TEXT,
                onSelect = { state.type = QuickClickConfig.TYPE_TEXT },
            )
            AppRadioButtonRow(
                text = "图像识别",
                selected = state.type == QuickClickConfig.TYPE_IMAGE,
                onSelect = { state.type = QuickClickConfig.TYPE_IMAGE },
            )
            AppRadioButtonRow(
                text = "坐标点击",
                selected = state.type == QuickClickConfig.TYPE_COORD,
                onSelect = { state.type = QuickClickConfig.TYPE_COORD },
            )
        }

        when (state.type) {
            QuickClickConfig.TYPE_IMAGE -> ImageRecognitionSection(
                state = state,
                onCaptureScreenshot = onCaptureScreenshot,
                onPickFromGallery = onPickFromGallery,
                screenshotSupported = screenshotSupported,
            )
            QuickClickConfig.TYPE_COORD -> CoordinateSection(
                state = state,
                onPickCoordinate = onPickCoordinate,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            )
            else -> {
                AppFieldLabel(text = "目标文字", modifier = Modifier.padding(top = Dimens.space16))
                AppTextField(
                    value = state.targetText,
                    onValueChange = { state.targetText = it },
                    modifier = Modifier.padding(top = Dimens.space4),
                    hint = "输入要在屏幕上匹配的文字",
                )
            }
        }

        // 点击后自动收起悬浮窗（整行可切换，token 化复选行）
        AppCheckboxRow(
            text = "点击后自动收起悬浮窗",
            checked = state.autoCollapse,
            onCheckedChange = { state.autoCollapse = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space16),
        )
    }
}

/** 图像识别配置区：模板图选择/缩略图 + 可展开的高级参数 */
@Composable
private fun ImageRecognitionSection(
    state: QuickConfigFormState,
    onCaptureScreenshot: () -> Unit,
    onPickFromGallery: () -> Unit,
    screenshotSupported: Boolean,
) {
    AppFieldLabel(text = "模板图片", modifier = Modifier.padding(top = Dimens.space16))

    // 缩略图（已选时显示，点击可重选）
    if (state.imageFileName.isNotEmpty()) {
        val ctx = LocalContext.current
        val bmp = remember(state.imageFileName) { ImageStore.loadImage(ctx, state.imageFileName) }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "模板缩略图",
                modifier = Modifier
                    .padding(top = Dimens.space8)
                    .size(Dimens.iconLg * 3)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onPickFromGallery() },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space8),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppButton(
            text = "屏幕截图",
            onClick = onCaptureScreenshot,
            modifier = Modifier.weight(1f),
            variant = AppButtonVariant.Outlined,
            enabled = screenshotSupported,
        )
        AppButton(
            text = "从相册选",
            onClick = onPickFromGallery,
            modifier = Modifier.weight(1f),
            variant = AppButtonVariant.Outlined,
        )
    }
    if (!screenshotSupported) {
        AppCaption(
            text = "屏幕截图需 Android 11 及以上；低版本请用相册选图",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Dimens.space4),
        )
    }

    // 高级设置开关行
    AppCheckboxRow(
        text = "高级匹配参数",
        checked = state.showAdvanced,
        onCheckedChange = { state.showAdvanced = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space8),
    )
    if (state.showAdvanced) {
        AppFieldLabel(text = "相似度阈值（0~1，默认 0.85）", modifier = Modifier.padding(top = Dimens.space8))
        AppTextField(
            value = state.thresholdText,
            onValueChange = { state.thresholdText = it },
            modifier = Modifier.padding(top = Dimens.space4),
            hint = "0.85",
            keyboardType = KeyboardType.Number,
        )
        AppFieldLabel(text = "降采样倍率（1~4，默认 2，越大越快越粗）", modifier = Modifier.padding(top = Dimens.space8))
        AppTextField(
            value = state.scaleText,
            onValueChange = { state.scaleText = it },
            modifier = Modifier.padding(top = Dimens.space4),
            hint = "2",
            keyboardType = KeyboardType.Number,
        )
        AppFieldLabel(text = "未找到重试次数 / 间隔(ms)", modifier = Modifier.padding(top = Dimens.space8))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space4),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            AppTextField(
                value = state.retryText,
                onValueChange = { state.retryText = it },
                modifier = Modifier.weight(1f),
                hint = "1",
                keyboardType = KeyboardType.Number,
            )
            AppTextField(
                value = state.retryDelayText,
                onValueChange = { state.retryDelayText = it },
                modifier = Modifier.weight(1f),
                hint = "300",
                keyboardType = KeyboardType.Number,
            )
        }
        AppCheckboxRow(
            text = "全屏搜索（关闭则仅在上次框选区域附近搜索）",
            checked = state.regionFull,
            onCheckedChange = { state.regionFull = it },
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.space8),
        )
        AppCheckboxRow(
            text = "灰度匹配（抗色偏，推荐开启）",
            checked = state.grayMode,
            onCheckedChange = { state.grayMode = it },
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.space4),
        )
    }
}

/** 坐标点击配置区：X/Y 手动输入 + 「屏幕取点」 */
@Composable
private fun CoordinateSection(
    state: QuickConfigFormState,
    onPickCoordinate: () -> Unit,
    screenWidth: Int,
    screenHeight: Int,
) {
    val rangeHint = if (screenWidth > 0 && screenHeight > 0) "，屏幕 ${screenWidth}×${screenHeight}" else ""
    AppFieldLabel(
        text = "点击坐标（屏幕像素$rangeHint）",
        modifier = Modifier.padding(top = Dimens.space16),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space4),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppTextField(
            value = state.coordXText,
            onValueChange = { state.coordXText = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            hint = "X",
            keyboardType = KeyboardType.Number,
        )
        AppTextField(
            value = state.coordYText,
            onValueChange = { state.coordYText = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            hint = "Y",
            keyboardType = KeyboardType.Number,
        )
    }
    AppButton(
        text = "屏幕取点",
        onClick = onPickCoordinate,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space8),
        variant = AppButtonVariant.Outlined,
    )
    AppCaption(
        text = "点「屏幕取点」后屏幕会置灰并覆盖网格，点选任意位置即可回填坐标，不会触发下层应用点击；按系统返回键可退出并回到弹框",
        modifier = Modifier.padding(top = Dimens.space4),
    )
}

// ==================== 悬浮窗透明度 ====================

/** 透明度弹框状态：滑块值实时回写悬浮窗 alpha */
@Stable
class OpacityDialogState {
    var alpha by mutableStateOf(1f)
}

@Composable
fun OpacityDialogContent(
    state: OpacityDialogState,
    onAlphaChange: (Float) -> Unit,
) {
    val percent = (state.alpha * 100).roundToInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.dialogHPadding, vertical = Dimens.space12),
    ) {
        AppCaption(text = "拖动滑块即可实时预览悬浮窗透明度，点「确定」保存")
        Slider(
            value = state.alpha,
            onValueChange = {
                state.alpha = it
                onAlphaChange(it)
            },
            valueRange = QuickClickStore.MIN_WINDOW_ALPHA..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space8),
        )
        Text(
            text = "$percent%",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ==================== 脚本管理（dialog_script_manager.xml） ====================

/** 脚本管理状态：脚本列表（空列表时展示空态提示） */
@Stable
class ScriptManagerState {
    var scripts by mutableStateOf<List<QuickScript>>(emptyList())
}

@Composable
fun ScriptManagerDialogContent(
    state: ScriptManagerState,
    onSaveCurrent: () -> Unit,
    onLoadScript: (QuickScript) -> Unit,
    onRenameScript: (QuickScript) -> Unit,
    onDeleteScript: (QuickScript) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .padding(horizontal = Dimens.dialogHPadding, vertical = Dimens.dialogVPadding),
    ) {
        // 保存当前按钮
        AppButton(
            text = "保存当前按钮",
            onClick = onSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
        )

        // 空态提示（无脚本时显示）
        if (state.scripts.isEmpty()) {
            AppEmptyState(
                title = "暂无已保存脚本",
                description = "先在悬浮窗上添加按钮，再回到这里保存",
                modifier = Modifier.padding(top = Dimens.space8),
            )
        }

        // 脚本列表（原 ScrollView 固定 200dp 高，可滚动；每行：名称 / 改名 / 删除）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.space24 * 8)   // 192dp ≈ 原 200dp 列表高度，落在 8 的倍数阶梯上
                .padding(top = Dimens.space8)
                .verticalScroll(rememberScrollState()),
        ) {
            state.scripts.forEach { script ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.touchMin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点击名称 = 加载该脚本
                    AppBodyText(
                        text = script.name,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLoadScript(script) }
                            .padding(start = Dimens.space4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "改名",
                        modifier = Modifier
                            .clickable { onRenameScript(script) }
                            .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                    )
                    Text(
                        text = "删除",
                        modifier = Modifier
                            .clickable { onDeleteScript(script) }
                            .padding(start = Dimens.space12, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space4),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.error,
                    )
                }
            }
        }
    }
}
