package com.hpu.autoclicker.feature.quickclick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 快捷点击子按钮配置
 * 识别方式支持文本匹配 / 图像识别 / 坐标定位；
 * 拿到坐标后由动作类型决定怎么注入手势（点击 / 双击 / 长按 / 滑动）。
 */
data class QuickClickConfig(
    val name: String,                    // 按钮名称
    val type: String = TYPE_TEXT,        // 识别方式：text / image / coord
    val text: String = "",               // 文本匹配：目标文字
    val x: Float = 0f,                   // 坐标定位：x（屏幕像素）
    val y: Float = 0f,                   // 坐标定位：y（屏幕像素）
    // ===== 图像识别扩展字段（仅 TYPE_IMAGE 有意义）=====
    val imageFileName: String = "",      // 模板图文件名（相对 filesDir/images/）
    val imageThreshold: Float = 0.80f,   // 相似度阈值 [0,1]
    val imageScale: Int = 2,             // 降采样倍率 1~4
    val imageRegion: String = "full",    // 匹配区域：full / last（仅上次框选区域）
    val imageGrayMode: Boolean = true,   // 灰度匹配
    val imageRetryCount: Int = 1,        // 未找到重试次数
    val imageRetryDelay: Long = 300L,    // 重试间隔 ms
    val imageRegionRect: String = "",    // 「仅框选区域」用的屏幕矩形 "l,t,r,b"（截图捕获时记录）
    // ===== 动作扩展（仅描述「识别坐标之后如何执行」）=====
    val actionType: String = ACTION_CLICK,
    val doubleClickInterval: Long = 150L,     // 双击间隔 ms [100,300]
    val longPressDuration: Long = 800L,       // 长按时长 ms [200,10000]
    val swipeDirection: String = SWIPE_UP,    // 滑动方向 up/down/left/right
    val swipeDistance: Int = 300,             // 滑动距离 px [50, 屏幕短边一半]
    val swipeDuration: Long = 300L,           // 滑动时长 ms [100,2000]
    val randomOffsetEnabled: Boolean = true,  // 识别坐标随机偏移
    val randomOffsetRadius: Int = 5,          // 偏移半径 px [0,50]，0 等同关闭
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("text", text)
        put("x", x.toDouble())
        put("y", y.toDouble())
        if (type == TYPE_IMAGE) {
            put("imageFileName", imageFileName)
            put("imageThreshold", imageThreshold.toDouble())
            put("imageScale", imageScale)
            put("imageRegion", imageRegion)
            put("imageGrayMode", imageGrayMode)
            put("imageRetryCount", imageRetryCount)
            put("imageRetryDelay", imageRetryDelay)
            put("imageRegionRect", imageRegionRect)
        }
        put("actionType", actionType)
        put("randomOffsetEnabled", randomOffsetEnabled)
        put("randomOffsetRadius", randomOffsetRadius)
        // 参数按类型序列化：换回点击后不残留别的动作的无效参数
        put("actionParams", JSONObject().apply {
            when (actionType) {
                ACTION_DOUBLE_CLICK -> put("interval", doubleClickInterval)
                ACTION_LONG_PRESS -> put("duration", longPressDuration)
                ACTION_SWIPE -> {
                    put("direction", swipeDirection)
                    put("distance", swipeDistance)
                    put("duration", swipeDuration)
                }
            }
        })
    }

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_COORD = "coord"

        const val ACTION_CLICK = "click"
        const val ACTION_DOUBLE_CLICK = "double_click"
        const val ACTION_LONG_PRESS = "long_press"
        const val ACTION_SWIPE = "swipe"

        const val SWIPE_UP = "up"
        const val SWIPE_DOWN = "down"
        const val SWIPE_LEFT = "left"
        const val SWIPE_RIGHT = "right"

        const val MIN_DOUBLE_INTERVAL = 100L
        const val MAX_DOUBLE_INTERVAL = 300L
        const val MIN_LONG_PRESS = 200L
        const val MAX_LONG_PRESS = 10_000L
        const val MIN_SWIPE_DISTANCE = 50
        const val MIN_SWIPE_DURATION = 100L
        const val MAX_SWIPE_DURATION = 2000L
        const val MAX_RANDOM_RADIUS = 50

        /** 图像匹配参数边界（读盘与弹框输入共用） */
        const val MIN_IMAGE_THRESHOLD = 0f
        const val MAX_IMAGE_THRESHOLD = 1f
        const val MIN_IMAGE_SCALE = 1
        const val MAX_IMAGE_SCALE = 4
        const val MAX_IMAGE_RETRY_COUNT = 5
        const val MAX_IMAGE_RETRY_DELAY = 5_000L

        /** 滑动距离上限：屏幕短边的一半（至少保留最小值，避免小屏上区间为空） */
        fun maxSwipeDistance(screenWidth: Int, screenHeight: Int): Int =
            (minOf(screenWidth, screenHeight) / 2).coerceAtLeast(MIN_SWIPE_DISTANCE)

        private fun validActionType(raw: String): String =
            if (raw in setOf(ACTION_CLICK, ACTION_DOUBLE_CLICK, ACTION_LONG_PRESS, ACTION_SWIPE)) {
                raw
            } else {
                ACTION_CLICK
            }

        private fun validSwipeDirection(raw: String): String =
            if (raw in setOf(SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT)) raw else SWIPE_UP

        fun fromJson(o: JSONObject): QuickClickConfig {
            val actionType = validActionType(o.optString("actionType", ACTION_CLICK))
            val params = o.optJSONObject("actionParams")
            return QuickClickConfig(
                name = o.optString("name", "按钮"),
                type = o.optString("type", TYPE_TEXT),
                text = o.optString("text", ""),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                imageFileName = o.optString("imageFileName", ""),
                imageThreshold = o.optDouble("imageThreshold", 0.80).toFloat()
                    .coerceIn(MIN_IMAGE_THRESHOLD, MAX_IMAGE_THRESHOLD),
                imageScale = o.optInt("imageScale", 2).coerceIn(MIN_IMAGE_SCALE, MAX_IMAGE_SCALE),
                imageRegion = o.optString("imageRegion", "full"),
                imageGrayMode = o.optBoolean("imageGrayMode", true),
                imageRetryCount = o.optInt("imageRetryCount", 1).coerceIn(0, MAX_IMAGE_RETRY_COUNT),
                imageRetryDelay = o.optLong("imageRetryDelay", 300L).coerceIn(0L, MAX_IMAGE_RETRY_DELAY),
                imageRegionRect = o.optString("imageRegionRect", ""),
                actionType = actionType,
                doubleClickInterval = (params?.optLong("interval", 150L) ?: 150L)
                    .coerceIn(MIN_DOUBLE_INTERVAL, MAX_DOUBLE_INTERVAL),
                longPressDuration = (params?.optLong("duration", 800L) ?: 800L)
                    .coerceIn(MIN_LONG_PRESS, MAX_LONG_PRESS),
                swipeDirection = validSwipeDirection(params?.optString("direction", SWIPE_UP) ?: SWIPE_UP),
                swipeDistance = (params?.optInt("distance", 300) ?: 300)
                    .coerceAtLeast(MIN_SWIPE_DISTANCE),
                swipeDuration = (params?.optLong("duration", 300L) ?: 300L)
                    .coerceIn(MIN_SWIPE_DURATION, MAX_SWIPE_DURATION),
                randomOffsetEnabled = o.optBoolean("randomOffsetEnabled", true),
                randomOffsetRadius = o.optInt("randomOffsetRadius", 5)
                    .coerceIn(0, MAX_RANDOM_RADIUS),
            )
        }
    }
}

/**
 * 图像存储管理：模板图片统一存于 filesDir/images/（PNG）。
 * 与脚本/按钮生命周期绑定：删除脚本时清理其图像按钮引用的文件。
 */
object ImageStore {
    private const val IMAGES_DIR = "images"
    private const val TAG = "ImageStore"

    fun getImagesDir(context: Context): File =
        File(context.filesDir, IMAGES_DIR).apply { mkdirs() }

    /** 保存图片，返回文件名（不含路径） */
    fun saveImage(context: Context, bitmap: Bitmap, fileName: String): Boolean = try {
        val file = File(getImagesDir(context), fileName)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        true
    } catch (e: Exception) {
        android.util.Log.e(TAG, "保存图片失败", e)
        false
    }

    /** 读取图片，失败返回 null */
    fun loadImage(context: Context, fileName: String): Bitmap? = try {
        val file = File(getImagesDir(context), fileName)
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "读取图片失败", e)
        null
    }

    /** 删除图片文件 */
    fun deleteImage(context: Context, fileName: String) {
        try {
            File(getImagesDir(context), fileName).delete()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "删除图片失败", e)
        }
    }

    /** 生成唯一文件名 */
    fun generateFileName(baseName: String): String {
        val safe = baseName.replace(Regex("[^\\w]"), "_").take(10)
        return "img_${safe}_${System.currentTimeMillis()}.png"
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
 * 悬浮窗上正在编辑的按钮列表本身不自动持久化（启动为空白编辑态），
 * 只有「保存当前按钮 / 另存为」命名后的脚本才会落盘并可在「脚本管理」加载。
 */
object QuickClickStore {

    private const val PREFS = "quick_click_config"
    private const val KEY_SCRIPTS = "scripts"
    private const val KEY_SCRIPTS_BACKUP = "scripts_unreadable_backup"
    private const val KEY_DEBOUNCE = "debounce"               // 防抖间隔 ms
    private const val KEY_WINDOW_ALPHA = "window_alpha"       // 悬浮窗不透明度 [0.2, 1]
    private const val TAG = "QuickClick"

    /** 不透明度下限：留一点可见度，避免调成全透明后无法再找到悬浮窗 */
    const val MIN_WINDOW_ALPHA = 0.2f

    /** 脚本数量与单个脚本按钮数量上限：防无限增长 */
    const val MAX_SCRIPTS = 50
    const val MAX_CONFIGS_PER_SCRIPT = 50

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 一次读取的结果；skipped>0 表示列表不完整，覆盖写前必须留档（见 keepBackupIfLossy） */
    private class ScriptRead(val scripts: MutableList<QuickScript>, val raw: String?, val skipped: Int) {
        val lossy: Boolean get() = skipped > 0
    }

    /** 读取全部已保存脚本 */
    fun loadScripts(context: Context): MutableList<QuickScript> = readScripts(context).scripts

    private fun readScripts(context: Context): ScriptRead {
        val raw = prefs(context).getString(KEY_SCRIPTS, null)
            ?: return ScriptRead(mutableListOf(), null, 0)
        val list = mutableListOf<QuickScript>()
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
            val cfgArr = o.optJSONArray("configs") ?: JSONArray()
            val configs = mutableListOf<QuickClickConfig>()
            for (j in 0 until cfgArr.length()) {
                val cfgObj = cfgArr.optJSONObject(j)
                if (cfgObj == null) {
                    skipped++
                    continue
                }
                runCatching { QuickClickConfig.fromJson(cfgObj) }
                    .onSuccess { configs.add(it) }
                    .onFailure { skipped++ }
            }
            // 旧版本缺字段由 fromJson 补默认值，不再借「读取」触发一次整表回写
            list.add(QuickScript(o.optString("name", "脚本"), configs))
        }
        return ScriptRead(list, raw, skipped)
    }

    /** 坏条目读不出来时，先把磁盘上的原始串留一份档，覆盖写才不至于把没读到的脚本永久删掉 */
    private fun keepBackupIfLossy(context: Context, read: ScriptRead) {
        if (!read.lossy || read.raw == null) return
        val p = prefs(context)
        if (p.contains(KEY_SCRIPTS_BACKUP)) return
        p.edit().putString(KEY_SCRIPTS_BACKUP, read.raw).apply()
        android.util.Log.e(TAG, "脚本数据有 ${read.skipped} 条未能读出，覆盖写前已留原始备份")
        Toast.makeText(
            context, "脚本文档有损坏条目，本次覆盖写前已自动备份原始数据", Toast.LENGTH_LONG
        ).show()
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
            prefs(context).edit().putString(KEY_SCRIPTS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "脚本保存失败", e)
        }
    }

    /** 删除候选图片里已经不再被任何脚本引用的那些 */
    private fun deleteUnreferencedImages(
        context: Context,
        candidates: List<String>,
        stillSaved: List<QuickScript>,
    ) {
        val referenced = stillSaved.flatMap { it.configs }
            .mapTo(HashSet()) { it.imageFileName }
        candidates.filter { it.isNotEmpty() && it !in referenced }
            .forEach { ImageStore.deleteImage(context, it) }
    }

    /**
     * 另存为：脚本名已存在则覆盖，并清理被替换脚本遗留下来的孤儿模板图。
     * 超出上限时拒绝写入并提示（不做静默截断，避免用户以为已保存）。
     */
    fun saveScript(context: Context, name: String, configs: List<QuickClickConfig>): Boolean {
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        val idx = scripts.indexOfFirst { it.name == name }
        if (idx < 0 && scripts.size >= MAX_SCRIPTS) {
            Toast.makeText(context, "脚本数量已达上限 $MAX_SCRIPTS，请先删除部分脚本", Toast.LENGTH_LONG).show()
            return false
        }
        if (configs.size > MAX_CONFIGS_PER_SCRIPT) {
            Toast.makeText(context, "单个脚本最多 $MAX_CONFIGS_PER_SCRIPT 个按钮", Toast.LENGTH_LONG).show()
            return false
        }
        val replacedImages = scripts.getOrNull(idx)?.configs
            ?.filter { it.type == QuickClickConfig.TYPE_IMAGE }
            ?.map { it.imageFileName }
            ?: emptyList()
        val script = QuickScript(name, configs.toList())
        if (idx >= 0) scripts[idx] = script else scripts.add(script)
        persist(context, scripts)
        if (idx >= 0) deleteUnreferencedImages(context, replacedImages, scripts)
        return true
    }

    fun deleteScript(context: Context, name: String) {
        val read = readScripts(context)
        keepBackupIfLossy(context, read)
        val scripts = read.scripts
        // 清理该脚本下图像按钮引用的图片文件
        val images = scripts.find { it.name == name }?.configs
            ?.filter { it.type == QuickClickConfig.TYPE_IMAGE }
            ?.map { it.imageFileName }
            ?: emptyList()
        scripts.removeAll { it.name == name }
        persist(context, scripts)
        deleteUnreferencedImages(context, images, scripts)
    }

    /** 重命名脚本；新名已被占用时返回 false */
    fun renameScript(context: Context, oldName: String, newName: String): Boolean {
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

    /** 防抖间隔，默认 500ms */
    fun getDebounce(context: Context): Long {
        return prefs(context).getLong(KEY_DEBOUNCE, 500L)
    }

    /** 悬浮窗不透明度，默认 1f（完全不透明） */
    fun getWindowAlpha(context: Context): Float {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_WINDOW_ALPHA, 1f)
            .coerceIn(MIN_WINDOW_ALPHA, 1f)
    }

    fun setWindowAlpha(context: Context, alpha: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_WINDOW_ALPHA, alpha.coerceIn(MIN_WINDOW_ALPHA, 1f))
            .apply()
    }
}
