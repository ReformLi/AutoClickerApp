package com.hpu.autoclicker.feature.clicker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 连点器点位的可序列化快照（字段与原 click_points.json 完全一致，
 * 加载时的还原顺序也保持不变）。
 */
data class ClickerPointData(
    val id: Int,
    val x: Float,
    val y: Float,
    val type: String = "点击",
    val name: String = "点 $id",
    val delay: Long = 50,
    val touchDuration: Long = 150,
    val repeatCount: Int = 1,
    val randomDistance: Int = 5,
    val clickPosition: String = "中间",
    val pairId: Int = -1,
    val isStart: Boolean = false,
    val endX: Float = x,
    val endY: Float = y,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("type", type)
        put("name", name)
        put("delay", delay)
        put("touchDuration", touchDuration)
        put("repeatCount", repeatCount)
        put("randomDistance", randomDistance)
        put("clickPosition", clickPosition)
        put("pairId", pairId)
        put("isStart", isStart)
        put("endX", endX.toDouble())
        put("endY", endY.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): ClickerPointData {
            val id = o.optInt("id", 0)
            val x = o.optDouble("x", 0.0).toFloat()
            val y = o.optDouble("y", 0.0).toFloat()
            return ClickerPointData(
                id = id,
                x = x,
                y = y,
                type = o.optString("type", "点击"),
                name = o.optString("name", "点 $id"),
                delay = o.optLong("delay", 0),
                touchDuration = o.optLong("touchDuration", 300),
                repeatCount = o.optInt("repeatCount", 1),
                randomDistance = o.optInt("randomDistance", 0),
                clickPosition = o.optString("clickPosition", "中间"),
                pairId = o.optInt("pairId", -1),
                isStart = o.optBoolean("isStart", false),
                endX = o.optDouble("endX", x.toDouble()).toFloat(),
                endY = o.optDouble("endY", y.toDouble()).toFloat(),
            )
        }
    }
}

/** 连点器脚本：一组命名点位 */
data class ClickerScript(
    val name: String,
    val points: List<ClickerPointData>,
)

/**
 * 连点器脚本持久化：SharedPreferences 存 JSON 数组。
 * 与代点器完全独立（不同 prefs），透明度也各存各的，互不干扰。
 */
object ClickerScriptStore {

    private const val PREFS = "clicker_script_config"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_WINDOW_ALPHA = "window_alpha"
    private const val KEY_POINT_ALPHA = "point_alpha"
    private const val KEY_LEGACY_MIGRATED = "legacy_migrated"
    private const val LEGACY_SCRIPT_NAME = "默认脚本"

    /** 不透明度下限：留一点可见度，避免调成全透明后无法再找到悬浮窗 / 点位 */
    const val MIN_WINDOW_ALPHA = 0.2f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取全部已保存脚本（首次读取时把旧的 click_points.json 迁成一个脚本） */
    fun loadScripts(context: Context): MutableList<ClickerScript> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_LEGACY_MIGRATED, false)) migrateLegacy(context, p)
        return readScripts(p)
    }

    private fun readScripts(p: SharedPreferences): MutableList<ClickerScript> {
        val list = mutableListOf<ClickerScript>()
        try {
            val json = p.getString(KEY_SCRIPTS, null) ?: return list
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pointArr = o.optJSONArray("points") ?: JSONArray()
                val points = mutableListOf<ClickerPointData>()
                for (j in 0 until pointArr.length()) {
                    points.add(ClickerPointData.fromJson(pointArr.getJSONObject(j)))
                }
                list.add(ClickerScript(o.optString("name", "脚本"), points))
            }
        } catch (e: Exception) {
            android.util.Log.e("ClickerScript", "脚本读取失败", e)
        }
        return list
    }

    private fun persist(context: Context, scripts: List<ClickerScript>) {
        val arr = JSONArray()
        scripts.forEach { s ->
            val o = JSONObject().apply {
                put("name", s.name)
                val pointArr = JSONArray()
                s.points.forEach { pointArr.put(it.toJson()) }
                put("points", pointArr)
            }
            arr.put(o)
        }
        prefs(context).edit()
            .putString(KEY_SCRIPTS, arr.toString())
            .putBoolean(KEY_LEGACY_MIGRATED, true)
            .apply()
    }

    /**
     * 旧版只有一个 click_points.json。首次进入脚本管理时把它迁成名为「默认脚本」的脚本，
     * 之后即使把脚本全删掉也不会再次导入（迁移标记只置一次）。
     */
    private fun migrateLegacy(context: Context, p: SharedPreferences) {
        try {
            val scripts = readScripts(p)
            val file = File(context.filesDir, "click_points.json")
            if (file.exists() && scripts.none { it.name == LEGACY_SCRIPT_NAME }) {
                val arr = JSONArray(file.readText())
                val points = mutableListOf<ClickerPointData>()
                for (i in 0 until arr.length()) {
                    points.add(ClickerPointData.fromJson(arr.getJSONObject(i)))
                }
                if (points.isNotEmpty()) {
                    scripts.add(ClickerScript(LEGACY_SCRIPT_NAME, points))
                    persist(context, scripts)
                    return
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ClickerScript", "旧点位迁移失败", e)
        }
        p.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }

    /** 保存脚本：名称已存在则覆盖 */
    fun saveScript(context: Context, name: String, points: List<ClickerPointData>) {
        val scripts = loadScripts(context)
        val idx = scripts.indexOfFirst { it.name == name }
        val script = ClickerScript(name, points.toList())
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

    /** 悬浮窗不透明度，默认 1f（完全不透明） */
    fun getWindowAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_WINDOW_ALPHA, 1f)
            .coerceIn(MIN_WINDOW_ALPHA, 1f)
    }

    fun setWindowAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_WINDOW_ALPHA, alpha.coerceIn(MIN_WINDOW_ALPHA, 1f))
            .apply()
    }

    /** 点击 / 滑动点位不透明度，默认 1f（完全不透明） */
    fun getPointAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_POINT_ALPHA, 1f)
            .coerceIn(MIN_WINDOW_ALPHA, 1f)
    }

    fun setPointAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_POINT_ALPHA, alpha.coerceIn(MIN_WINDOW_ALPHA, 1f))
            .apply()
    }
}
