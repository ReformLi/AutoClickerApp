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
│   ├── components/                    # 通用组件库（阶段二建立，全部消费 theme token）
│   │   ├── AppButton.kt               # 按钮唯一出口：Filled/Outlined/Text/Danger/Success 变体 + 默认/按下/禁用/加载态
│   │   ├── AppCard.kt                 # 卡片容器：surfaceContainer 色调层次，无阴影堆叠，可点击带 ripple
│   │   ├── AppText.kt                 # 语义文本：AppTitle/AppSectionTitle/AppBodyText/AppCaption（只走 Typography）
│   │   ├── AppTextField.kt            # 输入框：外观走 token；内核保留原生 EditText（overlay 软键盘硬约束）
│   │   ├── AppSelection.kt            # AppRadioButtonRow / AppCheckboxRow（整行可点，触控≥48dp）
│   │   ├── AppSegmentedControl.kt     # 分段单选（连点器/代点器模式切换）
│   │   └── AppEmptyState.kt           # 空状态占位（脚本管理空列表）
│   ├── ui/
│   │   ├── RawTouch.kt                # RawTapDetector + rememberRawTapDetector：悬浮按钮的原始触摸点击判定
│   │   └── ServiceComposeHost.kt      # Service 内为 ComposeView 补齐生命周期/ViewModel/SavedState 宿主
│   └── theme/                         # 设计 token（阶段一建立，页面唯一取值来源）
│       ├── Color.kt                   # 方案 A「钢青 Teal-Steel」浅/深全量 tone + AppSemanticColors(success/warning/primaryPressed)
│       ├── Type.kt                    # AppTypography：固定字号/字重（标题22/分组20/卡片17/正文15/输入16/辅助13/按钮15）
│       ├── Shape.kt                   # AppShapes：按钮/输入 12dp、卡片 16dp、弹窗 20dp、chip 8dp
│       ├── Dimens.kt                  # 间距阶梯 4/8/12/16/24/32 + 控件尺寸（触控48/按钮48/输入52）+ 动效时长
│       └── Theme.kt                   # AutoClickerTheme 装配（显式禁用动态取色）+ LocalAppSemanticColors/AppTheme
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

### `core.theme`（设计 token）
品牌色为「钢青 Teal-Steel」（浅 `#0F6E7D` / 深 `#5AD1E0`），**显式不启用 Material3 动态取色**。文件拆分：
- `Color.kt`：浅/深全量 tone（primary + surfaceContainer 五级 + outline + 语义色带容器），`AppSemanticColors` 承载 ColorScheme 没有槽位的 success/warning/primaryPressed；
- `Type.kt` / `Shape.kt` / `Dimens.kt`：字号字重、圆角、间距阶梯的唯一来源，页面禁止硬编码；
- `Theme.kt`：`AutoClickerTheme` 装配 ColorScheme + Typography + Shapes。

XML 侧 `res/values/colors.xml`（含 values-night）仍服务于主题/状态栏等 View 层，**色值与 Color.kt 同源**——改色必须两处同步，且浅/深两份键必须对称。

### `core.components`（通用组件库）
所有页面的 UI 只允许消费这里的组件与 theme token：按钮（五种变体四态）、卡片（色调层次不叠阴影）、语义文本、输入框（**内核保留原生 EditText**，见下文红线）、单选/复选行、分段控件、空状态。页面不允许各写一套按钮/卡片/输入框。

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
- **UI 硬约束（美化时也不可破坏）**：
  1. overlay 悬浮窗内 Compose 输入框收不到软键盘——`AppTextField` 内核必须保持原生 `EditText`，且每条含输入框的弹框路径都要 `clearFlags(FLAG_ALT_FOCUSABLE_IM)`（共三条：`showGlobalSettingsDialog` / `showPointSettingsDialog` / `QuickClickService.showOverlayDialog`）。
  2. 悬浮窗按钮手势统一在 `RawTouch` 单一链路（`pointerInteropFilter`），禁止混用 `clickable`。
  3. 悬浮窗内容的几何尺寸（40dp 按钮、8dp 根内边距等）与 Service 的窗口高度估算、拖动排序行高换算硬耦合，改前需同步 Service 侧数学。
- **样式一致性**：页面禁止硬编码颜色/字号/圆角/间距，一律取 `MaterialTheme.colorScheme` / `MaterialTheme.typography` / `MaterialTheme.shapes` / `Dimens`；例外仅悬浮窗内容的几何 dp（见上）。

## 6. 如何新增一个功能模块

1. 在 `feature/` 下新建 `feature/<name>/`，包含该功能的 `XxxService.kt` + `XxxContent.kt`（如需要）。
2. 复用 `core.*`（无障碍、触摸判定、宿主、主题）；不要在 `feature` 间互相引用。
3. 在 `AndroidManifest.xml` 注册服务（用新全限定名）。
4. 在 `MainActivity` 增加入口/模式，并驱动对应 `Screen`。
