package com.hpu.autoclicker.feature.quickclick

import android.text.InputFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppCard
import com.hpu.autoclicker.core.components.AppCheckboxRow
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppScriptList
import com.hpu.autoclicker.core.components.AppSegmentedControl
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens

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

    // ===== 坐标点击相关 =====
    /** 当前识别方式：TYPE_TEXT / TYPE_IMAGE / TYPE_COORD */
    var type by mutableStateOf(QuickClickConfig.TYPE_TEXT)
    var coordXText by mutableStateOf("")            // 坐标点击 X（屏幕像素）
    var coordYText by mutableStateOf("")            // 坐标点击 Y（屏幕像素）

    // ===== 图像识别相关 =====
    var imageFileName by mutableStateOf("")          // 已选/已截模板图文件名
    var imageRegionRect by mutableStateOf("")        // 截图捕获时的框选区域 "l,t,r,b"（屏幕像素）
    var thresholdText by mutableStateOf("0.80")     // 相似度阈值
    var scaleText by mutableStateOf("2")            // 降采样倍率
    var regionFull by mutableStateOf(true)          // true=全屏匹配，false=仅上次框选区域
    var grayMode by mutableStateOf(true)            // 灰度匹配
    var retryText by mutableStateOf("1")            // 重试次数
    var retryDelayText by mutableStateOf("300")     // 重试间隔 ms
    var showAdvanced by mutableStateOf(false)       // 高级设置展开

    // ===== 动作相关（识别坐标之后怎么执行）=====
    var actionType by mutableStateOf(QuickClickConfig.ACTION_CLICK)
    var doubleClickIntervalText by mutableStateOf("150")   // 双击间隔 ms
    var longPressDurationText by mutableStateOf("800")     // 长按时长 ms
    var swipeDirection by mutableStateOf(QuickClickConfig.SWIPE_UP)
    var swipeDistanceText by mutableStateOf("300")         // 滑动距离 px
    var swipeDurationText by mutableStateOf("300")         // 滑动时长 ms
    var randomOffsetEnabled by mutableStateOf(true)        // 随机坐标偏移
    var randomOffsetRadiusText by mutableStateOf("5")      // 偏移半径 px

    /** 表单图像参数是否合法（供保存前校验） */
    fun thresholdOrDefault(): Float = thresholdText.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.80f
    fun scaleOrDefault(): Int = scaleText.toIntOrNull()?.coerceIn(1, 4) ?: 2
    fun retryOrDefault(): Int = retryText.toIntOrNull()?.coerceAtLeast(0) ?: 1
    fun retryDelayOrDefault(): Long = retryDelayText.toLongOrNull()?.coerceAtLeast(0) ?: 300L

    /** 动作参数统一夹到合法区间，空值/非法值回落到默认值（供保存前校验） */
    fun doubleClickIntervalOrDefault(): Long = doubleClickIntervalText.toLongOrNull()
        ?.coerceIn(QuickClickConfig.MIN_DOUBLE_INTERVAL, QuickClickConfig.MAX_DOUBLE_INTERVAL)
        ?: 150L

    fun longPressDurationOrDefault(): Long = longPressDurationText.toLongOrNull()
        ?.coerceIn(QuickClickConfig.MIN_LONG_PRESS, QuickClickConfig.MAX_LONG_PRESS)
        ?: 800L

    fun swipeDistanceOrDefault(maxDistance: Int): Int = swipeDistanceText.toIntOrNull()
        ?.coerceIn(QuickClickConfig.MIN_SWIPE_DISTANCE, maxDistance)
        ?: 300.coerceIn(QuickClickConfig.MIN_SWIPE_DISTANCE, maxDistance)

    fun swipeDurationOrDefault(): Long = swipeDurationText.toLongOrNull()
        ?.coerceIn(QuickClickConfig.MIN_SWIPE_DURATION, QuickClickConfig.MAX_SWIPE_DURATION)
        ?: 300L

    fun randomOffsetRadiusOrDefault(): Int = randomOffsetRadiusText.toIntOrNull()
        ?.coerceIn(0, QuickClickConfig.MAX_RANDOM_RADIUS)
        ?: 5

    /** 输入超过上限（保存时会截断，需提示用户） */
    fun longPressExceedsLimit(): Boolean =
        (longPressDurationText.toLongOrNull() ?: 0L) > QuickClickConfig.MAX_LONG_PRESS

    fun swipeDistanceExceedsLimit(maxDistance: Int): Boolean =
        (swipeDistanceText.toIntOrNull() ?: 0) > maxDistance
}

/**
 * 新建快捷按钮弹框的正文 —— 外框（标题 / 分隔线 / 底部按钮）由
 * [com.hpu.autoclicker.core.components.AppDialogPanel] 统一提供，这里只放表单内容。
 */
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
    Column(modifier = Modifier.fillMaxWidth()) {
        AppFieldLabel(text = "按钮名称（可选）")
        AppTextField(
            value = state.name,
            onValueChange = { state.name = nameFilter(it) },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "默认为“按钮N”",
            inputFilter = QuickNameInputFilter,
        )

        // 识别方式：分段单选，与主页「连点器 / 代点器」同款控件
        AppFieldLabel(text = "识别方式", modifier = Modifier.padding(top = Dimens.space16))
        AppSegmentedControl(
            options = listOf("文本", "图像", "坐标"),
            selectedIndex = actionTypeIndex(state.type),
            onSelect = { state.type = actionTypeByIndex(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.fieldGap),
        )
        AppCaption(
            text = actionTypeHint(state.type),
            modifier = Modifier.padding(top = Dimens.space4),
        )

        // 类型相关配置：装进带描边的卡片，与主页「分区 + 卡片」的层次一致
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
            else -> TextMatchSection(state)
        }

        // 动作类型：拿到识别坐标之后执行哪种手势
        AppFieldLabel(text = "动作类型", modifier = Modifier.padding(top = Dimens.space16))
        AppSegmentedControl(
            options = ACTION_LABELS,
            selectedIndex = actionIndex(state.actionType),
            onSelect = { state.actionType = ACTION_TYPES[it] },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.fieldGap),
        )
        ActionSection(
            state = state,
            maxSwipeDistance = QuickClickConfig.maxSwipeDistance(screenWidth, screenHeight),
        )
    }
}

// ==================== 动作类型选择与参数 ====================

private val ACTION_TYPES = listOf(
    QuickClickConfig.ACTION_CLICK,
    QuickClickConfig.ACTION_DOUBLE_CLICK,
    QuickClickConfig.ACTION_LONG_PRESS,
    QuickClickConfig.ACTION_SWIPE,
)
private val ACTION_LABELS = listOf("点击", "双击", "长按", "滑动")
private val SWIPE_DIRECTIONS = listOf(
    QuickClickConfig.SWIPE_UP,
    QuickClickConfig.SWIPE_DOWN,
    QuickClickConfig.SWIPE_LEFT,
    QuickClickConfig.SWIPE_RIGHT,
)
private val SWIPE_LABELS = listOf("上", "下", "左", "右")

private fun actionIndex(action: String): Int = ACTION_TYPES.indexOf(action).coerceAtLeast(0)
private fun swipeIndex(direction: String): Int = SWIPE_DIRECTIONS.indexOf(direction).coerceAtLeast(0)

/**
 * 动作参数区：只渲染当前动作用到的字段；
 * 随机偏移是四种动作共用的，统一放在卡片底部。
 */
@Composable
private fun ActionSection(state: QuickConfigFormState, maxSwipeDistance: Int) {
    val scheme = MaterialTheme.colorScheme
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.blockGap),
        border = BorderStroke(Dimens.strokeThin, scheme.outlineVariant),
    ) {
        when (state.actionType) {
            QuickClickConfig.ACTION_DOUBLE_CLICK -> {
                AppFieldLabel(text = "双击间隔（100~300ms，默认 150）")
                AppTextField(
                    value = state.doubleClickIntervalText,
                    onValueChange = { state.doubleClickIntervalText = it.filter(Char::isDigit) },
                    modifier = Modifier.padding(top = Dimens.fieldGap),
                    hint = "150",
                    keyboardType = KeyboardType.Number,
                )
            }
            QuickClickConfig.ACTION_LONG_PRESS -> {
                AppFieldLabel(text = "长按时长（200~10000ms，默认 800）")
                AppTextField(
                    value = state.longPressDurationText,
                    onValueChange = { state.longPressDurationText = it.filter(Char::isDigit) },
                    modifier = Modifier.padding(top = Dimens.fieldGap),
                    hint = "800",
                    keyboardType = KeyboardType.Number,
                )
            }
            QuickClickConfig.ACTION_SWIPE -> {
                AppFieldLabel(text = "滑动方向")
                AppSegmentedControl(
                    options = SWIPE_LABELS,
                    selectedIndex = swipeIndex(state.swipeDirection),
                    onSelect = { state.swipeDirection = SWIPE_DIRECTIONS[it] },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.fieldGap),
                )
                AppFieldLabel(
                    text = "滑动距离（50~${maxSwipeDistance}px，默认 300）",
                    modifier = Modifier.padding(top = Dimens.space12),
                )
                AppTextField(
                    value = state.swipeDistanceText,
                    onValueChange = { state.swipeDistanceText = it.filter(Char::isDigit) },
                    modifier = Modifier.padding(top = Dimens.fieldGap),
                    hint = "300",
                    keyboardType = KeyboardType.Number,
                )
                AppFieldLabel(
                    text = "滑动时长（100~2000ms，默认 300）",
                    modifier = Modifier.padding(top = Dimens.space12),
                )
                AppTextField(
                    value = state.swipeDurationText,
                    onValueChange = { state.swipeDurationText = it.filter(Char::isDigit) },
                    modifier = Modifier.padding(top = Dimens.fieldGap),
                    hint = "300",
                    keyboardType = KeyboardType.Number,
                )
            }
            else -> AppCaption(text = "单击：在识别到的位置点击一次")
        }

        HorizontalDivider(
            thickness = Dimens.divider,
            color = scheme.outlineVariant,
            modifier = Modifier.padding(top = Dimens.space12),
        )
        AppCheckboxRow(
            text = "随机坐标偏移",
            checked = state.randomOffsetEnabled,
            onCheckedChange = { state.randomOffsetEnabled = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.randomOffsetEnabled) {
            AppFieldLabel(
                text = "偏移半径（0~50px，0 为关闭，默认 5）",
                modifier = Modifier.padding(top = Dimens.space8),
            )
            AppTextField(
                value = state.randomOffsetRadiusText,
                onValueChange = { state.randomOffsetRadiusText = it.filter(Char::isDigit) },
                modifier = Modifier.padding(top = Dimens.fieldGap),
                hint = "5",
                keyboardType = KeyboardType.Number,
            )
            AppCaption(
                text = "每次动作前在识别坐标上随机偏移一次；双击两次共用同一位置，滑动只偏移起点",
                modifier = Modifier.padding(top = Dimens.fieldGap),
            )
        }
    }
}

/** 动作类型 → 分段控件下标 */
private fun actionTypeIndex(type: String): Int = when (type) {
    QuickClickConfig.TYPE_IMAGE -> 1
    QuickClickConfig.TYPE_COORD -> 2
    else -> 0
}

/** 分段控件下标 → 动作类型 */
private fun actionTypeByIndex(index: Int): String = when (index) {
    1 -> QuickClickConfig.TYPE_IMAGE
    2 -> QuickClickConfig.TYPE_COORD
    else -> QuickClickConfig.TYPE_TEXT
}

/** 当前识别方式的一句话说明 */
private fun actionTypeHint(type: String): String = when (type) {
    QuickClickConfig.TYPE_IMAGE -> "图像识别：匹配模板图，取匹配位置执行所选动作"
    QuickClickConfig.TYPE_COORD -> "坐标定位：按屏幕像素坐标执行所选动作"
    else -> "文本匹配：查找目标文字，取文字位置执行所选动作"
}

/** 按钮名称限长：含中文 ≤3 字，纯英文/数字 ≤6 字符（悬浮按钮上会按宽度自动缩字号） */
internal fun quickNameMaxLength(text: CharSequence): Int =
    if (text.any { it.code > 0x2E80 }) 3 else 6

/**
 * 名称输入的硬限制：超长部分直接由输入框丢弃。
 * 不走「状态回灌 + setText」截断，避免快速输入时重组滞后导致漏截。
 */
internal val QuickNameInputFilter = InputFilter { source, start, end, dest, dstart, dend ->
    val result = buildString {
        append(dest, 0, dstart)
        append(source, start, end)
        append(dest, dend, dest.length)
    }
    val max = quickNameMaxLength(result)
    if (result.length <= max) {
        null
    } else {
        val keep = max - (dest.length - (dend - dstart))
        if (keep <= 0) "" else source.subSequence(start, start + keep)
    }
}

/** 文本匹配配置区：目标文字 */
@Composable
private fun TextMatchSection(state: QuickConfigFormState) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.blockGap),
        border = BorderStroke(Dimens.strokeThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        AppFieldLabel(text = "目标文字")
        AppTextField(
            value = state.targetText,
            onValueChange = { state.targetText = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "输入要匹配的目标文字",
        )
    }
}

/** 图像识别配置区：模板图选择/缩略图 + 可展开的高级参数（整块装进卡片） */
@Composable
private fun ImageRecognitionSection(
    state: QuickConfigFormState,
    onCaptureScreenshot: () -> Unit,
    onPickFromGallery: () -> Unit,
    screenshotSupported: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.blockGap),
        border = BorderStroke(Dimens.strokeThin, scheme.outlineVariant),
    ) {
        AppFieldLabel(text = "模板图片")
        TemplateThumbnail(
            fileName = state.imageFileName,
            onClick = onPickFromGallery,
            modifier = Modifier.padding(top = Dimens.fieldGap),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space12),
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
                color = scheme.error,
                modifier = Modifier.padding(top = Dimens.fieldGap),
            )
        }

        // 高级参数与模板图区用分隔线切开，展开时仍保持卡片内的分组感
        HorizontalDivider(
            thickness = Dimens.divider,
            color = scheme.outlineVariant,
            modifier = Modifier.padding(top = Dimens.space12),
        )
        AppCheckboxRow(
            text = "高级匹配参数",
            checked = state.showAdvanced,
            onCheckedChange = { state.showAdvanced = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showAdvanced) {
            AppFieldLabel(text = "相似度阈值（0~1，默认 0.80）", modifier = Modifier.padding(top = Dimens.space8))
            AppTextField(
                value = state.thresholdText,
                onValueChange = { state.thresholdText = it },
                modifier = Modifier.padding(top = Dimens.fieldGap),
                hint = "0.80",
                keyboardType = KeyboardType.Number,
            )
            AppFieldLabel(text = "降采样倍率（1~4，默认 2，越大越快越粗）", modifier = Modifier.padding(top = Dimens.space8))
            AppTextField(
                value = state.scaleText,
                onValueChange = { state.scaleText = it },
                modifier = Modifier.padding(top = Dimens.fieldGap),
                hint = "2",
                keyboardType = KeyboardType.Number,
            )
            AppFieldLabel(text = "未找到重试次数 / 间隔(ms)", modifier = Modifier.padding(top = Dimens.space8))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.fieldGap),
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
}

/**
 * 模板缩略图 —— 已选图时显示图片（点击可重选），未选图时显示占位框，
 * 两种状态都有描边，避免弹框里出现一块无来由的空白。
 */
@Composable
private fun TemplateThumbnail(
    fileName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val ctx = LocalContext.current
    val bitmap = remember(fileName) {
        if (fileName.isEmpty()) null else ImageStore.loadImage(ctx, fileName)
    }
    Column(modifier = modifier) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "模板缩略图",
                modifier = Modifier
                    .size(Dimens.iconLg * 3)
                    .clip(shape)
                    .border(Dimens.strokeThin, scheme.outlineVariant, shape)
                    .clickable { onClick() },
            )
        } else {
            Box(
                modifier = Modifier
                    .size(Dimens.iconLg * 3)
                    .clip(shape)
                    .background(scheme.surfaceContainer)
                    .border(Dimens.strokeThin, scheme.outlineVariant, shape),
                contentAlignment = Alignment.Center,
            ) {
                AppCaption(text = "未选择模板图", color = scheme.outline)
            }
        }
        AppCaption(
            text = if (bitmap != null) "点击缩略图可重新选择" else "可从下方截图或相册选择",
            modifier = Modifier.padding(top = Dimens.space4),
        )
    }
}

/** 坐标点击配置区：X/Y 手动输入 + 「屏幕取点」（整块装进卡片） */
@Composable
private fun CoordinateSection(
    state: QuickConfigFormState,
    onPickCoordinate: () -> Unit,
    screenWidth: Int,
    screenHeight: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val rangeHint = if (screenWidth > 0 && screenHeight > 0) "（屏幕 ${screenWidth}×${screenHeight}）" else ""
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.blockGap),
        border = BorderStroke(Dimens.strokeThin, scheme.outlineVariant),
    ) {
        AppFieldLabel(text = "点击坐标 $rangeHint")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.fieldGap),
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
                .padding(top = Dimens.space12),
        )
        AppCaption(
            text = "屏幕会置灰并覆盖网格，点选任意位置即可回填坐标，按返回键退出",
            modifier = Modifier.padding(top = Dimens.fieldGap),
        )
    }
}

// ==================== 单行输入（保存脚本 / 重命名脚本） ====================

/** 单行输入弹框状态 */
@Stable
class InputDialogState {
    var value by mutableStateOf("")
}

// ==================== 悬浮窗透明度 ====================
// 正文与状态已抽到 core.components（[com.hpu.autoclicker.core.components.AppOpacityDialogContent]），
// 与连点器共用同一页面；透明度各自持久化，互不干扰。

// ==================== 脚本管理（dialog_script_manager.xml） ====================

/** 脚本管理状态：脚本列表（空列表时展示空态提示） */
@Stable
class ScriptManagerState {
    var scripts by mutableStateOf<List<QuickScript>>(emptyList())
}

/** 脚本管理弹框正文（外框由 AppDialogPanel 提供） */
@Composable
fun ScriptManagerDialogContent(
    state: ScriptManagerState,
    onSaveCurrent: () -> Unit,
    onLoadScript: (QuickScript) -> Unit,
    onRenameScript: (QuickScript) -> Unit,
    onDeleteScript: (QuickScript) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 保存当前按钮
        AppButton(
            text = "保存当前按钮",
            onClick = onSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
        )

        // 脚本列表（与连点器「悬浮窗设置」共用同一套列表行样式）
        AppScriptList(
            scripts = state.scripts,
            nameOf = { it.name },
            onLoad = onLoadScript,
            onRename = onRenameScript,
            onDelete = onDeleteScript,
            emptyTitle = "暂无已保存脚本",
            emptyDescription = "先在悬浮窗上添加按钮，再回到这里保存",
        )
    }
}
