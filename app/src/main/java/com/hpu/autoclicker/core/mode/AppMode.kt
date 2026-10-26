package com.hpu.autoclicker.core.mode

/**
 * 三个互相独立的功能模块。
 *
 * 持久化值保留旧版本的 `old` / `new`（连点器 / 代点器），
 * [fromPref] 负责把它们迁移到新枚举，已有用户的模式选择不会丢。
 */
enum class AppMode(val prefValue: String, val displayName: String, val description: String) {
    CLICKER("clicker", "连点器", "按点位循环连点"),
    QUICK_CLICK("quick_click", "代点器", "识别文字/图像/坐标后进行点击"),
    RECORDER("recorder", "录点器", "录制操作并在任意界面回放");

    companion object {
        fun fromPref(raw: String?): AppMode = when (raw) {
            "new", "quick_click" -> QUICK_CLICK
            "recorder" -> RECORDER
            else -> CLICKER
        }
    }
}
