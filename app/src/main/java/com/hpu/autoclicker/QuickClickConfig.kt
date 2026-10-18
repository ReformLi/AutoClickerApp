package com.hpu.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快捷点击子按钮配置
 * Phase 1 仅文本匹配生效；图像识别 / 坐标点击为预留字段
 */
data class QuickClickConfig(
    val name: String,                    // 按钮名称
    val type: String = TYPE_TEXT,        // 动作类型：text / image / coord
    val text: String = "",               // 文本匹配：目标文字
    val x: Float = 0f,                   // 坐标点击：x（预留）
    val y: Float = 0f,                   // 坐标点击：y（预留）
    val autoCollapse: Boolean = false    // 点击后是否自动收起悬浮窗
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("text", text)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("autoCollapse", autoCollapse)
    }

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_COORD = "coord"

        fun fromJson(o: JSONObject): QuickClickConfig = QuickClickConfig(
            name = o.optString("name", "按钮"),
            type = o.optString("type", TYPE_TEXT),
            text = o.optString("text", ""),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            autoCollapse = o.optBoolean("autoCollapse", false)
        )
    }
}

/**
 * 脚本：一组命名的快捷按钮配置
 */
data class QuickScript(
    val name: String,
    val configs: List<QuickClickConfig>
)

/**
 * 脚本持久化：SharedPreferences 存 JSON 数组。
 * 悬浮框上的按钮列表本身不自动持久化（启动为空白编辑态），
 * 只有通过「设置 → 另存为」命名的脚本才会被保存并可在下次启动后加载。
 */
object QuickClickStore {

    private const val PREFS = "quick_click_config"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_CLICK_DURATION = "click_duration"   // 手势点击时长 ms
    private const val KEY_DEBOUNCE = "debounce"               // 防抖间隔 ms

    /** 读取全部已保存脚本 */
    fun loadScripts(context: Context): MutableList<QuickScript> {
        val list = mutableListOf<QuickScript>()
        try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SCRIPTS, null) ?: return list
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cfgArr = o.optJSONArray("configs") ?: JSONArray()
                val configs = mutableListOf<QuickClickConfig>()
                for (j in 0 until cfgArr.length()) {
                    configs.add(QuickClickConfig.fromJson(cfgArr.getJSONObject(j)))
                }
                list.add(QuickScript(o.optString("name", "脚本"), configs))
            }
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "脚本读取失败", e)
        }
        return list
    }

    private fun persist(context: Context, scripts: List<QuickScript>) {
        try {
            val arr = JSONArray()
            scripts.forEach { s ->
                val o = JSONObject().apply {
                    put("name", s.name)
                    val cfgArr = JSONArray()
                    s.configs.forEach { cfgArr.put(it.toJson()) }
                    put("configs", cfgArr)
                }
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SCRIPTS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("QuickClick", "脚本保存失败", e)
        }
    }

    /** 另存为：脚本名已存在则覆盖 */
    fun saveScript(context: Context, name: String, configs: List<QuickClickConfig>) {
        val scripts = loadScripts(context)
        val idx = scripts.indexOfFirst { it.name == name }
        val script = QuickScript(name, configs.toList())
        if (idx >= 0) scripts[idx] = script else scripts.add(script)
        persist(context, scripts)
    }

    fun deleteScript(context: Context, name: String) {
        val scripts = loadScripts(context)
        scripts.removeAll { it.name == name }
        persist(context, scripts)
    }

    /** 重命名脚本；新名已被占用时返回 false */
    fun renameScript(context: Context, oldName: String, newName: String): Boolean {
        val scripts = loadScripts(context)
        if (scripts.any { it.name == newName && it.name != oldName }) return false
        val idx = scripts.indexOfFirst { it.name == oldName }
        if (idx < 0) return false
        scripts[idx] = scripts[idx].copy(name = newName)
        persist(context, scripts)
        return true
    }

    /** 手势点击时长，默认 80ms */
    fun getClickDuration(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLICK_DURATION, 80)
    }

    /** 防抖间隔，默认 500ms */
    fun getDebounce(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DEBOUNCE, 500L)
    }
}
