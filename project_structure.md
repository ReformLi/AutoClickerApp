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
├── MainActivity.kt                    # 应用入口：权限引导、服务启停、三模块（连点器/代点器/录点器）切换，驱动 MainScreen
│
├── core/                              # 跨功能共享基础设施（不含业务逻辑）
│   ├── mode/
│   │   └── AppMode.kt                 # 三模块枚举（CLICKER/QUICK_CLICK/RECORDER）+ 持久化值（兼容旧 old/new）+ 展示名/说明
│   ├── accessibility/
│   │   └── AutoClickerAccessibilityService.kt   # 无障碍服务：手势点击/滑动、代点器通用手势注入、全局按键、按文本定位节点
│   ├── components/                    # 通用组件库（阶段二建立，全部消费 theme token）
│   │   ├── AppButton.kt               # 按钮唯一出口：Filled/Outlined/Text/Danger/Success 变体 + 默认/按下/禁用/加载态
│   │   ├── AppCard.kt                 # 卡片容器：surface 底 + 可选细描边，不叠阴影，可点击带 ripple
│   │   ├── AppText.kt                 # 语义文本：AppTitle/AppSectionTitle/AppBodyText/AppCaption/AppFieldLabel（只走 Typography）
│   │   ├── AppTextField.kt            # 输入框：外观走 token；内核保留原生 EditText（overlay 软键盘硬约束）；setText 后选区按实际文本夹取（InputFilter 截短时不越界）
│   │   ├── AppSelection.kt            # AppRadioButtonRow / AppCheckboxRow（整行可点，触控≥48dp）
│   │   ├── AppSegmentedControl.kt     # 分段单选（三模块切换、识别方式、动作类型、滑动方向、回放倍速）；圆角用 background(shape) 画，整段矩形可点
│   │   ├── AppBadge.kt                # 状态徽标：Primary/Success/Warning/Neutral 四色调（权限与运行状态）+ AppPillButton 同尺寸紧凑胶囊按钮
│   │   ├── AppEmptyState.kt           # 空状态占位（脚本管理空列表）
│   │   ├── AppScriptList.kt           # 命名脚本列表行（连点器/代点器/录点器共用）：点击加载 + 改名 + 删除 + 空状态
│   │   ├── AppOpacityDialog.kt        # 透明度弹框内容（两个模式共用）：说明 + 悬浮窗滑块 +（连点器）点位滑块 + 百分比，并上报拖动开始/结束及滑块屏幕矩形
│   │   ├── AppSlider.kt               # 统一滑块：胶囊轨道（激活段 primary）+ 圆形滑钮（surface 底 + primary 描边 + 投影），交互沿用 Material3 Slider
│   │   └── AppDialog.kt               # 统一弹框面板：AppDialogPanel(标题/分隔线/可滚动正文/按钮区) + AppDialogActions(取消+主操作) + AppConfirmMessage
│   ├── ui/
│   │   ├── RawTouch.kt                # RawTapDetector + rememberRawTapDetector：悬浮按钮的原始触摸点击判定
│   │   ├── ServiceComposeHost.kt      # Service 内为 ComposeView 补齐生命周期/ViewModel/SavedState 宿主
│   │   ├── ServiceDialogs.kt          # Service 内弹框统一宿主：平台 AlertDialog + 透明窗底 + TYPE_APPLICATION_OVERLAY + stripPlatformPanel 去双层框
│   │   ├── OpacityDragPreview.kt      # 拖动透明度滑块时的预览：隐藏弹框窗口+遮罩，在被拖动滑块原位叠一条 20% 不透明度的百分比进行条
│   │   ├── FloatingSquareButton.kt    # 悬浮窗方块按钮：40dp/12dp 圆角/品牌实心/4dp 投影（禁用态无投影），三个模块共用
│   │   ├── FloatingWindowBounds.kt    # 悬浮窗几何工具（三模块共用）：真实屏幕尺寸、x/y 原点偏移、可用区域、安全区间夹取
│   │   ├── WindowTouchRegion.kt       # 窗口触摸区域：反射注册隐藏 API 的 TOUCHABLE_INSETS_REGION，把悬浮窗里不接收触摸的部分挖掉（失败退回整窗可点）
│   │   ├── SystemBarInsets.kt         # 全面屏适配：liftBottomBarAboveNavigationBar 把铺满整屏的页面/浮层底部操作栏抬到导航栏之上
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
    │   └── MainScreen.kt              # MainUiState + MainScreen：主页 Compose 界面（内容按 safeDrawing 避让状态栏/导航栏/刘海）
    │
    ├── clicker/                       # 「连点器」= 旧功能（坐标循环连点）
    │   ├── FloatingWindowService.kt   # 悬浮窗主服务 + ClickPoint 数据类 + 脚本管理/透明度/输入/确认弹框逻辑
    │   ├── FloatingServiceContent.kt  # 悬浮窗 Compose 内容 + FloatingServiceUiState（运行态裁剪按钮栏）
    │   ├── PipeLineView.kt            # 滑动通道自绘 View（品牌青轨带 + 实线中线 + 白色 V 形箭头，两端按标记半径留白，执行时切强调色）
    │   ├── PointMarkerView.kt         # 点位标记自绘 View（点击 / 滑动起点 / 滑动终点共用的 44dp 圆角方块，执行时切强调色）
    │   ├── ClickerScript.kt           # ClickerPointData / ClickerScript / ClickerScriptStore（clicker_script_config：scripts / window_alpha / point_alpha + 旧 click_points.json 一次性迁移）
    │   └── ClickerDialogs.kt          # SettingsDialogContent（循环次数 + 脚本列表） + PointSettingsDialogContent（点击点设置） + ClickerInputDialogState
    │
    ├── quickclick/                    # 「代点器」= 新功能（识别方式：文本 / 图像 / 坐标；动作：点击 / 双击 / 长按 / 滑动）
    │   ├── QuickClickService.kt       # 悬浮子按钮服务 + 识别→坐标→随机偏移→构造并注入手势 + 脚本管理弹框逻辑
    │   ├── QuickClickConfig.kt        # QuickClickConfig(含图像/坐标/动作/偏移字段) + ImageStore + QuickScript + QuickClickStore（含旧脚本动作字段迁移）
    │   ├── ImageMatcher.kt            # 纯 Kotlin 模板匹配引擎（降采样+灰度NCC滑窗+区域限定+粗扫/精扫）
    │   ├── RegionSelectView.kt        # 区域选择控件：置灰+网格+框内原图+单指拖动/双指缩放+可选宽高比锁定
    │   ├── RegionRatioBar.kt          # 裁剪比例条：自由 + 1:1/4:3/3:4/16:9/9:16 + 自定义 W:H（截图/相册共用）
    │   ├── RegionCaptureOverlay.kt    # 截图用全屏捕获浮层（承载 RegionSelectView + 比例条 + 仅「确定」，系统返回键=取消）
    │   ├── CoordinatePickOverlay.kt   # 全屏坐标拾取浮层：置灰+网格+十字线，点击取屏幕绝对坐标，系统返回键退出
    │   ├── ImagePickActivity.kt       # 相册选图透明中转 Activity（PickVisualMedia + 比例裁剪 + 回传）
    │   ├── QuickFloatingContent.kt    # 悬浮窗 Compose 内容 + QuickFloatingUiState
    │   └── QuickDialogs.kt            # 新建/编辑快捷按钮弹框（识别方式分段开关 + 按类型分组的描边卡片 + 动作类型四段开关与动态参数 + 随机坐标偏移分组） + 脚本管理弹框（内容复用 AppScriptList / AppOpacityDialogContent）
    │
    └── recorder/                      # 「录点器」= 录制 / 回放（与另外两个模块零共享业务数据）
        ├── RecorderService.kt         # 悬浮窗主服务 + 编辑/运行态切换 + 向下/向右生长的窗口伸缩与横竖排判定 + 录制会话/草稿/现场/脚本管理/弹框逻辑
        ├── RecorderFloatingContent.kt # 悬浮窗 Compose 内容 + RecorderFloatingUiState（主按钮在首位 + 信息条 + 按钮列/行；信息条 40dp 方块、0 步不渲染、每行独立缩字号）
        ├── RecorderBarContent.kt      # 准备 / 录制 / 暂停小条（RecorderBarState + RecorderBarPhase + 步数闪动；底色固定 50% 半透明，仅 ≡ 把手与两个按钮接收触摸，可点矩形按窗口坐标经 onTouchLayout 上报）
        ├── RecorderCaptureOverlay.kt  # 全屏透明触摸捕获层（原生 View）：CLICK/LONG_PRESS/SWIPE 判定 + 落点圆环回显 + NOT_TOUCHABLE 暂停 + 忽略小条矩形内的触摸（避免幽灵步骤）
        ├── RecorderPlaybackEngine.kt  # 回放引擎：delayBefore÷倍速等待 → 检查无障碍 → 逐步注入；generation 守卫 + 错误自动暂停
        ├── RecorderScript.kt          # RecorderStep/RecorderScript/RecorderStore + RecorderDraft/RecorderPlaybackSession/RecorderStateStore（原子写）
        └── RecorderDialogs.kt         # 结束录制三选一 + 关闭三选一 + 草稿恢复三选一 + 脚本管理（复用 AppScriptList）+ 名称限长（中文 ≤3 / 英文数字 ≤6，InputFilter 输入层丢弃）+ 启动确认（重复次数在此配置并写回脚本）+ 设置（倍速 + 透明度 + 底部「关闭悬浮窗」；透明度复用 AppOpacityDialogContent，拖动时由宿主隐藏弹框并显示预览条）
```

## 3. 包职责说明

### `core.mode`
三模块枚举 `AppMode`（`CLICKER` / `QUICK_CLICK` / `RECORDER`），带持久化值、展示名与一句话说明；`fromPref` 兼容旧版本的 `old` / `new`，已有用户的模式选择不会丢。只描述「有哪几个模块」，不含任何业务逻辑。

### `core.accessibility`
系统级无障碍服务，三个模块都通过它下发手势。对外暴露静态 `instance`、`performClick`、`performSwipe`、`dispatchQuickGesture`（代点器通用手势注入：接收已构造好的 `GestureDescription`，用 `onResult` 回传完成/取消/提交失败）、`dispatchInjectedGesture`（录点器回放与录制回显共用，语义同 `dispatchQuickGesture`）、`findTargetByText`，以及 `takeScreenshotAsync` + `isScreenshotSupported()`（图像识别截屏，依赖 API 30+，回调主线程 ARGB Bitmap）。`findTargetByText` 分三层查找：系统 `findAccessibilityNodeInfosByText` 结果二次校验 → 自行遍历节点树兜底 → 文字被拆成多节点时按子树拼接命中；文本比较统一去掉空白并忽略大小写，候选只保留可见节点，优先可点击 / 文字完全相等 / 最深，全部落空时打印窗口包名与可见文字清单（`adb logcat -s ClickService`）。被 `feature/clicker`、`feature/quickclick`、`feature/recorder`、`MainActivity` 依赖。

### `core.ui`
悬浮窗 / 服务弹框的通用 UI 基础设施：
- `RawTouch`：悬浮窗内所有按钮统一走 `pointerInteropFilter` 一条触摸链路，故不用 `Modifier.clickable`，改用手写点击判定（避免两条事件链路互吞）。
- `ServiceComposeHost`：`Service` 与系统 `AlertDialog` 场景下 `ComposeView` 缺少 `ViewTreeLifecycleOwner`，此类补齐生命周期，并需挂到窗口 DecorView。
- `ServiceDialogs`：三个悬浮窗服务内弹框的**唯一宿主**。平台 `AlertDialog` + `setView(ComposeView)`，窗口底透明（平台面板底会与自绘圆角面板叠成「双层框」，故用 `stripPlatformPanel()` 按 id 资源名找到 `parentPanel` 并置空），API ≥ 26 挂 `TYPE_APPLICATION_OVERLAY`，`clearFlags(FLAG_ALT_FOCUSABLE_IM)` 保证弹框内软键盘可用，`show()` 后按 `widthRatio` 设宽。
- `FloatingSquareButton`：连点器、代点器与录点器悬浮窗按钮的**唯一样式**（40dp 见方、12dp 圆角、品牌实心底、白图标、4dp 投影、按下换 `primaryPressed`、禁用 0.4 透明度且投影归零）。禁用态必须关投影：半透明方块几乎不可见，残留的投影会压在相邻按钮上、看起来像两个透明按钮叠影。组件本身不消费点击，触摸仍由调用方经 `pointerInteropFilter` 传入，不 `.clip()` 以免裁掉指针分发。
- `FloatingWindowBounds`：三个悬浮窗服务共用的几何工具。`displaySize()` 取 `getRealSize()`；`originOffset()` 用 `getLocationOnScreen - LayoutParams.x/y` 求出 x/y 原点相对屏幕的偏移（状态栏 / 侧边导航栏占位）；`usableSize()` = 真实屏幕减偏移；`screenUsableSize()` = 真实屏幕减 `systemInsets()` 与内边距，**不看窗口当前位置**，供「横排还是竖排」这类布局方向判定使用（`usableSize()` 依赖的偏移在旋转 / 弹框那一帧可能是旧值，拿它判方向会锁死在横排）；`safeBounds()` 再用 `systemInsets()`（`getRootWindowInsets` + API 30 `currentWindowMetrics.windowInsets`，旧版本回退导航栏高度资源，取三来源最大值，含 `displayCutout`）扣掉状态栏 / 导航栏 / 挖孔并加内边距，得到主窗口真正的落点区间；`clampOffset()` / `clampInside()` 在「区间 - 窗口」为负时贴起始边，不会抛 `IllegalArgumentException`。横竖屏下所有拖动、吸边、旋转钳制都必须使用可用区域 / 安全区，**不能再直接拿 `getRectSize()` 当可见高度**。
- **吸附方向**：三个服务各持有 `dockedLeft`（竖排）与 `dockedTop`（横排）两个偏好，并用 `dockTopBottom` 记录当前吸附轴。判定跟随「长边方向」：`ui.horizontal || 屏幕横屏` 时贴上下边，其余贴左右边（横屏下的折叠方形按钮同样贴上下）。吸附加只作用于吸附轴，另一轴保留用户松手时的位置——横条不再强制靠右，可以停在左上 / 正中 / 右下。`snapToEdge()` 在松手时按窗口中心落在半屏的位置更新偏好；换轴时（进入横排、旋转）按窗口左上角所在的半区确定贴哪条边，不用宽高算中心，避免旋转瞬间拿旧方向的视口尺寸。拖动期间的安全区在 `ACTION_DOWN` 缓存进 `dragBounds`，避免每帧用滞后的 `getLocationOnScreen` 重算原点偏移。
- `OverlayViews`：截图框选 / 相册裁剪 / 坐标拾取是原生 View 浮层（画在 Canvas 与 Drawable 上），读不到 Compose 主题，故单独提供与 Compose 侧同款的按钮（12dp 圆角、品牌实心或半透明描边、15sp 白字）与底部操作栏底色（半透明黑 + 1dp 分隔线），色值取 `OverlayPalette`；录点器的捕获层同样自行绘制，取色统一走 `OverlayPalette`。

### `core.theme`（设计 token）
品牌色为「钢青 Teal-Steel」（浅 `#0F6E7D` / 深 `#5AD1E0`），**显式不启用 Material3 动态取色**。文件拆分：
- `Color.kt`：浅/深全量 tone（primary + surfaceContainer 五级 + outline + 语义色带容器）。**中性色与品牌主色同 hue 家族**（低饱和青灰），表面层级靠亮度差而非阴影划分；`AppSemanticColors` 承载 ColorScheme 没有槽位的 success/warning/primaryPressed；`OverlayPalette` 是原生浮层专用色板（品牌实心块/强调色/品牌渐变亮暗端/品牌浅底与浅底前景色/操作栏底/分隔线/遮罩/网格/三分线/画布底），与 Compose 侧同源但不受深浅主题切换；
- `Type.kt` / `Shape.kt` / `Dimens.kt`：字号字重、圆角、间距阶梯的唯一来源，页面禁止硬编码。间距除 4/8/12/16/24/32 基础阶梯外，另有一组语义间距（`screenVMargin` 页面留白、`sectionGap` 分区之间、`blockGap` 分区内块之间、`fieldGap` 字段与输入框之间、`dialogHPadding`/`dialogVPadding` 弹框内边距），排版需求直接取语义名而非裸数值；
- `Theme.kt`：`AutoClickerTheme` 装配 ColorScheme + Typography + Shapes。

XML 侧 `res/values/colors.xml`（含 values-night）仍服务于主题/状态栏等 View 层，**色值与 Color.kt 同源**——改色必须两处同步，且浅/深两份键必须对称。主题状态栏与页面背景同色（浅色 `windowLightStatusBar=true`、深色 `false`）；首页渐变相关的 `gradient_*` / `status_bar_top` 遗留色键已删除。

### `core.components`（通用组件库）
所有页面的 UI 只允许消费这里的组件与 theme token：按钮（五种变体四态，Outlined 为 `surface` 底 + `outlineVariant` 描边）、卡片（`surface` 底 + 可选细描边，不叠阴影）、语义文本（含 `AppFieldLabel` 表单字段标签）、输入框（**内核保留原生 EditText**，见下文红线）、单选/复选行、分段控件（整段矩形可点，圆角处不留死区）、**滑块 `AppSlider`**（胶囊轨道 + 圆形滑钮，自绘 track/thumb，交互沿用 Material3 Slider）、状态徽标 `AppBadge` 与同尺寸紧凑胶囊按钮 `AppPillButton`、空状态、**统一弹框面板 `AppDialog`**。脚本列表 `AppScriptList` 是连点器 / 代点器 / 录点器共用的跨功能组件——三个模块的脚本列表行完全一致；透明度弹框 `AppOpacityDialogContent` 由三个模块共用——连点器 / 代点器是独立弹框（连点器多一根点位滑块），录点器把它嵌在设置弹框里与回放倍速同屏；组件本身不碰存储，拖动预览条 `OpacityDragPreview` 也只负责显示。页面不允许各写一套按钮/卡片/输入框。例外：`feature/quickclick` 的全屏截图框选页、`RegionRatioBar` 与坐标拾取浮层，以及 `feature/recorder` 的全屏录制捕获层为原生 View（Service 浮层窗口场景，非 Compose 页面）。

**统一弹框 `AppDialog.kt`** —— 全项目弹框的唯一外框：`AppDialogPanel(title, subtitle?, actions?, content)` 画出「标题区 → 1dp 分隔线 → 可滚动正文 → 1dp 分隔线 → 按钮区」的圆角面板（`shapes.large` + `surface` 底），整体高度上限为屏高 72%，超出部分正文内部滚动而标题与按钮始终可见；`AppDialogActions(confirmText, onConfirm, cancelText?, onCancel?, confirmVariant?, leading?)` 统一「取消（描边）在左 / 主操作（实心）在右」的按钮行，`leading` 插槽放弱化或危险操作（如「删除」）；`AppConfirmMessage(text)` 是确认框正文。危险操作把主按钮换成 `AppButtonVariant.Danger`（红色实心）。

### `feature.main`
主页。`MainUiState` 为唯一状态源，`MainActivity` 刷新权限/服务状态后驱动重组；含模块分段开关（连点器 / 代点器 / 录点器）。布局为分区式：Hero 卡片（品牌色底 + 图标 + 模式说明）→ 权限与状态（标题行：标题 + 就绪徽标在左、行尾紧凑「刷新」胶囊按钮；下方卡片三行状态，行间分隔线，条件显示的「获取权限」按钮）→ 运行模式（分段开关）→ 底部主操作（启动/关闭，平铺占满整行）。权限齐备时「获取权限」按钮自动消失，由状态卡片说明一切。切换模块：权限不齐直接失败并保留原模块；当前悬浮窗在运行则先 `stopService` 旧模块（录点器借此保存草稿 / 回放现场），250ms 后自动启动目标模块并提示，避免两个悬浮窗短暂叠加；未运行则只切换持久化值与选中态。

### `feature.clicker`（连点器，旧功能）
可拖动、边缘吸附的悬浮主按钮 + 展开操作栏，管理编号点击点与滑动对，按序循环执行点击/滑动/系统按键，支持总循环次数与随机偏移。**横竖屏**：`onConfigurationChanged` 按新尺寸重选布局，主窗用 `FloatingWindowBounds.safeBounds()` 按当前吸附轴贴边（竖排左右、横排或横屏上下，另一轴保留位置，距边 12dp），所有点位 / 滑动通道均用 `usableSize()` 夹回可见区域，350ms 后再复检一次；竖排按钮栏在横屏放不下时自动改为横向一排。**点位视觉统一走 `PointMarkerView`**：44dp 圆角方块（14dp 圆角），点击点为品牌青实心渐变 + 白色描边 + 数字外套细白环，滑动起点为实心态、终点为浅底描边态；**滑动通道走 `PipeLineView`**：品牌青半透明轨带 + 实线中线 + 白色 V 形箭头，两端按标记半径留白。**运行中点位保持正常显示**（沿用用户设置的 `point_alpha`，不再压到 30%），手势穿透只靠 `FLAG_NOT_TOUCHABLE`；某个点正在执行时该标记（滑动时含起点 / 终点 / 通道）短暂切到强调色 `OverlayPalette.accent` 强调「这一步已执行」，执行完复原。**运行中展开栏只保留主按钮与停止按钮**（其余按钮在 Compose 内容里直接不渲染，`FloatingServiceUiState.isRunning` 为唯一开关），主按钮仍可展开/折叠。脚本管理迁到「悬浮窗设置」弹框：命名保存（名称 ≤12 字）/ 点击加载（有未保存点位先确认覆盖，加载前先 `stopRunning`）/ 改名 / 删除，列表行与代点器共用 `AppScriptList`；点位与透明度分别存于 `clicker_script_config.xml` 的 `scripts` 与 `window_alpha` / `point_alpha`（与代点器的 `window_alpha` 独立），旧的 `files/click_points.json` 只在首次读取脚本列表时迁移为「默认脚本」一次（`legacy_migrated` 标记）。“隐藏”按钮已移除，由透明度弹框替代：弹框内两根滑块分别控制悬浮窗与点击 / 滑动点位的不透明度，拖动期间弹框隐藏，进行条（`OpacityDragPreview`，20% 不透明度）显示在被拖动滑块的原位置（位置由滑块通过 `onGloballyPositioned` 上报的屏幕矩形决定）。

### `feature.quickclick`（代点器，新功能）
折叠/展开/编辑三态悬浮按钮，**启动为仅主按钮的折叠态**，单击主按钮展开/折叠（无子按钮时单击直接进入编辑态）。**横竖屏**：`onConfigurationChanged` 后 `clampWindow()` 重选横排 / 竖排，主窗按当前吸附轴贴边（竖排左右、横排或横屏上下，另一轴保留位置，距边 12dp，`safeBounds()` 内落点），350ms 复检；横排时编辑态长按拖动排序改用 X 轴换算行号，竖排仍用 Y 轴；两轴都放不下时提示旋转设备。为子按钮配置识别方式（「目标文字」文本匹配 / 「模板图片」图像识别 / 「X/Y 坐标」坐标定位）与动作类型（点击 / 双击 / 长按 / 滑动）；执行时识别只产出坐标，随后统一叠加随机偏移并按动作构造手势一次注入（详见「手势注入」约束）。图像识别用 `ImageMatcher` 的纯 Kotlin NCC 匹配、`RegionSelectView`/`RegionCaptureOverlay` 框选模板（支持 `RegionRatioBar` 锁定 1:1 / 4:3 / 16:9 / 自定义等裁剪比例）、`ImagePickActivity` 相册裁剪；坐标点击支持手填坐标或经 `CoordinatePickOverlay` 全屏置灰网格拾取（触摸不会穿透到下层应用）。截图/选图期间悬浮窗与配置弹框整体隐藏（弹框同时置 `FLAG_NOT_TOUCHABLE`），结束后恢复；框选页与取点页不设取消按钮，系统返回键即退出并回到配置弹框，相册裁剪页返回同样回传取消并恢复弹框。支持编辑态单击子按钮打开修改/删除弹框、长按拖动排序、脚本另存为/加载/改名/删除、名称按中英文限长；编辑态的「X」关闭悬浮窗（对比 `savedSnapshot` 判断未保存改动并提示保存，未命名列表先输入脚本名），「透明度」按钮用滑块实时调整 `root.alpha` 并持久化到 `window_alpha`。

### `feature.recorder`（录点器，录制 / 回放）

与另外两个模块**完全独立**的第三模块：只共用通用悬浮窗框架（`FloatingSquareButton`、`RawTouch`、拖动吸边）与弹框组件，**不读写**连点器的点位/脚本，也不读写代点器的按钮/规则。**横竖屏**：`onConfigurationChanged` 后按朝向与可用长度选择竖排 / 横排（竖屏单列，横屏展开一律横排，见 `shouldUseHorizontalLayout()`；判尺寸用位置无关的 `FloatingWindowBounds.screenUsableSize()`，不用 `usableSize()`，否则旋转 / 弹框瞬间的旧原点偏移会让竖排误判成放不下并锁死在横排），主窗按当前吸附轴贴边（竖排左右、横排或横屏上下，另一轴保留位置，距边 12dp，`safeBounds()` 内落点），录制小条用 `usableSize()` 钳制，350ms 复检；两轴都放不下时 toast 提示旋转设备。悬浮窗与连点器 / 代点器同一形态：**主按钮固定在首位**（竖排顶部 / 横排左端），展开时面板向下 / 向右生长、窗口原点不动，因此展开、折叠、切换编辑/运行态都不会出现整窗平移的抽帧感（不再需要锚定补偿）。三种形态：

1. **编辑态**：展开后自上而下是**主按钮 + 信息条（有脚本时）+ 录制 + 启动 + 文件 + 设置 + 关闭**（横屏为同一顺序的横排，主按钮恒在首位）。信息条与主按钮等大（40dp 方块），底色用 `primaryContainer` + `onPrimaryContainer`（浅青底深青字，与实心按钮同族但表明只读，不用白色），**0 步时整个信息条不渲染**，不出现「未命名脚本」占位；「启动」在 0 步时置灰，点击先弹「开始回放」确认框；「文件」打开脚本管理；「设置」含倍速、透明度与底部红色「关闭悬浮窗」；末位「关闭」用与连点器 / 代点器相同的 `ic_exit` 图标，和设置弹框底部的入口共用 `requestCloseWindow()`（回放中先停止，有未保存步骤时三选一）。未完成草稿仍由启动弹框与「文件」入口的「恢复草稿」处理。
2. **录制态**：先出「准备录制」小条（引导文案 + 开始 / 取消），点开始后创建 `RecorderCaptureOverlay` 全屏透明捕获层；小条切为录制态（白底红描边 + 呼吸红点 + 步数），每记一步整条闪动、捕获层在落点画扩散圆环。小条底色固定 50% 半透明（文字 / 按钮不透明，不跟随设置里的悬浮窗透明度）；**只有 ≡ 把手（36dp，按住拖动移动小条）与右侧两个按钮接收触摸**，其余区域通过 `core/ui/WindowTouchRegion`（反射注册隐藏 API `TOUCHABLE_INSETS_REGION`，失败退回整窗可点）从窗口触摸区域里挖掉，点击直接落到底层应用；录制态下捕获层在小条下面，Service 把小条的屏幕矩形（拖动 / 旋转时刷新）作为忽略区同步给 `RecorderCaptureOverlay`，落在小条非按钮区域的触摸既不记录也不提示。捕获层的位移/按压判定产出 CLICK / LONG_PRESS / SWIPE，系统栏区域不记录并提示；暂停时捕获层置 `FLAG_NOT_TOUCHABLE` 把触摸交还其它应用，「继续」不补算暂停时间；停止后弹「保存并结束 / 继续录制 / 丢弃录制」三选一。
3. **运行态**：不渲染任何编辑控件，展开后自上而下是**主按钮 + 信息条 + 启动/暂停 + 停止 + 返回**（横屏为同一顺序的横排）。信息条只读，三行依次是脚本名、`第 X/Y 轮`、`第 N/M 步`，每行按可用宽度独立缩字号（9sp → 6sp，每档 0.5sp），数字变长时三行字号可能不同；倍速标记随 40dp 方块一起移除。暂停中图标切为继续，回放中停止才可点；**停止与暂停语义不同，保留为两个按钮**（停止复位到「待运行」并留在运行态，首按钮回到「启动」，停止键置灰）。**循环次数移出运行态**：在点「启动」前的「开始回放」确认框里配置（`RecorderScript.repeatCount`，1–999，即时写回当前脚本），运行中不提供修改入口；倍速只在设置里调，运行态只读展示。每步等待 `delayBefore ÷ 倍速` 后再注入（2.0× 只把等待减半，手势时长不变），每步注入前重新检查无障碍，失败/取消自动暂停并提示。

**回放引擎 `RecorderPlaybackEngine`**：逐步调度（`handler`），`generation` 守卫丢弃过期回调，`inFlight` 防止同一步重复排程，`injectionGuard` 在注入前给悬浮窗留一帧让 `NOT_TOUCHABLE` 生效；暂停/停止只取消后续调度，已在系统里的手势无法撤回。只调用 `dispatchInjectedGesture`，不接触另外两个模块的任何代码。

**脚本与现场**：`RecorderScript.kt` 里 `RecorderStep` / `RecorderScript`（带 `version`、屏幕尺寸与方向、`repeatCount`）由 `RecorderStore` 存 `recorder_config.xml`（`scripts` / `speed` / `window_alpha`）；`RecorderDraft` 每步落盘 `filesDir/recorder/draft.json`；`RecorderPlaybackSession` 存 `filesDir/recorder/session.json`，两者都先写 `.tmp` 再 `Os.rename` 原子替换。切模块或服务重启后只恢复为**暂停态**，绝不自动注入；保存脚本时同步清理同一脚本 id 的草稿。

## 4. 依赖方向

```
MainActivity
   │  引用（启停/权限/模式）
   ▼
feature.main   feature.clicker   feature.quickclick   feature.recorder
   │                │                    │                  │
   └────────────────┴────────────────────┴──────────────────┘
                       ▼     （仅向下依赖）
  core.accessibility   core.ui   core.components   core.theme   core.mode
```

- `feature/*` 之间互不引用（`clicker`、`quickclick`、`recorder` 三者完全独立，脚本、存储与运行状态不共享）。
- `core/*` 不引用任何 `feature/*`。

## 5. 关键约束与注意事项

- **无障碍服务已移至 `core.accessibility`**，其 ComponentName 变为 `com.hpu.autoclicker/.core.accessibility.AutoClickerAccessibilityService`。重构后需**在系统设置中关闭再重新开启该无障碍服务**，否则旧入口失效、三个模块的点击/回放均无效。
- **AndroidManifest** 中四个组件的 `android:name` 已随包更新（`.feature.clicker.FloatingWindowService`、`.feature.quickclick.QuickClickService`、`.feature.recorder.RecorderService`、`.core.accessibility.AutoClickerAccessibilityService`）；`MainActivity` 仍在根包。
- **前台服务类型权限（Android 14+ 硬性要求）**：三个悬浮窗服务声明 `android:foregroundServiceType="dataSync"`，manifest **必须**同时声明 `android.permission.FOREGROUND_SERVICE_DATA_SYNC`，否则 `startForeground()` 抛 `SecurityException` → 「Unable to create service」闪退。改动服务类型时，类型字符串与类型权限要成对修改，且不再使用 `tools:ignore="ForegroundServicePermission"` 掩盖问题。
  - `POST_NOTIFICATIONS`（Android 13+）由 `MainActivity` 运行时申请，拒绝授权不阻断悬浮窗启动，仅通知栏入口不可见。
  - Android 15+ 的 `dataSync` 有 6 小时 / 24 小时上限，系统回调 `Service.onTimeout(startId, fgsType)`，三个服务均已覆写并 `stopSelf(startId)` 优雅停止（不可不处理，否则系统以 `RemoteServiceException` 结束进程）。若需无限时长，可评估 `specialUse` 类型（需额外 `FOREGROUND_SERVICE_SPECIAL_USE` 权限与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性，并受应用商店政策约束）。
- **Service 内弹框**：`TYPE_APPLICATION_OVERLAY` 弹框须 `clearFlags(FLAG_ALT_FOCUSABLE_IM)` 才能弹软键盘；承载 `ComposeView` 的弹框须在 `show()` 前把 `ServiceComposeHost` 挂到窗口 DecorView。**所有 Service 内弹框一律走 `ServiceDialogs.show()`**，不要自己 new `AlertDialog`——统一宿主负责透明窗底、overlay 窗口类型、去平台双层框与宽度。
- **`ClickPoint`** 作为数据类内联在 `feature/clicker/FloatingWindowService.kt` 末尾；连点器的持久化模型已拆到 `feature/clicker/ClickerScript.kt`（`ClickerPointData` / `ClickerScript` / `ClickerScriptStore`）；**`QuickScript`/`QuickClickStore`** 内联在 `feature/quickclick/QuickClickConfig.kt`；`QuickClickConfig` 现同时承载识别字段、动作字段（`actionType` / `actionParams` 内部的间隔、时长、方向、距离）与随机偏移（`randomOffsetEnabled` / `randomOffsetRadius`），旧脚本在 `loadScripts` 中按缺少 `actionType` 判据补齐并回写一次（幂等）。
- **UI 硬约束（美化时也不可破坏）**：
  1. overlay 悬浮窗内 Compose 输入框收不到软键盘——`AppTextField` 内核必须保持原生 `EditText`；`ServiceDialogs.show()` 已在 `show()` 后统一 `clearFlags(FLAG_ALT_FOCUSABLE_IM)`，手工搭建的弹框（`showPointSettingsDialog`、`RegionRatioBar` 自定义比例）必须自行清除。
  2. 悬浮窗按钮手势统一在 `RawTouch` 单一链路（`pointerInteropFilter`），禁止混用 `clickable`；三个悬浮窗的按钮一律用 `FloatingSquareButton`，只换图标，不各写一套。脚本列表里的「点击加载 / 改名 / 删除」是弹框内 Compose 内容（非悬浮窗），可正常使用 `Modifier.clickable`。
  3. 悬浮窗内容的几何尺寸（40dp 按钮、8dp 根内边距等）与 Service 的窗口高度估算、拖动排序行高换算硬耦合，改前需同步 Service 侧数学。连点器运行态只渲染主按钮 + 停止按钮，`isTouchOnAllowedButton` 只放行主按钮与停止按钮记录的位置（`mainBtnWindowRect` / `stopBtnWindowRect`），新增按钮时必须同步这三处。
- **弹框一致性**：全项目弹框统一套 `AppDialogPanel` 外框，标题/分隔线/可滚动正文/按钮区由组件统一排版；按钮行统一用 `AppDialogActions`（取消在左、主操作在右）；危险操作主按钮用 `AppButtonVariant.Danger`。新增弹框不得自绘外框或使用平台默认面板。
- **全面屏（edge-to-edge）适配**：`targetSdk 36` 下系统已强制窗口铺满到状态栏/导航栏/刘海区域，**页面必须自行消费系统栏 inset**，否则状态栏会压住内容。规则：
  1. Activity 侧在 `onCreate` 调 `enableEdgeToEdge()`（内部会 `WindowCompat.setDecorFitsSystemWindows(window, false)`），Compose 页面按 `WindowInsets.safeDrawing` 加内边距避让，内边距加在滚动容器**之外**（内容不会滚进系统栏区域）。
  2. 铺满整屏的原生浮层与透明 Activity（截图框选页 / 相册裁剪页）没有 Compose inset，底部操作栏统一走 `core/ui/SystemBarInsets.kt` 的 `liftBottomBarAboveNavigationBar()`：判据是「操作栏底边是否触到屏幕底边」，触到才按导航栏高度整体上移，窗口本身已避让时不重复留白。
  3. 新增整屏页面或浮层时不要硬编码状态栏高度，一律走上述两处入口。
- **样式一致性**：页面禁止硬编码颜色/字号/圆角/间距，一律取 `MaterialTheme.colorScheme` / `MaterialTheme.typography` / `MaterialTheme.shapes` / `Dimens`；布局间距优先取 `Dimens` 中的**语义间距**（sectionGap / blockGap / fieldGap / screenVMargin）而非裸数值；例外仅悬浮窗内容的几何 dp（见上）。
- **图像识别（代点器）**：依赖无障碍 `takeScreenshot`（**仅 Android 11 / API 30+**，低版本入口置灰提示），无障碍配置必须声明 `android:canTakeScreenshot="true"`，否则系统直接拒绝截图。截图前 `hideSelfForScreenshot()` + `hideConfigForCapture()` 隐藏悬浮窗与配置弹框并延迟约 150ms，两者都置 `FLAG_NOT_TOUCHABLE`（弹框另加 `FLAG_NOT_FOCUSABLE`），避免不可见窗口仍吞触摸或抢相册焦点；框选浮层关闭（确定 / 系统返回键取消）后才恢复弹框与悬浮窗。「从相册选」同样先隐藏悬浮窗与弹框再拉起 `ImagePickActivity`，取消、返回、选图完成都经 `pendingResult`（含 `onDestroy` 兜底）恢复。模板匹配在 `matchExecutor` 单线程后台执行（不能放主线程，全屏 NCC 会卡 UI），并用 `matchInProgress` 防止并发截图；模板图与匹配参数存 `QuickClickConfig`，图片文件由 `ImageStore` 管 `filesDir/images/`，删脚本/删按钮/换图/取消编辑均需清理旧文件。`ImagePickActivity` 用 `ComponentActivity`（框架 Activity 无 `registerForActivityResult`），其在 `Button.apply{}` 内引用宿主须用 `this@ImagePickActivity`；`ScreenshotResult`/`TakeScreenshotCallback` 是 `AccessibilityService` 嵌套类，不可 import。
- **裁剪比例（代点器图像识别）**：`RegionRatioBar` 由截图浮层与相册裁剪页共用，提供 自由 / 1:1 / 4:3 / 3:4 / 16:9 / 9:16 / 自定义 W:H；`RegionSelectView.setAspectRatio(ratio)` 中的比例是**图像像素坐标的宽/高**，切换比例时保持选框面积与中心不变，锁定后 `applyScale` 只等比改变尺寸、拖动只平移。自定义比例弹框在 `Service` 上下文（截图浮层）中需显式 `TYPE_APPLICATION_OVERLAY` 并清除 `FLAG_ALT_FOCUSABLE_IM`，在 `Activity` 上下文（相册裁剪）中走普通弹框；`RegionRatioBar` 内部按 `context is Activity` 自动分流。选中比例不会写入 `QuickClickConfig`，裁剪结果 PNG 本身即为目标比例。
- **坐标定位（代点器）**：`CoordinatePickOverlay` 是独立的 `TYPE_APPLICATION_OVERLAY` 全屏浮层，**不加** `FLAG_NOT_TOUCHABLE`，触摸由浮层消费，因此点选坐标不会穿透到下层应用；窗口保持可聚焦并在根视图 `dispatchKeyEvent` 拦截 `KEYCODE_BACK`，返回键关闭浮层并恢复配置弹框（无取消按钮）。坐标取 `MotionEvent.rawX/rawY` 屏幕绝对坐标，与 `dispatchGesture` 坐标系一致。拾取期间需隐藏悬浮窗和配置弹框（`decorView.visibility = INVISIBLE` + `FLAG_NOT_TOUCHABLE`），回调后再恢复。
- **手势注入（代点器）**：识别链路（文本 / 图像 / 坐标）只负责产出坐标，三种识别最终都走 `QuickClickService.performActionAt()`——先 `applyRandomOffset()` 叠加一次随机偏移（每次执行只随机一次；双击两次触摸共用同一坐标，滑动只偏移起点），再 `buildActionGesture()` 构造 `GestureDescription` 交给 `AccessibilityService.dispatchQuickGesture()`：点击/双击用单点 `Path`、按压时长固定 50ms，双击在同一手势内放两条 stroke（startTime 0 与 interval），长按按压时长即动作时长，滑动用 `moveTo`→`lineTo` 直线。所有坐标（含滑动终点）都夹到屏幕内并留 1dp 边距，避免越界导致 `dispatchGesture` 失败；提交失败（服务断连）要提示检查无障碍并置灰主按钮，`onCancelled` 也必须 Toast，不能静默吞掉。新增动作类型只需扩展 `buildActionGesture` 与配置弹框，不要改识别链路。

- **录点器（录制 / 回放）**：
  1. **完全独立**：只复用 `FloatingSquareButton` / `RawTouch` / `ServiceDialogs` / `AppScriptList` 与主题 token；脚本存 `recorder_config.xml`，草稿存 `filesDir/recorder/draft.json`，现场存 `session.json`。不得读写 `clicker_script_config.xml` / `quick_click_config.xml`，也不得调用连点器 / 代点器的脚本或规则。
  2. 捕获层 `RecorderCaptureOverlay` 是 `TYPE_APPLICATION_OVERLAY` 全屏透明窗口（`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`），触摸全部由本层消费。暂停 / 注入期间 `setTouchable(false)` 必须**同时**置 `View.suppressInput = true` 与 `FLAG_NOT_TOUCHABLE`：窗口标志约一帧后才生效，只改标志会把回显手势录成新步骤；恢复时 `resetTimeline()` 把时间线移到「现在」，暂停时长不计入 `delayBefore`。
  3. 判定规则：位移未超 `touchSlop` 且按压 < `ViewConfiguration.getLongPressTimeout()` → `CLICK`；达到长按阈值 → `LONG_PRESS`（记录真实按压时长）；超过 `touchSlop` → `SWIPE`（起终点 + 总时长）。`isSystemArea()` 内（状态栏 / 底部导航栏）的触摸不记录并 toast 提示，不能静默丢弃；达到 `MAX_RECORD_STEPS` 自动暂停并提示。
  4. 回放节奏唯一来源是 `RecorderStep.delayBefore`（第一步固定为 0）：等待 `delayBefore ÷ 倍速` → 重新检查无障碍 → 注入。倍速只作用于等待，不改手势时长；`buildRecorderGesture` 把 CLICK 按压夹在 50~600ms（避免回放时被当成长按），LONG_PRESS / SWIPE 夹在 50ms~10s。
  5. 引擎守卫：`generation` 丢弃过期回调、`inFlight` 防同一步重入、`injectionGuard` 在注入前让悬浮窗 `NOT_TOUCHABLE` 生效；`dispatchInjectedGesture` 返回失败或 `onCancelled` 必须 `pauseWithError()` 并 toast，禁止静默跳过。暂停 / 停止只能阻止后续步骤，系统里已提交的手势无法撤回。
  6. 重复次数是**脚本属性**（`RecorderScript.repeatCount`），只在点「启动」前的「开始回放」确认框里配置，确认后立即写回脚本；运行面板不显示也不允许修改，不得放进悬浮窗全局设置。暂停在「当前步结束之后」生效，等待中的下一步立即取消；停止复位到「待运行」并留在运行态；切模块 / 服务重启只恢复为暂停态，绝不自动注入。
  7. 草稿每步覆盖写（`RecorderStateStore` 先写 `.tmp` 再 `Os.rename` 原子替换）；保存脚本（结束录制 / 脚本管理 / 关闭弹框）时清理**同一脚本 id** 的草稿，「不保存关闭」「丢弃草稿」也显式删除。重新录制、丢弃录制 / 草稿、删除脚本、关闭未保存步骤均二次确认；空脚本的「运行」置灰，引擎侧再兜底「脚本没有可回放的步骤」。

## 6. 如何新增一个功能模块

1. 在 `feature/` 下新建 `feature/<name>/`，包含该功能的 `XxxService.kt` + `XxxContent.kt`（如需要）。
2. 复用 `core.*`（无障碍、触摸判定、宿主、主题）；不要在 `feature` 间互相引用。
3. 在 `AndroidManifest.xml` 注册服务（用新全限定名），并声明与之匹配的 `foregroundServiceType` 与类型权限。
4. 在 `MainActivity` / `AppMode` 增加入口与模式，并驱动对应 `Screen`。
5. 新模块的脚本与设置一律新建自己的 SharedPreferences 与 `filesDir` 子目录，**不要复用**其它模块的键或文件。
