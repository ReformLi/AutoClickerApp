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
        /** 参数硬边界：弹框输入与读盘都按这套夹紧，避免 0 / 负数 / 荒谬值进入执行链路 */
        const val MIN_DELAY = 0L
        const val MAX_DELAY = 600_000L
        const val MIN_TOUCH_DURATION = 50L
        const val MAX_TOUCH_DURATION = 10_000L
        const val MIN_REPEAT = 1
        const val MAX_REPEAT = 999
        const val MIN_RANDOM_DISTANCE = 0
        const val MAX_RANDOM_DISTANCE = 200

        fun clampDelay(value: Long): Long = value.coerceIn(MIN_DELAY, MAX_DELAY)
        fun clampTouchDuration(value: Long): Long = value.coerceIn(MIN_TOUCH_DURATION, MAX_TOUCH_DURATION)
        fun clampRepeatCount(value: Int): Int = value.coerceIn(MIN_REPEAT, MAX_REPEAT)
        fun clampRandomDistance(value: Int): Int = value.coerceIn(MIN_RANDOM_DISTANCE, MAX_RANDOM_DISTANCE)

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
                delay = clampDelay(o.optLong("delay", 0)),
                touchDuration = clampTouchDuration(o.optLong("touchDuration", 300)),
                repeatCount = clampRepeatCount(o.optInt("repeatCount", 1)),
                randomDistance = clampRandomDistance(o.optInt("randomDistance", 0)),
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
    private const val KEY_SCRIPTS_BACKUP = "scripts_unreadable_backup"
    private const val KEY_WINDOW_ALPHA = "window_alpha"
    private const val KEY_POINT_ALPHA = "point_alpha"
    private const val KEY_LEGACY_MIGRATED = "legacy_migrated"
    private const val LEGACY_SCRIPT_NAME = "默认脚本"
    private const val TAG = "ClickerScript"

    /** 不透明度下限：留一点可见度，避免调成全透明后无法再找到悬浮窗 / 点位 */
    const val MIN_WINDOW_ALPHA = 0.2f

    /** 脚本数量与单个脚本点位数量上限：与录点器同为「防无限增长」的硬闸 */
    const val MAX_SCRIPTS = 50
    const val MAX_POINTS_PER_SCRIPT = 200

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 一次读取的结果。
     * @param skipped 没能读出来的条目数（结构损坏 / 类型不符）；>0 说明这份列表**不完整**，
     *                写回前必须先留档，否则整表覆盖会把没读到的脚本永久删掉。
     */
    private class ScriptRead(val scripts: MutableList<ClickerScript>, val raw: String?, val skipped: Int) {
        val lossy: Boolean get() = skipped > 0
    }

    /** 读取全部已保存脚本（首次读取时把旧的 click_points.json 迁成一个脚本） */
    fun loadScripts(context: Context): MutableList<ClickerScript> {
        ensureLegacyMigration(context)
        return readScripts(context).scripts
    }

    /** 迁移只做一次，纯读取本身不再触发，避免与 migrateLegacy 互相调用 */
    private fun ensureLegacyMigration(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_LEGACY_MIGRATED, false)) migrateLegacy(context, p)
    }

    private fun readScripts(context: Context): ScriptRead {
        val raw = prefs(context).getString(KEY_SCRIPTS, null)
            ?: return ScriptRead(mutableListOf(), null, 0)
        val list = mutableListOf<ClickerScript>()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "脚本整体解析失败", e)
            return ScriptRead(list, raw, 1)
        }
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            val pointArr = o.optJSONArray("points") ?: JSONArray()
            val points = mutableListOf<ClickerPointData>()
            for (j in 0 until pointArr.length()) {
                val pointObj = pointArr.optJSONObject(j)
                if (pointObj == null) {
                    skipped++
                    continue
                }
                runCatching { ClickerPointData.fromJson(pointObj) }
                    .onSuccess { points.add(it) }
                    .onFailure { skipped++ }
            }
            list.add(ClickerScript(o.optString("name", "脚本"), points))
        }
        return ScriptRead(list, raw, skipped)
    }

    /**
     * 读到过不完整列表时，先把磁盘上的原始串另存一份，再允许覆盖写。
     * 已留过档就不覆盖第一份，保证最坏情况下仍有一条恢复路径。
     */
    private fun keepBackupIfLossy(context: Context, read: ScriptRead) {
        if (!read.lossy || read.raw == null) return
        val p = prefs(context)
        if (p.contains(KEY_SCRIPTS_BACKUP)) return
        p.edit().putString(KEY_SCRIPTS_BACKUP, read.raw).apply()
        android.util.Log.e(TAG, "脚本数据有 ${read.skipped} 条未能读出，覆盖写前已留原始备份")
        android.widget.Toast.makeText(
            context, "脚本文档有损坏条目，本次覆盖写前已自动备份原始数据", android.widget.Toast.LENGTH_LONG
        ).show()
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
     * 迁移成功后把文件改名为 .migrated：既不再被当成待迁数据，又保留一份可人工取回的副本。
     */
    private fun migrateLegacy(context: Context, p: SharedPreferences) {
        val file = File(context.filesDir, "click_points.json")
        try {
            val scripts = readScripts(context).scripts
            if (file.exists() && scripts.none { it.name == LEGACY_SCRIPT_NAME }) {
                val arr = JSONArray(file.readText())
                val points = mutableListOf<ClickerPointData>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { points.add(ClickerPointData.fromJson(it)) }
                }
                if (points.isNotEmpty()) {
                    scripts.add(ClickerScript(LEGACY_SCRIPT_NAME, points))
                    persist(context, scripts)
                    file.renameTo(File(context.filesDir, "click_points.json.migrated"))
                    return
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "旧点位迁移失败", e)
        }
        p.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }

    /**
     * 保存脚本：名称已存在则覆盖。
     * 超出上限时拒绝写入并提示（不做静默截断，避免用户以为已保存）。
     */
    fun saveScript(context: Context, name: String, points: List<ClickerPointData>): Boolean {
        ensureLegacyMigration(context)
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        val idx = scripts.indexOfFirst { it.name == name }
        if (idx < 0 && scripts.size >= MAX_SCRIPTS) {
            android.widget.Toast.makeText(
                context, "脚本数量已达上限 $MAX_SCRIPTS，请先删除部分脚本", android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        if (points.size > MAX_POINTS_PER_SCRIPT) {
            android.widget.Toast.makeText(
                context, "单个脚本最多 $MAX_POINTS_PER_SCRIPT 个点位", android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        val script = ClickerScript(name, points.toList())
        if (idx >= 0) scripts[idx] = script else scripts.add(script)
        persist(context, scripts)
        return true
    }

    fun deleteScript(context: Context, name: String) {
        ensureLegacyMigration(context)
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        scripts.removeAll { it.name == name }
        persist(context, scripts)
    }

    /** 重命名脚本；新名已被占用时返回 false */
    fun renameScript(context: Context, oldName: String, newName: String): Boolean {
        ensureLegacyMigration(context)
        val read = readScripts(context)
        val scripts = read.scripts
        if (scripts.any { it.name == newName && it.name != oldName }) return false
        val idx = scripts.indexOfFirst { it.name == oldName }
        if (idx < 0) return false
        keepBackupIfLossy(context, read)
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
