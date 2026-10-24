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
│   │   ├── AppSegmentedControl.kt     # 分段单选（连点器/代点器模式切换、弹框动作类型）；圆角用 background(shape) 画，整段矩形可点
│   │   ├── AppBadge.kt                # 状态徽标：Primary/Success/Warning/Neutral 四色调（权限与运行状态）+ AppPillButton 同尺寸紧凑胶囊按钮
│   │   ├── AppEmptyState.kt           # 空状态占位（脚本管理空列表）
│   │   ├── AppScriptList.kt           # 命名脚本列表行（连点器/代点器共用）：点击加载 + 改名 + 删除 + 空状态
│   │   ├── AppOpacityDialog.kt        # 透明度弹框内容（两个模式共用）：说明 + 悬浮窗滑块 +（连点器）点位滑块 + 百分比，并上报拖动开始/结束及滑块屏幕矩形
│   │   ├── AppSlider.kt               # 统一滑块：胶囊轨道（激活段 primary）+ 圆形滑钮（surface 底 + primary 描边 + 投影），交互沿用 Material3 Slider
│   │   └── AppDialog.kt               # 统一弹框面板：AppDialogPanel(标题/分隔线/可滚动正文/按钮区) + AppDialogActions(取消+主操作) + AppConfirmMessage
│   ├── ui/
│   │   ├── RawTouch.kt                # RawTapDetector + rememberRawTapDetector：悬浮按钮的原始触摸点击判定
│   │   ├── ServiceComposeHost.kt      # Service 内为 ComposeView 补齐生命周期/ViewModel/SavedState 宿主
│   │   ├── ServiceDialogs.kt          # Service 内弹框统一宿主：平台 AlertDialog + 透明窗底 + TYPE_APPLICATION_OVERLAY + stripPlatformPanel 去双层框
│   │   ├── OpacityDragPreview.kt      # 拖动透明度滑块时的预览：隐藏弹框窗口+遮罩，在被拖动滑块原位叠一条 20% 不透明度的百分比进行条
│   │   ├── FloatingSquareButton.kt    # 悬浮窗方块按钮：40dp/12dp 圆角/品牌实心/4dp 投影，连点器与代点器共用
│   │   └── OverlayViews.kt            # 原生浮层（截图框选/相册裁剪/坐标拾取）按钮与底部操作栏底色，读不到 Compose 主题故单独给一套同款视觉
│   └── theme/                         # 设计 token（阶段一建立，页面唯一取值来源）
│       ├── Color.kt                   # 方案 A「钢青 Teal-Steel」浅/深全量 tone（同 hue 低饱和中性色）+ AppSemanticColors(success/warning/primaryPressed) + OverlayPalette(原生浮层专用色板)
│       ├── Type.kt                    # AppTypography：固定字号/字重（标题24/分组20/卡片17/正文15/输入16/辅助13/按钮15/字段标签14/徽标12）
│       ├── Shape.kt                   # AppShapes：按钮/输入 12dp、卡片 16dp、弹窗 20dp、chip 8dp
│       ├── Dimens.kt                  # 间距阶梯 4/8/12/16/24/32 + 语义间距(screenVMargin/sectionGap/blockGap/fieldGap/dialogPadding) + 控件尺寸(含滑块 sliderTrackHeight/sliderThumbSize/sliderThumbStroke/sliderThumbShadow*) + 动效时长
│       └── Theme.kt                   # AutoClickerTheme 装配（显式禁用动态取色）+ LocalAppSemanticColors/AppTheme
│
└── feature/                           # 按功能特性划分的业务模块
    ├── main/
    │   └── MainScreen.kt              # MainUiState + MainScreen：主页 Compose 界面
    │
    ├── clicker/                       # 「连点器」= 旧功能（坐标循环连点）
    │   ├── FloatingWindowService.kt   # 悬浮窗主服务 + ClickPoint 数据类 + 脚本管理/透明度/输入/确认弹框逻辑
    │   ├── FloatingServiceContent.kt  # 悬浮窗 Compose 内容 + FloatingServiceUiState（运行态裁剪按钮栏）
    │   ├── PipeLineView.kt            # 滑动点连线的自绘 View（起终点 + 箭头）
    │   ├── ClickerScript.kt           # ClickerPointData / ClickerScript / ClickerScriptStore（clicker_script_config：scripts / window_alpha / point_alpha + 旧 click_points.json 一次性迁移）
    │   └── ClickerDialogs.kt          # SettingsDialogContent（循环次数 + 脚本列表） + PointSettingsDialogContent（点击点设置） + ClickerInputDialogState
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
        └── QuickDialogs.kt            # 新建/编辑快捷按钮弹框（动作类型分段开关 + 按类型分组的描边卡片：文本/图像/坐标、模板缩略图、高级参数） + 脚本管理弹框（内容复用 AppScriptList / AppOpacityDialogContent）
```

## 3. 包职责说明

### `core.accessibility`
系统级无障碍服务，两种功能都通过它下发手势。对外暴露静态 `instance`、`performClick`、`performSwipe`、`findTargetByText`，以及 `takeScreenshotAsync` + `isScreenshotSupported()`（图像识别截屏，依赖 API 30+，回调主线程 ARGB Bitmap）。被 `feature/clicker`、`feature/quickclick`、`MainActivity` 依赖。

### `core.ui`
悬浮窗 / 服务弹框的通用 UI 基础设施：
- `RawTouch`：悬浮窗内所有按钮统一走 `pointerInteropFilter` 一条触摸链路，故不用 `Modifier.clickable`，改用手写点击判定（避免两条事件链路互吞）。
- `ServiceComposeHost`：`Service` 与系统 `AlertDialog` 场景下 `ComposeView` 缺少 `ViewTreeLifecycleOwner`，此类补齐生命周期，并需挂到窗口 DecorView。
- `ServiceDialogs`：两个悬浮窗服务内弹框的**唯一宿主**。平台 `AlertDialog` + `setView(ComposeView)`，窗口底透明（平台面板底会与自绘圆角面板叠成「双层框」，故用 `stripPlatformPanel()` 按 id 资源名找到 `parentPanel` 并置空），API ≥ 26 挂 `TYPE_APPLICATION_OVERLAY`，`clearFlags(FLAG_ALT_FOCUSABLE_IM)` 保证弹框内软键盘可用，`show()` 后按 `widthRatio` 设宽。
- `FloatingSquareButton`：连点器与代点器悬浮窗按钮的**唯一样式**（40dp 见方、12dp 圆角、品牌实心底、白图标、4dp 投影、按下换 `primaryPressed`、禁用 0.4 透明度）。组件本身不消费点击，触摸仍由调用方经 `pointerInteropFilter` 传入，不 `.clip()` 以免裁掉指针分发。
- `OverlayViews`：截图框选 / 相册裁剪 / 坐标拾取是原生 View 浮层（画在 Canvas 与 Drawable 上），读不到 Compose 主题，故单独提供与 Compose 侧同款的按钮（12dp 圆角、品牌实心或半透明描边、15sp 白字）与底部操作栏底色（半透明黑 + 1dp 分隔线），色值取 `OverlayPalette`。

### `core.theme`（设计 token）
品牌色为「钢青 Teal-Steel」（浅 `#0F6E7D` / 深 `#5AD1E0`），**显式不启用 Material3 动态取色**。文件拆分：
- `Color.kt`：浅/深全量 tone（primary + surfaceContainer 五级 + outline + 语义色带容器）。**中性色与品牌主色同 hue 家族**（低饱和青灰），表面层级靠亮度差而非阴影划分；`AppSemanticColors` 承载 ColorScheme 没有槽位的 success/warning/primaryPressed；`OverlayPalette` 是原生浮层专用色板（品牌实心块/强调色/操作栏底/分隔线/遮罩/网格/三分线/画布底），与 Compose 侧同源但不受深浅主题切换；
- `Type.kt` / `Shape.kt` / `Dimens.kt`：字号字重、圆角、间距阶梯的唯一来源，页面禁止硬编码。间距除 4/8/12/16/24/32 基础阶梯外，另有一组语义间距（`screenVMargin` 页面留白、`sectionGap` 分区之间、`blockGap` 分区内块之间、`fieldGap` 字段与输入框之间、`dialogHPadding`/`dialogVPadding` 弹框内边距），排版需求直接取语义名而非裸数值；
- `Theme.kt`：`AutoClickerTheme` 装配 ColorScheme + Typography + Shapes。

XML 侧 `res/values/colors.xml`（含 values-night）仍服务于主题/状态栏等 View 层，**色值与 Color.kt 同源**——改色必须两处同步，且浅/深两份键必须对称。主题状态栏与页面背景同色（浅色 `windowLightStatusBar=true`、深色 `false`）；首页渐变相关的 `gradient_*` / `status_bar_top` 遗留色键已删除。

### `core.components`（通用组件库）
所有页面的 UI 只允许消费这里的组件与 theme token：按钮（五种变体四态，Outlined 为 `surface` 底 + `outlineVariant` 描边）、卡片（`surface` 底 + 可选细描边，不叠阴影）、语义文本（含 `AppFieldLabel` 表单字段标签）、输入框（**内核保留原生 EditText**，见下文红线）、单选/复选行、分段控件（整段矩形可点，圆角处不留死区）、**滑块 `AppSlider`**（胶囊轨道 + 圆形滑钮，自绘 track/thumb，交互沿用 Material3 Slider）、状态徽标 `AppBadge` 与同尺寸紧凑胶囊按钮 `AppPillButton`、空状态、**统一弹框面板 `AppDialog`**。脚本列表 `AppScriptList` 与透明度弹框 `AppOpacityDialogContent` 是连点器 / 代点器共用的跨功能组件——两个模式的脚本列表行完全一致，透明度页面共用同一套滑块（连点器多一根「点击 / 滑动点位」滑块）；组件本身不碰存储，拖动预览条 `OpacityDragPreview` 也只负责显示。页面不允许各写一套按钮/卡片/输入框。例外：`feature/quickclick` 的全屏截图框选页、`RegionRatioBar` 与坐标拾取浮层为原生 View（Service 浮层窗口场景，非 Compose 页面）。

**统一弹框 `AppDialog.kt`** —— 全项目弹框的唯一外框：`AppDialogPanel(title, subtitle?, actions?, content)` 画出「标题区 → 1dp 分隔线 → 可滚动正文 → 1dp 分隔线 → 按钮区」的圆角面板（`shapes.large` + `surface` 底），整体高度上限为屏高 72%，超出部分正文内部滚动而标题与按钮始终可见；`AppDialogActions(confirmText, onConfirm, cancelText?, onCancel?, confirmVariant?, leading?)` 统一「取消（描边）在左 / 主操作（实心）在右」的按钮行，`leading` 插槽放弱化或危险操作（如「删除」）；`AppConfirmMessage(text)` 是确认框正文。危险操作把主按钮换成 `AppButtonVariant.Danger`（红色实心）。

### `feature.main`
主页。`MainUiState` 为唯一状态源，`MainActivity` 刷新权限/服务状态后驱动重组；含模式分段开关（连点器/代点器）。布局为分区式：Hero 卡片（品牌色底 + 图标 + 模式说明）→ 权限与状态（标题行：标题 + 就绪徽标在左、行尾紧凑「刷新」胶囊按钮；下方卡片三行状态，行间分隔线，条件显示的「获取权限」按钮）→ 运行模式（分段开关）→ 底部主操作（启动/关闭，平铺占满整行）。权限齐备时「获取权限」按钮自动消失，由状态卡片说明一切。

### `feature.clicker`（连点器，旧功能）
可拖动、边缘吸附的悬浮主按钮 + 展开操作栏，管理编号点击点与滑动对，按序循环执行点击/滑动/系统按键，支持总循环次数与随机偏移。**运行中展开栏只保留主按钮与停止按钮**（其余按钮在 Compose 内容里直接不渲染，`FloatingServiceUiState.isRunning` 为唯一开关），主按钮仍可展开/折叠。脚本管理迁到「悬浮窗设置」弹框：命名保存（名称 ≤12 字）/ 点击加载（有未保存点位先确认覆盖，加载前先 `stopRunning`）/ 改名 / 删除，列表行与代点器共用 `AppScriptList`；点位与透明度分别存于 `clicker_script_config.xml` 的 `scripts` 与 `window_alpha` / `point_alpha`（与代点器的 `window_alpha` 独立），旧的 `files/click_points.json` 只在首次读取脚本列表时迁移为「默认脚本」一次（`legacy_migrated` 标记）。“隐藏”按钮已移除，由透明度弹框替代：弹框内两根滑块分别控制悬浮窗与点击 / 滑动点位的不透明度，拖动期间弹框隐藏，进行条（`OpacityDragPreview`，20% 不透明度）显示在被拖动滑块的原位置（位置由滑块通过 `onGloballyPositioned` 上报的屏幕矩形决定）。

### `feature.quickclick`（代点器，新功能）
折叠/展开/编辑三态悬浮按钮，**启动为仅主按钮的折叠态**，单击主按钮展开/折叠（无子按钮时单击直接进入编辑态）。为子按钮配置「目标文字」（文本匹配）、「模板图片」（图像识别）或「X/Y 坐标」（坐标点击），点击时经无障碍定位或直接按坐标模拟点击；图像识别用 `ImageMatcher` 的纯 Kotlin NCC 匹配、`RegionSelectView`/`RegionCaptureOverlay` 框选模板（支持 `RegionRatioBar` 锁定 1:1 / 4:3 / 16:9 / 自定义等裁剪比例）、`ImagePickActivity` 相册裁剪；坐标点击支持手填坐标或经 `CoordinatePickOverlay` 全屏置灰网格拾取（触摸不会穿透到下层应用）。截图/选图期间悬浮窗与配置弹框整体隐藏（弹框同时置 `FLAG_NOT_TOUCHABLE`），结束后恢复；框选页与取点页不设取消按钮，系统返回键即退出并回到配置弹框，相册裁剪页返回同样回传取消并恢复弹框。支持编辑态单击子按钮打开修改/删除弹框、长按拖动排序、脚本另存为/加载/改名/删除、名称按中英文限长；编辑态的「X」关闭悬浮窗（对比 `savedSnapshot` 判断未保存改动并提示保存，未命名列表先输入脚本名），「透明度」按钮用滑块实时调整 `root.alpha` 并持久化到 `window_alpha`。

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
- **Service 内弹框**：`TYPE_APPLICATION_OVERLAY` 弹框须 `clearFlags(FLAG_ALT_FOCUSABLE_IM)` 才能弹软键盘；承载 `ComposeView` 的弹框须在 `show()` 前把 `ServiceComposeHost` 挂到窗口 DecorView。**所有 Service 内弹框一律走 `ServiceDialogs.show()`**，不要自己 new `AlertDialog`——统一宿主负责透明窗底、overlay 窗口类型、去平台双层框与宽度。
- **`ClickPoint`** 作为数据类内联在 `feature/clicker/FloatingWindowService.kt` 末尾；连点器的持久化模型已拆到 `feature/clicker/ClickerScript.kt`（`ClickerPointData` / `ClickerScript` / `ClickerScriptStore`）；**`QuickScript`/`QuickClickStore`** 内联在 `feature/quickclick/QuickClickConfig.kt`。
- **UI 硬约束（美化时也不可破坏）**：
  1. overlay 悬浮窗内 Compose 输入框收不到软键盘——`AppTextField` 内核必须保持原生 `EditText`；`ServiceDialogs.show()` 已在 `show()` 后统一 `clearFlags(FLAG_ALT_FOCUSABLE_IM)`，手工搭建的弹框（`showPointSettingsDialog`、`RegionRatioBar` 自定义比例）必须自行清除。
  2. 悬浮窗按钮手势统一在 `RawTouch` 单一链路（`pointerInteropFilter`），禁止混用 `clickable`；两个悬浮窗的按钮一律用 `FloatingSquareButton`，只换图标，不各写一套。脚本列表里的「点击加载 / 改名 / 删除」是弹框内 Compose 内容（非悬浮窗），可正常使用 `Modifier.clickable`。
  3. 悬浮窗内容的几何尺寸（40dp 按钮、8dp 根内边距等）与 Service 的窗口高度估算、拖动排序行高换算硬耦合，改前需同步 Service 侧数学。连点器运行态只渲染主按钮 + 停止按钮，`isTouchOnAllowedButton` 只放行主按钮与停止按钮记录的位置（`mainBtnWindowRect` / `stopBtnWindowRect`），新增按钮时必须同步这三处。
- **弹框一致性**：全项目弹框统一套 `AppDialogPanel` 外框，标题/分隔线/可滚动正文/按钮区由组件统一排版；按钮行统一用 `AppDialogActions`（取消在左、主操作在右）；危险操作主按钮用 `AppButtonVariant.Danger`。新增弹框不得自绘外框或使用平台默认面板。
- **样式一致性**：页面禁止硬编码颜色/字号/圆角/间距，一律取 `MaterialTheme.colorScheme` / `MaterialTheme.typography` / `MaterialTheme.shapes` / `Dimens`；布局间距优先取 `Dimens` 中的**语义间距**（sectionGap / blockGap / fieldGap / screenVMargin）而非裸数值；例外仅悬浮窗内容的几何 dp（见上）。
- **图像识别（代点器）**：依赖无障碍 `takeScreenshot`（**仅 Android 11 / API 30+**，低版本入口置灰提示），无障碍配置必须声明 `android:canTakeScreenshot="true"`，否则系统直接拒绝截图。截图前 `hideSelfForScreenshot()` + `hideConfigForCapture()` 隐藏悬浮窗与配置弹框并延迟约 150ms，两者都置 `FLAG_NOT_TOUCHABLE`（弹框另加 `FLAG_NOT_FOCUSABLE`），避免不可见窗口仍吞触摸或抢相册焦点；框选浮层关闭（确定 / 系统返回键取消）后才恢复弹框与悬浮窗。「从相册选」同样先隐藏悬浮窗与弹框再拉起 `ImagePickActivity`，取消、返回、选图完成都经 `pendingResult`（含 `onDestroy` 兜底）恢复。模板匹配在 `matchExecutor` 单线程后台执行（不能放主线程，全屏 NCC 会卡 UI），并用 `matchInProgress` 防止并发截图；模板图与匹配参数存 `QuickClickConfig`，图片文件由 `ImageStore` 管 `filesDir/images/`，删脚本/删按钮/换图/取消编辑均需清理旧文件。`ImagePickActivity` 用 `ComponentActivity`（框架 Activity 无 `registerForActivityResult`），其在 `Button.apply{}` 内引用宿主须用 `this@ImagePickActivity`；`ScreenshotResult`/`TakeScreenshotCallback` 是 `AccessibilityService` 嵌套类，不可 import。
- **裁剪比例（代点器图像识别）**：`RegionRatioBar` 由截图浮层与相册裁剪页共用，提供 自由 / 1:1 / 4:3 / 3:4 / 16:9 / 9:16 / 自定义 W:H；`RegionSelectView.setAspectRatio(ratio)` 中的比例是**图像像素坐标的宽/高**，切换比例时保持选框面积与中心不变，锁定后 `applyScale` 只等比改变尺寸、拖动只平移。自定义比例弹框在 `Service` 上下文（截图浮层）中需显式 `TYPE_APPLICATION_OVERLAY` 并清除 `FLAG_ALT_FOCUSABLE_IM`，在 `Activity` 上下文（相册裁剪）中走普通弹框；`RegionRatioBar` 内部按 `context is Activity` 自动分流。选中比例不会写入 `QuickClickConfig`，裁剪结果 PNG 本身即为目标比例。
- **坐标点击（代点器）**：`CoordinatePickOverlay` 是独立的 `TYPE_APPLICATION_OVERLAY` 全屏浮层，**不加** `FLAG_NOT_TOUCHABLE`，触摸由浮层消费，因此点选坐标不会穿透到下层应用；窗口保持可聚焦并在根视图 `dispatchKeyEvent` 拦截 `KEYCODE_BACK`，返回键关闭浮层并恢复配置弹框（无取消按钮）。坐标取 `MotionEvent.rawX/rawY` 屏幕绝对坐标，与 `dispatchGesture` 坐标系一致。拾取期间需隐藏悬浮窗和配置弹框（`decorView.visibility = INVISIBLE` + `FLAG_NOT_TOUCHABLE`），回调后再恢复。

## 6. 如何新增一个功能模块

1. 在 `feature/` 下新建 `feature/<name>/`，包含该功能的 `XxxService.kt` + `XxxContent.kt`（如需要）。
2. 复用 `core.*`（无障碍、触摸判定、宿主、主题）；不要在 `feature` 间互相引用。
3. 在 `AndroidManifest.xml` 注册服务（用新全限定名）。
4. 在 `MainActivity` 增加入口/模式，并驱动对应 `Screen`。
