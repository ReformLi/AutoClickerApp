package com.hpu.autoclicker.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角 token —— 各容器类型一个固定值，全项目一致（越大越圆）。
 * - extraSmall(8dp)：chip / 小组件
 * - small(12dp)：按钮 / 输入框（沿用旧值，保证未迁移组件不变样）
 * - medium(16dp)：卡片 / 列表项 / Hero 卡片
 * - large(20dp)：弹窗 / 底部栏
 * - extraLarge(28dp)：全屏面板 / 抽屉
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
