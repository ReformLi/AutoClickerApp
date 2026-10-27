package com.hpu.autoclicker.core.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 调试日志开关。
 *
 * 链路日志会带上目标文字、前台应用包名、界面可见文字清单与坐标，这些属于用户屏幕上的内容，
 * 因此 release 包（未标记 debuggable）一律静默；异常与失败仍走 Log.e / Log.w，不受这里影响。
 */
object DebugLog {

    @Volatile
    var enabled: Boolean = false

    /** Activity / Service 创建时按当前包是否 debuggable 赋值 */
    fun syncFrom(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun v(tag: String, message: String) {
        if (enabled) Log.v(tag, message)
    }
}
