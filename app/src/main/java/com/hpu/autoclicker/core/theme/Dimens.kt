package com.hpu.autoclicker.core.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距 / 尺寸 token —— 页面所有 margin/padding/尺寸只能从这里取值，禁止 13/17 之类随意值。
 *
 * 间距阶梯（4 的倍数）：4 / 8 / 12 / 16 / 24 / 32。
 * 触控与组件尺寸遵循 Material 最小 48dp 触控目标要求（工具类尤其重要）。
 *
 * 语义别名按「层级」划分，页面按语义取值而不是随手挑一个数字：
 * - 区块之间（sectionGap）：大段落分隔，24dp
 * - 卡片/组内元素之间（blockGap）：12dp
 * - 标签与输入框之间（fieldGap）：8dp
 */
object Dimens {

    /** 间距阶梯 */
    val space4: Dp = 4.dp
    val space8: Dp = 8.dp
    val space12: Dp = 12.dp
    val space16: Dp = 16.dp
    val space24: Dp = 24.dp
    val space32: Dp = 32.dp

    /** 常用语义间距别名 */
    val gapXs: Dp get() = space4
    val gapSm: Dp get() = space8
    val gapMd: Dp get() = space16
    val gapLg: Dp get() = space24
    val screenHMargin: Dp get() = space16   // 页面左右安全边距
    val screenVMargin: Dp get() = space24   // 页面上下留白
    val cardPadding: Dp get() = space16     // 卡片内边距
    val cardPaddingLg: Dp get() = space24   // 表单组 / 大卡片内边距
    val dialogHPadding: Dp get() = space24  // 弹窗左右留白
    val dialogVPadding: Dp get() = space16  // 弹窗上下留白

    /** 层级化间距（页面分段时按语义取值） */
    val sectionGap: Dp get() = space24      // 区块之间
    val blockGap: Dp get() = space12        // 卡片内分组之间
    val fieldGap: Dp get() = space8         // 标签与输入控件之间
    val listItemGap: Dp get() = space12     // 列表项之间

    /** 控件尺寸 */
    val touchMin: Dp = 48.dp        // 最小触控目标
    val buttonHeight: Dp = 48.dp
    val buttonHeightSm: Dp = 40.dp
    val buttonHPadding: Dp = 20.dp // 按钮左右内边距
    val fieldHeight: Dp = 52.dp     // 输入框建议高度
    val listItemMinHeight: Dp = 56.dp
    val divider: Dp = 1.dp
    val statusDot: Dp = 8.dp        // 状态圆点直径
    val badgeHPadding: Dp = 8.dp   // 徽标左右内边距
    val badgeVPadding: Dp = 4.dp   // 徽标上下内边距

    /** 图标尺寸 */
    val iconSm: Dp = 16.dp
    val iconMd: Dp = 24.dp
    val iconLg: Dp = 40.dp

    /** 描边宽度 */
    val strokeThin: Dp = 1.dp
    val strokeFocused: Dp = 1.5.dp

    /** 滑块 */
    val sliderTrackHeight: Dp = 8.dp      // 胶囊轨道高度
    val sliderThumbSize: Dp = 24.dp      // 圆形滑钮直径
    val sliderThumbStroke: Dp = 2.5.dp   // 滑钮描边宽度
    val sliderThumbShadowIdle: Dp = 3.dp // 未拖动时投影
    val sliderThumbShadowActive: Dp = 8.dp // 拖动时投影

    /** 动效时长（ms），统一 150–300ms */
    object Motion {
        const val durationShort = 150
        const val durationMedium = 220
        const val durationLong = 300
    }
}
