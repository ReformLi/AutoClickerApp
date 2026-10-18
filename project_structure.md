# 项目结构（project_structure）

> 本文档描述 `app` 模块在「按功能特性（feature-based）分包」重构后的源码组织。
> 根包：`com.hpu.autoclicker`（同时也是 applicationId / R 类所在包）。

## 1. 设计原则

- **高内聚**：每个功能的 Service、Composable 界面、数据模型、专属弹框聚在同一 `feature/*` 包内。
- **低耦合**：跨功能复用的基础设施统一下沉到 `core/*`；`feature` 只依赖 `core`，`core` 不反向依赖任何 `feature`。
- **单向依赖**：`MainActivity（根）` → `feature/*` → `core/*`，不存在环依赖。
- **R 类**：始终位于 `com.hpu.autoclicker.R`（命名空间未变），子包内引用 `R.*` 需 `import com.hpu.autoclicker.R`。

## 2. 目录树

```
com.hpu.autoclicker
├── MainActivity.kt                    # 应用入口：权限引导、服务启停、连点器/代点器模式切换，驱动 MainScreen
│
├── core/                              # 跨功能共享基础设施（不含业务逻辑）
│   ├── accessibility/
│   │   └── AutoClickerAccessibilityService.kt   # 无障碍服务：手势点击/滑动、全局按键、按文本定位节点
│   ├── ui/
│   │   ├── RawTouch.kt                # RawTapDetector + rememberRawTapDetector：悬浮按钮的原始触摸点击判定
│   │   ├── ServiceComposeHost.kt      # Service 内为 ComposeView 补齐生命周期/ViewModel/SavedState 宿主
│   │   └── LabeledRadio.kt            # 共享单选行控件（internal）
│   └── theme/
│       └── Design.kt                  # AppPalette + AutoClickerTheme + AppButton/AppOutlineButton/ModernTextField/ModernCard/ModernText.*
│
└── feature/                           # 按功能特性划分的业务模块
    ├── main/
    │   └── MainScreen.kt              # MainUiState + MainScreen：主页 Compose 界面
    │
    ├── clicker/                       # 「连点器」= 旧功能（坐标循环连点）
    │   ├── FloatingWindowService.kt   # 悬浮窗主服务 + ClickPoint 数据类
    │   ├── FloatingServiceContent.kt  # 悬浮窗 Compose 内容 + FloatingServiceUiState
    │   ├── PipeLineView.kt            # 滑动点连线的自绘 View（起终点 + 箭头）
    │   └── ClickerDialogs.kt          # SettingsDialogContent（全局设置） + PointSettingsDialogContent（点击点设置）
    │
    └── quickclick/                    # 「代点器」= 新功能（文本匹配快捷点击）
        ├── QuickClickService.kt       # 悬浮子按钮服务 + 识别→点击链路 + 脚本管理弹框逻辑
        ├── QuickClickConfig.kt        # QuickClickConfig + QuickScript + QuickClickStore（数据模型与持久化）
        ├── QuickFloatingContent.kt    # 悬浮窗 Compose 内容 + QuickFloatingUiState
        └── QuickDialogs.kt            # QuickConfigDialogContent（新建按钮） + ScriptManagerDialogContent（脚本管理）
```

## 3. 包职责说明

### `core.accessibility`
系统级无障碍服务，两种功能都通过它下发手势。对外暴露静态 `instance`、`performClick`、`performSwipe`、`findTargetByText` 等能力。被 `feature/clicker`、`feature/quickclick`、`MainActivity` 依赖。

### `core.ui`
悬浮窗 / 服务弹框的通用 UI 基础设施：
- `RawTouch`：悬浮窗内所有按钮统一走 `pointerInteropFilter` 一条触摸链路，故不用 `Modifier.clickable`，改用手写点击判定（避免两条事件链路互吞）。
- `ServiceComposeHost`：`Service` 与系统 `AlertDialog` 场景下 `ComposeView` 缺少 `ViewTreeLifecycleOwner`，此类补齐生命周期，并需挂到窗口 DecorView。
- `LabeledRadio`：跨 `clicker` / `quickclick` 复用的单选行。

### `core.theme`
由原 `res/values/styles.xml + colors.xml`（含 night）迁移而来的 Compose 设计系统：色板 `AppPalette`（浅/深两套）、全局主题 `AutoClickerTheme`、复用组件 `AppButton / AppOutlineButton / ModernTextField / ModernCard / ModernText.*`。`ModernTextField` 内部用 `AndroidView` 包原生 `EditText`，以解决 Service 弹框内软键盘不弹的问题。

### `feature.main`
主页。`MainUiState` 为唯一状态源，`MainActivity` 刷新权限/服务状态后驱动重组；含模式分段开关（连点器/代点器）。

### `feature.clicker`（连点器，旧功能）
可拖动、边缘吸附的悬浮主按钮 + 展开操作栏，管理编号点击点与滑动对，按序循环执行点击/滑动/系统按键，支持总循环次数、随机偏移、点位 JSON 保存/加载。

### `feature.quickclick`（代点器，新功能）
折叠/展开/编辑三态悬浮按钮。为子按钮配置「目标文字」，点击时经无障碍文本匹配定位后模拟点击；支持子按钮长按排序、脚本另存为/加载/改名/删除、名称按中英文限长。

## 4. 依赖方向

```
MainActivity
   │  引用（启停/权限/模式）
   ▼
feature.main   feature.clicker   feature.quickclick
   │                │                    │
   └────────────────┴────────────────────┘
                    ▼        （仅向下依赖）
        core.accessibility   core.ui   core.theme
```

- `feature/*` 之间互不引用（`clicker` 与 `quickclick` 完全独立）。
- `core/*` 不引用任何 `feature/*`。

## 5. 关键约束与注意事项

- **无障碍服务已移至 `core.accessibility`**，其 ComponentName 变为 `com.hpu.autoclicker/.core.accessibility.AutoClickerAccessibilityService`。重构后需**在系统设置中关闭再重新开启该无障碍服务**，否则旧入口失效、代点器/连点器点击无效。
- **AndroidManifest** 中三个组件的 `android:name` 已随包更新（`.feature.clicker.FloatingWindowService`、`.feature.quickclick.QuickClickService`、`.core.accessibility.AutoClickerAccessibilityService`）；`MainActivity` 仍在根包。
- **Service 内弹框**：`TYPE_APPLICATION_OVERLAY` 弹框须 `clearFlags(FLAG_ALT_FOCUSABLE_IM)` 才能弹软键盘；承载 `ComposeView` 的弹框须在 `show()` 前把 `ServiceComposeHost` 挂到窗口 DecorView。
- **`ClickPoint`** 作为数据类内联在 `feature/clicker/FloatingWindowService.kt` 末尾；**`QuickScript`/`QuickClickStore`** 内联在 `feature/quickclick/QuickClickConfig.kt`。如后续单个文件继续膨胀，可再拆出独立文件。

## 6. 如何新增一个功能模块

1. 在 `feature/` 下新建 `feature/<name>/`，包含该功能的 `XxxService.kt` + `XxxContent.kt`（如需要）。
2. 复用 `core.*`（无障碍、触摸判定、宿主、主题）；不要在 `feature` 间互相引用。
3. 在 `AndroidManifest.xml` 注册服务（用新全限定名）。
4. 在 `MainActivity` 增加入口/模式，并驱动对应 `Screen`。
