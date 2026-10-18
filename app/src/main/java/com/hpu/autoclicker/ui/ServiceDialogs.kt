package com.hpu.autoclicker.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hpu.autoclicker.QuickScript
import com.hpu.autoclicker.ui.theme.AppButton
import com.hpu.autoclicker.ui.theme.AppOutlineButton
import com.hpu.autoclicker.ui.theme.LocalAppPalette
import com.hpu.autoclicker.ui.theme.ModernLabel
import com.hpu.autoclicker.ui.theme.ModernTextField
import com.hpu.autoclicker.ui.theme.ModernTitle

/**
 * 本文件集中承载「服务内 4 个弹框」的 Compose 内容（原 4 个 XML 布局）：
 * - QuickConfigDialogContent   ← dialog_quick_click_config.xml（新建快捷按钮）
 * - ScriptManagerDialogContent ← dialog_script_manager.xml（脚本管理）
 * - SettingsDialogContent      ← dialog_settings.xml（悬浮窗设置，旧功能）
 * - PointSettingsDialogContent ← activity_point_settings.xml（点击点设置，旧功能）
 *
 * 这些弹框都通过 AlertDialog.setView(ComposeView) 的方式挂载，
 * 业务侧仍沿用原有的 AlertDialog / TYPE_APPLICATION_OVERLAY / 确认逻辑，
 * 仅把「布局 + 控件取值」换成 Compose（取值改为读写状态对象）。
 */

// ==================== Service 内 ComposeView 的宿主支持 ====================

/**
 * Service 内使用 ComposeView 的最小宿主实现。
 *
 * ComposeView.setContent 在 attach 时要求 ViewTreeLifecycleOwner；
 * 而 Service / 系统 AlertDialog 场景下默认没有该 Owner，
 * 不补齐会抛 “ViewTreeLifecycleOwner not found from ...”。
 */
class ServiceComposeHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    // Compose 在 Service 场景下还会查找 SavedStateRegistryOwner / ViewModelStoreOwner，
    // 缺失时 rememberSaveable、LocalViewModelStoreOwner 等能力不可用，一并补齐
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * 把宿主注入 ComposeView，并让 Compose 认为宿主已 RESUMED（可正常组合/重组）。
     * 必须保证 lifecycle 处于 INITIALIZED 时 restore，否则 SavedStateRegistry 会抛异常。
     */
    fun attach(view: ComposeView) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        attachWindowRoot(view)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * 把宿主 Owner 挂到「窗口根视图」上（Service 场景即对话框窗口的 DecorView）。
     *
     * 只给 ComposeView 自己挂 Owner 在对话框场景下是不够的：
     * Compose 取窗口 Recomposer 时，会先从 ComposeView 向上找到「父级 id == android.R.id.content」
     * 的那一层视图（WindowRecomposer 的 contentChild），对话框里这一层正是 AlertDialogLayout，
     * 然后从该视图开始向上查找 ViewTreeLifecycleOwner。
     * Activity 场景下 Activity 已把 Owner 挂在 DecorView 上，所以能一路找到；
     * Service 场景没有这一步，就会抛
     * “ViewTreeLifecycleOwner not found from com.android.internal.widget.AlertDialogLayout”。
     * 因此必须在 dialog.show() 之前，把 Owner 同时挂到对话框窗口的 DecorView 上。
     */
    fun attachWindowRoot(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

// ==================== 通用小控件 ====================

/** 单选行（对应 XML 中的 RadioButton + label） */
@Composable
private fun LabeledRadio(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled) { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.primary,
                unselectedColor = palette.textHint,
                disabledSelectedColor = palette.primary,
                disabledUnselectedColor = palette.textHint,
            ),
        )
        Text(
            text = text,
            color = if (enabled) palette.textPrimary else palette.textSecondary,
            fontSize = 16.sp,
        )
    }
}

// ==================== 1. 新建快捷按钮（dialog_quick_click_config.xml） ====================

/** 新建快捷按钮表单状态（替代原来对 EditText / CheckBox 的 findViewById 取值） */
@Stable
class QuickConfigFormState {
    var name by mutableStateOf("")
    var targetText by mutableStateOf("")
    var autoCollapse by mutableStateOf(false)
}

@Composable
fun QuickConfigDialogContent(
    state: QuickConfigFormState,
    nameFilter: (String) -> String = { it },
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 与原 XML 一致的边距：左右 24dp、上 12dp、下 4dp
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    ) {
        ModernLabel(text = "按钮名称（可选）")
        ModernTextField(
            value = state.name,
            onValueChange = { state.name = nameFilter(it) },
            modifier = Modifier.padding(top = 6.dp),
            hint = "默认为“按钮N”",
            maxLength = 10,
        )

        ModernLabel(text = "动作类型", modifier = Modifier.padding(top = 16.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            // 目前仅支持文本匹配，其余两项置灰占位（与原文案、禁用态一致）
            LabeledRadio(text = "文本匹配", selected = true, onSelect = {})
            LabeledRadio(text = "图像识别（即将支持）", selected = false, enabled = false, onSelect = {})
            LabeledRadio(text = "坐标点击（即将支持）", selected = false, enabled = false, onSelect = {})
        }

        ModernLabel(text = "目标文字", modifier = Modifier.padding(top = 16.dp))
        ModernTextField(
            value = state.targetText,
            onValueChange = { state.targetText = it },
            modifier = Modifier.padding(top = 6.dp),
            hint = "输入要在屏幕上匹配的文字",
        )

        // 点击后自动收起悬浮窗
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 20.dp)
                .clickable { state.autoCollapse = !state.autoCollapse },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.autoCollapse,
                onCheckedChange = { state.autoCollapse = it },
                colors = CheckboxDefaults.colors(checkedColor = palette.primary),
            )
            Text(text = "点击后自动收起悬浮窗", color = palette.textPrimary, fontSize = 16.sp)
        }
    }
}

// ==================== 2. 脚本管理（dialog_script_manager.xml） ====================

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
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    ) {
        // 保存当前按钮（原 btnSaveCurrent，44dp 高）
        AppButton(
            text = "保存当前按钮",
            onClick = onSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
        )

        // 空态提示（无脚本时显示）
        if (state.scripts.isEmpty()) {
            Text(
                text = "暂无已保存脚本",
                modifier = Modifier.padding(top = 12.dp),
                color = palette.textSecondary,
                fontSize = 14.sp,
            )
        }

        // 脚本列表（原 ScrollView 固定 200dp 高，可滚动；每行：名称 / 改名 / 删除）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            state.scripts.forEach { script ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点击名称 = 加载该脚本
                    Text(
                        text = script.name,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLoadScript(script) }
                            .padding(start = 4.dp),
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "改名",
                        modifier = Modifier
                            .clickable { onRenameScript(script) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = palette.primary,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "删除",
                        modifier = Modifier
                            .clickable { onDeleteScript(script) }
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        color = palette.danger,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

// ==================== 3. 悬浮窗设置（dialog_settings.xml，旧功能） ====================

/** 悬浮窗设置状态（总循环次数字符串） */
@Stable
class SettingsDialogState {
    var loopCount by mutableStateOf("")
}

@Composable
fun SettingsDialogContent(
    state: SettingsDialogState,
    onSavePoints: () -> Unit,
    onLoadPoints: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 原 XML 根布局自带 dialog_background（白底 12dp 圆角），此处等价还原
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppPalette.current.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        ModernTitle(text = "悬浮窗设置")

        ModernLabel(text = "总循环次数", modifier = Modifier.padding(top = 24.dp))
        ModernTextField(
            value = state.loopCount,
            onValueChange = { state.loopCount = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0 表示无限循环",
            keyboardType = KeyboardType.Number,
        )

        AppOutlineButton(
            text = "保存当前点击位置",
            onClick = onSavePoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        )
        AppOutlineButton(
            text = "打开点击文件",
            onClick = onLoadPoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        Row(modifier = Modifier.padding(top = 24.dp)) {
            AppOutlineButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.width(8.dp))
            AppButton(
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ==================== 4. 点击点设置（activity_point_settings.xml，旧功能） ====================

/** 点击点设置状态（替代原 XML 中大量控件取值） */
@Stable
class PointSettingsFormState {
    var name by mutableStateOf("")
    /** 点击类型：点击 / 双击 / 返回键 / 主页键 / 打开通知栏 / 滑动 */
    var type by mutableStateOf("点击")
    /** 点击位置：中间 / 随机 */
    var clickPosition by mutableStateOf("中间")
    var delay by mutableStateOf("0")
    var touchDuration by mutableStateOf("100")
    var repeatCount by mutableStateOf("1")
    var randomDistance by mutableStateOf("0")
    /** 是否为滑动点（滑动点隐藏其它类型、仅展示「滑动」且不可改） */
    var isSwipePoint by mutableStateOf(false)
}

@Composable
fun PointSettingsDialogContent(
    state: PointSettingsFormState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalAppPalette.current
    // 原 XML 根布局自带 dialog_background；外层 wrapper 负责百分比留白与点外关闭
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        ModernTitle(text = "点击点设置")

        ModernLabel(text = "点击名称", modifier = Modifier.padding(top = 24.dp))
        ModernTextField(
            value = state.name,
            onValueChange = {},
            modifier = Modifier.padding(top = 8.dp),
            hint = "按钮名称",
            readOnly = true,
        )

        ModernLabel(text = "点击类型", modifier = Modifier.padding(top = 20.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            if (state.isSwipePoint) {
                // 滑动点：仅显示「滑动」（选中且不可改）
                LabeledRadio(text = "滑动", selected = true, enabled = false, onSelect = {})
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    LabeledRadio("点击", state.type == "点击", Modifier.weight(1f)) { state.type = "点击" }
                    LabeledRadio("双击", state.type == "双击", Modifier.weight(1f)) { state.type = "双击" }
                    LabeledRadio("返回键", state.type == "返回键", Modifier.weight(1f)) { state.type = "返回键" }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    LabeledRadio("主页键", state.type == "主页键", Modifier.weight(1f)) { state.type = "主页键" }
                    LabeledRadio("打开通知栏", state.type == "打开通知栏", Modifier.weight(1f)) { state.type = "打开通知栏" }
                }
            }
        }

        ModernLabel(text = "点击位置", modifier = Modifier.padding(top = 20.dp))
        Row(modifier = Modifier.padding(top = 4.dp)) {
            LabeledRadio("中间", state.clickPosition == "中间", Modifier.weight(1f)) { state.clickPosition = "中间" }
            LabeledRadio("随机", state.clickPosition == "随机", Modifier.weight(1f)) { state.clickPosition = "随机" }
        }

        ModernLabel(text = "延迟（毫秒）", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.delay,
            onValueChange = { state.delay = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )

        // 滑动点显示「滑动持续时间」，其余显示「触摸时长」（与原文案一致）
        ModernLabel(
            text = if (state.isSwipePoint) "滑动持续时间（毫秒）" else "触摸时长（毫秒）",
            modifier = Modifier.padding(top = 20.dp),
        )
        ModernTextField(
            value = state.touchDuration,
            onValueChange = { state.touchDuration = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "100",
            keyboardType = KeyboardType.Number,
        )

        ModernLabel(text = "重复次数", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.repeatCount,
            onValueChange = { state.repeatCount = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "1",
            keyboardType = KeyboardType.Number,
        )

        ModernLabel(text = "随机距离（像素）", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.randomDistance,
            onValueChange = { state.randomDistance = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppOutlineButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "保存",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }

        // 底部占位，保证滚动到底部时有呼吸空间
        Box(modifier = Modifier.size(8.dp))
    }
}
