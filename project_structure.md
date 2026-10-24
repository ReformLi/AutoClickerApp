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
│   │   ├── AppCard.kt                 # 卡片容器：surface 底 + 可选细描边，不叠阴影，可点击带 ripple
│   │   ├── AppText.kt                 # 语义文本：AppTitle/AppSectionTitle/AppBodyText/AppCaption/AppFieldLabel（只走 Typography）
│   │   ├── AppTextField.kt            # 输入框：外观走 token；内核保留原生 EditText（overlay 软键盘硬约束）
│   │   ├── AppSelection.kt            # AppRadioButtonRow / AppCheckboxRow（整行可点，触控≥48dp）
│   │   ├── AppSegmentedControl.kt     # 分段单选（连点器/代点器模式切换）
│   │   ├── AppBadge.kt                # 状态徽标：Primary/Success/Warning/Neutral 四色调（权限与运行状态）+ AppPillButton 同尺寸紧凑胶囊按钮
│   │   └── AppEmptyState.kt           # 空状态占位（脚本管理空列表）
│   ├── ui/
│   │   ├── RawTouch.kt                # RawTapDetector + rememberRawTapDetector：悬浮按钮的原始触摸点击判定
│   │   └── ServiceComposeHost.kt      # Service 内为 ComposeView 补齐生命周期/ViewModel/SavedState 宿主
│   └── theme/                         # 设计 token（阶段一建立，页面唯一取值来源）
│       ├── Color.kt                   # 方案 A「钢青 Teal-Steel」浅/深全量 tone（同 hue 低饱和中性色）+ AppSemanticColors(success/warning/primaryPressed)
│       ├── Type.kt                    # AppTypography：固定字号/字重（标题24/分组20/卡片17/正文15/输入16/辅助13/按钮15/字段标签14/徽标12）
│       ├── Shape.kt                   # AppShapes：按钮/输入 12dp、卡片 16dp、弹窗 20dp、chip 8dp
│       ├── Dimens.kt                  # 间距阶梯 4/8/12/16/24/32 + 语义间距(screenVMargin/sectionGap/blockGap/fieldGap/dialogPadding) + 控件尺寸 + 动效时长
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
    └── quickclick/                    # 「代点器」= 新功能（文本匹配 / 图像识别 / 坐标点击）
        ├── QuickClickService.kt       # 悬浮子按钮服务 + 识别→点击链路 + 坐标点击 + 脚本管理弹框逻辑
        ├── QuickClickConfig.kt        # QuickClickConfig(含图像/坐标字段) + ImageStore + QuickScript + QuickClickStore
        ├── ImageMatcher.kt            # 纯 Kotlin 模板匹配引擎（降采样+灰度NCC滑窗+区域限定+粗扫/精扫）
        ├── RegionSelectView.kt        # 区域选择控件：置灰+网格+框内原图+单指拖动/双指缩放+可选宽高比锁定
        ├── RegionRatioBar.kt          # 裁剪比例条：自由 + 1:1/4:3/3:4/16:9/9:16 + 自定义 W:H（截图/相册共用）
        ├── RegionCaptureOverlay.kt    # 截图用全屏捕获浮层（承载 RegionSelectView + 比例条 + 仅「确定」，系统返回键=取消）
        ├── CoordinatePickOverlay.kt   # 全屏坐标拾取浮层：置灰+网格+十字线，点击取屏幕绝对坐标，系统返回键退出
        ├── ImagePickActivity.kt       # 相册选图透明中转 Activity（PickVisualMedia + 比例裁剪 + 回传）
        ├── QuickFloatingContent.kt    # 悬浮窗 Compose 内容 + QuickFloatingUiState
        └── QuickDialogs.kt            # 新建/编辑快捷按钮弹框（文本/图像/坐标、缩略图、高级参数） + 透明度滑块弹框 + 脚本管理弹框
```

## 3. 包职责说明

### `core.accessibility`
系统级无障碍服务，两种功能都通过它下发手势。对外暴露静态 `instance`、`performClick`、`performSwipe`、`findTargetByText`，以及 `takeScreenshotAsync` + `isScreenshotSupported()`（图像识别截屏，依赖 API 30+，回调主线程 ARGB Bitmap）。被 `feature/clicker`、`feature/quickclick`、`MainActivity` 依赖。

### `core.ui`
悬浮窗 / 服务弹框的通用 UI 基础设施：
- `RawTouch`：悬浮窗内所有按钮统一走 `pointerInteropFilter` 一条触摸链路，故不用 `Modifier.clickable`，改用手写点击判定（避免两条事件链路互吞）。
- `ServiceComposeHost`：`Service` 与系统 `AlertDialog` 场景下 `ComposeView` 缺少 `ViewTreeLifecycleOwner`，此类补齐生命周期，并需挂到窗口 DecorView。

### `core.theme`（设计 token）
品牌色为「钢青 Teal-Steel」（浅 `#0F6E7D` / 深 `#5AD1E0`），**显式不启用 Material3 动态取色**。文件拆分：
- `Color.kt`：浅/深全量 tone（primary + surfaceContainer 五级 + outline + 语义色带容器）。**中性色与品牌主色同 hue 家族**（低饱和青灰），表面层级靠亮度差而非阴影划分；`AppSemanticColors` 承载 ColorScheme 没有槽位的 success/warning/primaryPressed；
- `Type.kt` / `Shape.kt` / `Dimens.kt`：字号字重、圆角、间距阶梯的唯一来源，页面禁止硬编码。间距除 4/8/12/16/24/32 基础阶梯外，另有一组语义间距（`screenVMargin` 页面留白、`sectionGap` 分区之间、`blockGap` 分区内块之间、`fieldGap` 字段与输入框之间、`dialogHPadding`/`dialogVPadding` 弹框内边距），排版需求直接取语义名而非裸数值；
- `Theme.kt`：`AutoClickerTheme` 装配 ColorScheme + Typography + Shapes。

XML 侧 `res/values/colors.xml`（含 values-night）仍服务于主题/状态栏等 View 层，**色值与 Color.kt 同源**——改色必须两处同步，且浅/深两份键必须对称。主题状态栏与页面背景同色（浅色 `windowLightStatusBar=true`、深色 `false`）；首页渐变相关的 `gradient_*` / `status_bar_top` 遗留色键已删除。

### `core.components`（通用组件库）
所有页面的 UI 只允许消费这里的组件与 theme token：按钮（五种变体四态，Outlined 为 `surface` 底 + `outlineVariant` 描边）、卡片（`surface` 底 + 可选细描边，不叠阴影）、语义文本（含 `AppFieldLabel` 表单字段标签）、输入框（**内核保留原生 EditText**，见下文红线）、单选/复选行、分段控件、状态徽标 `AppBadge` 与同尺寸紧凑胶囊按钮 `AppPillButton`、空状态。页面不允许各写一套按钮/卡片/输入框。例外：`feature/quickclick` 的全屏截图框选页、`RegionRatioBar` 与坐标拾取浮层为原生 View（Service 浮层窗口场景，非 Compose 页面）。

### `feature.main`
主页。`MainUiState` 为唯一状态源，`MainActivity` 刷新权限/服务状态后驱动重组；含模式分段开关（连点器/代点器）。布局为分区式：Hero 卡片（品牌色底 + 图标 + 模式说明）→ 权限与状态（标题行：标题 + 就绪徽标在左、行尾紧凑「刷新」胶囊按钮；下方卡片三行状态，行间分隔线，条件显示的「获取权限」按钮）→ 运行模式（分段开关）→ 底部主操作（启动/关闭，平铺占满整行）。权限齐备时「获取权限」按钮自动消失，由状态卡片说明一切。

### `feature.clicker`（连点器，旧功能）
可拖动、边缘吸附的悬浮主按钮 + 展开操作栏，管理编号点击点与滑动对，按序循环执行点击/滑动/系统按键，支持总循环次数、随机偏移、点位 JSON 保存/加载。

### `feature.quickclick`（代点器，新功能）
折叠/展开/编辑三态悬浮按钮。为子按钮配置「目标文字」（文本匹配）、「模板图片」（图像识别）或「X/Y 坐标」（坐标点击），点击时经无障碍定位或直接按坐标模拟点击；图像识别用 `ImageMatcher` 的纯 Kotlin NCC 匹配、`RegionSelectView`/`RegionCaptureOverlay` 框选模板（支持 `RegionRatioBar` 锁定 1:1 / 4:3 / 16:9 / 自定义等裁剪比例）、`ImagePickActivity` 相册裁剪；坐标点击支持手填坐标或经 `CoordinatePickOverlay` 全屏置灰网格拾取（触摸不会穿透到下层应用）。截图/选图期间悬浮窗与配置弹框整体隐藏（弹框同时置 `FLAG_NOT_TOUCHABLE`），结束后恢复；框选页与取点页不设取消按钮，系统返回键即退出并回到配置弹框，相册裁剪页返回同样回传取消并恢复弹框。支持编辑态单击子按钮打开修改/删除弹框、长按拖动排序、脚本另存为/加载/改名/删除、名称按中英文限长；编辑态的「X」关闭悬浮窗（对比 `savedSnapshot` 判断未保存改动并提示保存，未命名列表先输入脚本名），「透明度」按钮用滑块实时调整 `root.alpha` 并持久化到 `window_alpha`。

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
  1. overlay 悬浮窗内 Compose 输入框收不到软键盘——`AppTextField` 内核必须保持原生 `EditText`，且每条含输入框的弹框路径都要 `clearFlags(FLAG_ALT_FOCUSABLE_IM)`（共四条：`showGlobalSettingsDialog` / `showPointSettingsDialog` / `QuickClickService.showOverlayDialog` / `RegionRatioBar` 自定义比例弹框）。
  2. 悬浮窗按钮手势统一在 `RawTouch` 单一链路（`pointerInteropFilter`），禁止混用 `clickable`。
  3. 悬浮窗内容的几何尺寸（40dp 按钮、8dp 根内边距等）与 Service 的窗口高度估算、拖动排序行高换算硬耦合，改前需同步 Service 侧数学。
- **样式一致性**：页面禁止硬编码颜色/字号/圆角/间距，一律取 `MaterialTheme.colorScheme` / `MaterialTheme.typography` / `MaterialTheme.shapes` / `Dimens`；布局间距优先取 `Dimens` 中的**语义间距**（sectionGap / blockGap / fieldGap / screenVMargin）而非裸数值；例外仅悬浮窗内容的几何 dp（见上）。
- **图像识别（代点器）**：依赖无障碍 `takeScreenshot`（**仅 Android 11 / API 30+**，低版本入口置灰提示），无障碍配置必须声明 `android:canTakeScreenshot="true"`，否则系统直接拒绝截图。截图前 `hideSelfForScreenshot()` + `hideConfigForCapture()` 隐藏悬浮窗与配置弹框并延迟约 150ms，两者都置 `FLAG_NOT_TOUCHABLE`（弹框另加 `FLAG_NOT_FOCUSABLE`），避免不可见窗口仍吞触摸或抢相册焦点；框选浮层关闭（确定 / 系统返回键取消）后才恢复弹框与悬浮窗。「从相册选」同样先隐藏悬浮窗与弹框再拉起 `ImagePickActivity`，取消、返回、选图完成都经 `pendingResult`（含 `onDestroy` 兜底）恢复。模板匹配在 `matchExecutor` 单线程后台执行（不能放主线程，全屏 NCC 会卡 UI），并用 `matchInProgress` 防止并发截图；模板图与匹配参数存 `QuickClickConfig`，图片文件由 `ImageStore` 管 `filesDir/images/`，删脚本/删按钮/换图/取消编辑均需清理旧文件。`ImagePickActivity` 用 `ComponentActivity`（框架 Activity 无 `registerForActivityResult`），其在 `Button.apply{}` 内引用宿主须用 `this@ImagePickActivity`；`ScreenshotResult`/`TakeScreenshotCallback` 是 `AccessibilityService` 嵌套类，不可 import。
- **裁剪比例（代点器图像识别）**：`RegionRatioBar` 由截图浮层与相册裁剪页共用，提供 自由 / 1:1 / 4:3 / 3:4 / 16:9 / 9:16 / 自定义 W:H；`RegionSelectView.setAspectRatio(ratio)` 中的比例是**图像像素坐标的宽/高**，切换比例时保持选框面积与中心不变，锁定后 `applyScale` 只等比改变尺寸、拖动只平移。自定义比例弹框在 `Service` 上下文（截图浮层）中需显式 `TYPE_APPLICATION_OVERLAY` 并清除 `FLAG_ALT_FOCUSABLE_IM`，在 `Activity` 上下文（相册裁剪）中走普通弹框；`RegionRatioBar` 内部按 `context is Activity` 自动分流。选中比例不会写入 `QuickClickConfig`，裁剪结果 PNG 本身即为目标比例。
- **坐标点击（代点器）**：`CoordinatePickOverlay` 是独立的 `TYPE_APPLICATION_OVERLAY` 全屏浮层，**不加** `FLAG_NOT_TOUCHABLE`，触摸由浮层消费，因此点选坐标不会穿透到下层应用；窗口保持可聚焦并在根视图 `dispatchKeyEvent` 拦截 `KEYCODE_BACK`，返回键关闭浮层并恢复配置弹框（无取消按钮）。坐标取 `MotionEvent.rawX/rawY` 屏幕绝对坐标，与 `dispatchGesture` 坐标系一致。拾取期间需隐藏悬浮窗和配置弹框（`decorView.visibility = INVISIBLE` + `FLAG_NOT_TOUCHABLE`），回调后再恢复。

## 6. 如何新增一个功能模块

1. 在 `feature/` 下新建 `feature/<name>/`，包含该功能的 `XxxService.kt` + `XxxContent.kt`（如需要）。
2. 复用 `core.*`（无障碍、触摸判定、宿主、主题）；不要在 `feature` 间互相引用。
3. 在 `AndroidManifest.xml` 注册服务（用新全限定名）。
4. 在 `MainActivity` 增加入口/模式，并驱动对应 `Screen`。
