package com.hpu.autoclicker.feature.recorder

import android.content.Context
import android.system.Os
import com.hpu.autoclicker.core.util.ScriptNaming
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 录点器脚本模型 —— 与连点器脚本、代点器按钮完全独立。
 *
 * 存储分三处，互不读写：
 * - `recorder_config.xml`：已保存脚本、回放速度、悬浮窗透明度
 * - `filesDir/recorder/draft.json`：录制草稿（每录一步落盘，服务被杀/切模块后可恢复）
 * - `filesDir/recorder/session.json`：回放现场（切模块时停注入但保留进度）
 *
 * 屏幕坐标一律是屏幕绝对像素，与 dispatchGesture 的坐标系一致。
 */

enum class RecorderStepType(val json: String) {
    CLICK("CLICK"),
    LONG_PRESS("LONG_PRESS"),
    SWIPE("SWIPE"),
    WAIT("WAIT");

    companion object {
        fun from(raw: String): RecorderStepType? = entries.firstOrNull { it.json == raw }
    }
}

/**
 * 一步操作。字段按类型取用：
 * - CLICK / LONG_PRESS：x、y、duration（实际按压时长）
 * - SWIPE：x1、y1、x2、y2、duration（手指从按下到抬起的总时长）
 * - WAIT：duration（等待时长；录制不产生，预留给后续导入/手动编辑）
 *
 * delayBefore 是「上一步抬起 → 本步按下」的间隔，是回放节奏的唯一来源。
 */
data class RecorderStep(
    val type: RecorderStepType,
    val x: Int = 0,
    val y: Int = 0,
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val duration: Long = 0L,
    val delayBefore: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.json)
        when (type) {
            RecorderStepType.CLICK, RecorderStepType.LONG_PRESS -> {
                put("x", x)
                put("y", y)
                put("duration", duration)
            }

            RecorderStepType.SWIPE -> {
                put("x1", x1)
                put("y1", y1)
                put("x2", x2)
                put("y2", y2)
                put("duration", duration)
            }

            RecorderStepType.WAIT -> put("duration", duration)
        }
        put("delayBefore", delayBefore)
    }

    companion object {
        const val MIN_GESTURE_DURATION = 50L
        const val MAX_GESTURE_DURATION = 10_000L
        const val MAX_WAIT_DURATION = 600_000L
        const val MAX_DELAY_BEFORE = 600_000L

        /**
         * 解析并逐字段校准。无法修复的步骤返回 null（由上层跳过并计数）。
         * 坐标按录制屏幕尺寸收敛，避免导入的脏数据把点击甩到屏幕外。
         */
        fun fromJson(o: JSONObject, screenW: Int, screenH: Int): RecorderStep? {
            val type = RecorderStepType.from(o.optString("type", "")) ?: return null
            val delay = o.optLong("delayBefore", 0L).coerceIn(0L, MAX_DELAY_BEFORE)
            fun coord(key: String, max: Int): Int? {
                val raw = o.optDouble(key, Double.NaN)
                if (!raw.isFinite()) return null
                return raw.toInt().coerceIn(0, max.coerceAtLeast(1) - 1)
            }

            return when (type) {
                RecorderStepType.CLICK, RecorderStepType.LONG_PRESS -> {
                    val x = coord("x", screenW) ?: return null
                    val y = coord("y", screenH) ?: return null
                    val duration = o.optLong("duration", if (type == RecorderStepType.LONG_PRESS) 800L else 80L)
                        .coerceIn(MIN_GESTURE_DURATION, MAX_GESTURE_DURATION)
                    RecorderStep(type = type, x = x, y = y, duration = duration, delayBefore = delay)
                }

                RecorderStepType.SWIPE -> {
                    val x1 = coord("x1", screenW) ?: return null
                    val y1 = coord("y1", screenH) ?: return null
                    val x2 = coord("x2", screenW) ?: return null
                    val y2 = coord("y2", screenH) ?: return null
                    val duration = o.optLong("duration", 300L)
                        .coerceIn(MIN_GESTURE_DURATION, MAX_GESTURE_DURATION)
                    RecorderStep(
                        type = type,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        duration = duration,
                        delayBefore = delay,
                    )
                }

                RecorderStepType.WAIT -> {
                    val duration = o.optLong("duration", 0L).coerceIn(0L, MAX_WAIT_DURATION)
                    RecorderStep(type = type, duration = duration, delayBefore = delay)
                }
            }
        }
    }
}

/** 一个可命名、可重复回放的录点脚本 */
data class RecorderScript(
    val id: String,
    val name: String,
    val steps: List<RecorderStep>,
    val repeatCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val screenW: Int = 0,
    val screenH: Int = 0,
    val orientation: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", SCHEMA_VERSION)
        put("id", id)
        put("name", name)
        put("repeatCount", repeatCount)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("screen", JSONObject().apply {
            put("w", screenW)
            put("h", screenH)
            put("orientation", orientation)
        })
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIN_REPEAT = 1
        const val MAX_REPEAT = 999
        const val MAX_SCRIPT_STEPS = 2000
        const val MAX_RECORD_STEPS = 500
        const val MAX_NAME_LENGTH = ScriptNaming.MAX_NAME_LENGTH

        fun newEmpty(name: String, screenW: Int, screenH: Int, orientation: Int): RecorderScript =
            RecorderScript(
                id = UUID.randomUUID().toString(),
                name = name,
                steps = emptyList(),
                screenW = screenW,
                screenH = screenH,
                orientation = orientation,
            )

        /** 解析脚本；结构不合法或版本过新返回 null。空步骤脚本默认视为非法。 */
        fun fromJson(o: JSONObject, allowEmpty: Boolean = false): RecorderScript? {
            val version = o.optInt("version", SCHEMA_VERSION)
            if (version > SCHEMA_VERSION) return null

            val screen = o.optJSONObject("screen")
            val screenW = screen?.optInt("w", 0) ?: 0
            val screenH = screen?.optInt("h", 0) ?: 0
            val orientation = screen?.optInt("orientation", 0) ?: 0

            val steps = mutableListOf<RecorderStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                if (steps.size >= MAX_SCRIPT_STEPS) break
                val item = arr.optJSONObject(i) ?: continue
                RecorderStep.fromJson(item, screenW, screenH)?.let { steps.add(it) }
            }
            if (steps.isEmpty() && !allowEmpty) return null

            val name = o.optString("name", "").trim().ifBlank { "脚本" }.take(MAX_NAME_LENGTH)
            val id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() }
            return RecorderScript(
                id = id,
                name = name,
                steps = steps,
                repeatCount = o.optInt("repeatCount", 1).coerceIn(MIN_REPEAT, MAX_REPEAT),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                screenW = screenW.coerceAtLeast(0),
                screenH = screenH.coerceAtLeast(0),
                orientation = orientation,
            )
        }
    }
}

/** 已保存脚本 + 回放速度 + 悬浮窗透明度（独立于另外两个模块） */
object RecorderStore {

    private const val PREFS = "recorder_config"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_SCRIPTS_BACKUP = "scripts_unreadable_backup"
    private const val KEY_SPEED = "speed"
    private const val KEY_WINDOW_ALPHA = "window_alpha"
    private const val TAG = "RecorderStore"

    /** 不透明度下限：留一点可见度，避免调成全透明后找不到悬浮窗 */
    const val MIN_WINDOW_ALPHA = 0.2f

    /** 回放速度档位：数值 = 倍速，等待时间按倍速相除 */
    val SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    /**
     * 一次读取的结果。
     * @param skipped 没能读出来的条目数（结构损坏或 schema 版本比当前 App 还新）；
     *                >0 说明这份列表不完整，覆盖写前必须先留档，否则会把没读到的脚本永久删掉。
     */
    private class ScriptRead(val scripts: MutableList<RecorderScript>, val raw: String?, val skipped: Int) {
        val lossy: Boolean get() = skipped > 0
    }

    fun loadScripts(context: Context): MutableList<RecorderScript> = readScripts(context).scripts

    private fun readScripts(context: Context): ScriptRead {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCRIPTS, null) ?: return ScriptRead(mutableListOf(), null, 0)
        val list = mutableListOf<RecorderScript>()
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
            // 允许 0 步脚本原样读回：结构合法，只是不可运行，不能算损坏条目
            val script = RecorderScript.fromJson(o, allowEmpty = true)
            if (script == null) skipped++ else list.add(script)
        }
        return ScriptRead(list, raw, skipped)
    }

    /** 坏条目读不出来时，先把磁盘上的原始串留一份档，覆盖写才不至于把没读到的脚本永久删掉 */
    private fun keepBackupIfLossy(context: Context, read: ScriptRead) {
        if (!read.lossy || read.raw == null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(KEY_SCRIPTS_BACKUP)) return
        p.edit().putString(KEY_SCRIPTS_BACKUP, read.raw).apply()
        android.util.Log.e(TAG, "脚本数据有 ${read.skipped} 条未能读出，覆盖写前已留原始备份")
        android.widget.Toast.makeText(
            context, "脚本文档有损坏条目，本次覆盖写前已自动备份原始数据", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /** 按 id 覆盖保存，不存在则新增；返回补齐 updatedAt 后的脚本 */
    fun saveScript(context: Context, script: RecorderScript): RecorderScript {
        val normalized = script.copy(
            name = script.name.trim().ifBlank { "脚本" }.take(RecorderScript.MAX_NAME_LENGTH),
            repeatCount = script.repeatCount.coerceIn(
                RecorderScript.MIN_REPEAT,
                RecorderScript.MAX_REPEAT,
            ),
            steps = script.steps.take(RecorderScript.MAX_SCRIPT_STEPS),
            updatedAt = System.currentTimeMillis(),
        )
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        val index = scripts.indexOfFirst { it.id == normalized.id }
        if (index >= 0) scripts[index] = normalized else scripts.add(normalized)
        persist(context, scripts)
        return normalized
    }

    fun deleteScript(context: Context, id: String) {
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        scripts.removeAll { it.id == id }
        persist(context, scripts)
        // 草稿与回放现场里若正是这个脚本，一并清掉：
        // session 内嵌整个脚本副本，不清会把已删脚本在下次启动时「复活」成当前脚本
        RecorderStateStore.loadDraft(context)?.let { if (it.id == id) RecorderStateStore.clearDraft(context) }
        RecorderStateStore.loadSession(context)
            ?.let { if (it.script.id == id) RecorderStateStore.clearSession(context) }
    }

    /** 不重名的默认脚本名：脚本 1、脚本 2 … */
    fun defaultScriptName(context: Context): String {
        val names = loadScripts(context).map { it.name }.toSet()
        var index = names.size + 1
        while (names.contains("脚本 $index")) index++
        return "脚本 $index"
    }

    private fun persist(context: Context, scripts: List<RecorderScript>) {
        try {
            val arr = JSONArray().apply { scripts.forEach { put(it.toJson()) } }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SCRIPTS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "脚本保存失败", e)
        }
    }

    fun getSpeed(context: Context): Float {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f)
        return SPEEDS.minByOrNull { kotlin.math.abs(it - stored) } ?: 1.0f
    }

    fun setSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun getWindowAlpha(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_WINDOW_ALPHA, 1f)
            .coerceIn(MIN_WINDOW_ALPHA, 1f)

    fun setWindowAlpha(context: Context, alpha: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_WINDOW_ALPHA, alpha.coerceIn(MIN_WINDOW_ALPHA, 1f))
            .apply()
    }
}

/** 录制草稿：每录一步覆盖写一次，服务被杀或切模块后可恢复 */
data class RecorderDraft(
    val id: String,
    val name: String,
    val steps: List<RecorderStep>,
    val repeatCount: Int,
    val screenW: Int,
    val screenH: Int,
    val orientation: Int,
    val startedAt: Long,
    val updatedAt: Long,
) {
    fun toScript(): RecorderScript = RecorderScript(
        id = id,
        name = name,
        steps = steps,
        repeatCount = repeatCount,
        createdAt = startedAt,
        updatedAt = updatedAt,
        screenW = screenW,
        screenH = screenH,
        orientation = orientation,
    )

    companion object {
        fun from(script: RecorderScript, startedAt: Long): RecorderDraft = RecorderDraft(
            id = script.id,
            name = script.name,
            steps = script.steps,
            repeatCount = script.repeatCount,
            screenW = script.screenW,
            screenH = script.screenH,
            orientation = script.orientation,
            startedAt = startedAt,
            updatedAt = System.currentTimeMillis(),
        )

        fun fromJson(o: JSONObject): RecorderDraft? {
            if (o.optInt("version", 1) > RecorderScript.SCHEMA_VERSION) return null
            val screen = o.optJSONObject("screen")
            val screenW = screen?.optInt("w", 0) ?: 0
            val screenH = screen?.optInt("h", 0) ?: 0
            val orientation = screen?.optInt("orientation", 0) ?: 0
            val steps = mutableListOf<RecorderStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                RecorderStep.fromJson(item, screenW, screenH)?.let { steps.add(it) }
            }
            return RecorderDraft(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", "脚本").take(RecorderScript.MAX_NAME_LENGTH),
                steps = steps,
                repeatCount = o.optInt("repeatCount", 1)
                    .coerceIn(RecorderScript.MIN_REPEAT, RecorderScript.MAX_REPEAT),
                screenW = screenW.coerceAtLeast(0),
                screenH = screenH.coerceAtLeast(0),
                orientation = orientation,
                startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }

        fun toJson(draft: RecorderDraft): JSONObject = JSONObject().apply {
            put("version", RecorderScript.SCHEMA_VERSION)
            put("id", draft.id)
            put("name", draft.name)
            put("repeatCount", draft.repeatCount)
            put("startedAt", draft.startedAt)
            put("updatedAt", draft.updatedAt)
            put("screen", JSONObject().apply {
                put("w", draft.screenW)
                put("h", draft.screenH)
                put("orientation", draft.orientation)
            })
            put("steps", JSONArray().apply { draft.steps.forEach { put(it.toJson()) } })
        }
    }
}

/** 回放现场：切模块/服务被杀时保存「停在哪一步」，切回后不自动注入 */
data class RecorderPlaybackSession(
    val script: RecorderScript,
    val stepIndex: Int,
    val repeatIndex: Int,
    val speed: Float,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", RecorderScript.SCHEMA_VERSION)
        put("script", script.toJson())
        put("stepIndex", stepIndex)
        put("repeatIndex", repeatIndex)
        put("speed", speed.toDouble())
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): RecorderPlaybackSession? {
            if (o.optInt("version", 1) > RecorderScript.SCHEMA_VERSION) return null
            val scriptObj = o.optJSONObject("script") ?: return null
            val script = RecorderScript.fromJson(scriptObj) ?: return null
            return RecorderPlaybackSession(
                script = script,
                stepIndex = o.optInt("stepIndex", 0)
                    .coerceIn(0, (script.steps.size - 1).coerceAtLeast(0)),
                repeatIndex = o.optInt("repeatIndex", 0)
                    .coerceIn(0, RecorderScript.MAX_REPEAT - 1),
                speed = o.optDouble("speed", 1.0).toFloat()
                    .takeIf { it.isFinite() && it > 0f } ?: 1.0f,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

/**
 * 草稿 / 回放现场的原子读写。
 * 先写 `.tmp` 再 `Os.rename`（同目录 rename 是原子替换），避免半截 JSON 把现场写坏。
 */
object RecorderStateStore {

    private const val DIR = "recorder"
    private const val FILE_DRAFT = "draft.json"
    private const val FILE_SESSION = "session.json"
    private const val TAG = "RecorderState"

    fun saveDraft(context: Context, draft: RecorderDraft) {
        writeAtomic(file(context, FILE_DRAFT), RecorderDraft.toJson(draft).toString())
    }

    fun loadDraft(context: Context): RecorderDraft? {
        val raw = read(file(context, FILE_DRAFT)) ?: return null
        return try {
            RecorderDraft.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "草稿读取失败", e)
            null
        }
    }

    fun clearDraft(context: Context) {
        file(context, FILE_DRAFT).delete()
    }

    fun saveSession(context: Context, session: RecorderPlaybackSession) {
        writeAtomic(file(context, FILE_SESSION), session.toJson().toString())
    }

    fun loadSession(context: Context): RecorderPlaybackSession? {
        val raw = read(file(context, FILE_SESSION)) ?: return null
        return try {
            RecorderPlaybackSession.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "回放现场读取失败", e)
            null
        }
    }

    fun clearSession(context: Context) {
        file(context, FILE_SESSION).delete()
    }

    private fun file(context: Context, name: String): File =
        File(File(context.filesDir, DIR), name)

    private fun read(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: Exception) {
        android.util.Log.e(TAG, "文件读取失败 ${file.name}", e)
        null
    }

    private fun writeAtomic(file: File, text: String) {
        val dir = file.parentFile ?: return
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.e(TAG, "目录创建失败 ${dir.absolutePath}")
            return
        }
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeText(text)
            try {
                Os.rename(tmp.absolutePath, file.absolutePath)
            } catch (e: Exception) {
                // 个别文件系统不支持覆盖式 rename：退化为复制后替换
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "文件写入失败 ${file.name}", e)
        }
    }
}
