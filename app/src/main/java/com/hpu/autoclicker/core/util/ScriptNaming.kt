package com.hpu.autoclicker.core.util

/**
 * 三个模块（连点器 / 代点器 / 录点器）脚本名称的统一规则。
 *
 * **Why:** 名称会写进脚本 JSON 并作为查找、重命名、去重的 key，
 * 三处各写一套限长会出现「同名脚本在 A 模块存得下、B 模块被截断」的分歧。
 */
object ScriptNaming {
    /** 脚本名最大字符数：输入框 maxLength 与存储截断都用它 */
    const val MAX_NAME_LENGTH = 16
}
