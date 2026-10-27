package com.hpu.autoclicker.core.ui

import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.View
import java.lang.reflect.Proxy

/**
 * 限定悬浮窗里真正接收触摸的区域，区域外的触摸交给下层窗口。
 *
 * 系统只给「窗口触摸区域」留了一条路：`ViewTreeObserver#addOnComputeInternalInsetsListener`
 * 配合 `InternalInsetsInfo#touchableRegion`，矩形用窗口坐标。这套接口至今仍是 @hide，
 * 只能反射注册；任何一步被非 SDK 接口策略拦下时静默退回「整窗可点」并打一条日志，
 * 悬浮窗的其它交互不受影响。
 */
class WindowTouchRegion(private val view: View) {

    private val region = Region()
    private var registered = false
    private var failed = false

    private val listenerClass: Class<*>? = runCatching {
        Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    }.getOrNull()

    private val infoClass: Class<*>? = runCatching {
        Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    }.getOrNull()

    private val setTouchableInsets = runCatching {
        infoClass?.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
    }.getOrNull()

    private val touchableRegionField = runCatching {
        infoClass?.getField("touchableRegion")
    }.getOrNull()

    /** TOUCHABLE_INSETS_REGION 的常量值；取不到时用 AOSP 里固定的 3 */
    private val regionMode: Int = runCatching {
        infoClass?.getField("TOUCHABLE_INSETS_REGION")?.getInt(null)
    }.getOrNull() ?: 3

    private val proxy: Any? = runCatching {
        val cls = listenerClass ?: return@runCatching null
        Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, method, args ->
            if (method.name == "onComputeInternalInsets") applyTo(args?.firstOrNull())
            null
        }
    }.getOrNull()

    fun attach() {
        if (registered || failed) return
        val proxy = proxy
        if (proxy == null || listenerClass == null || setTouchableInsets == null ||
            touchableRegionField == null
        ) {
            fail("反射不到 insets 接口")
            return
        }
        runCatching {
            val observer = view.viewTreeObserver
            observer.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerClass)
                .invoke(observer, proxy)
            registered = true
        }.onFailure { fail("注册监听失败：${it.message}") }
    }

    /** 设置可点区域（窗口坐标）；传空集合表示整窗可点 */
    fun setRects(rects: List<Rect>) {
        val next = Region()
        rects.forEach { next.union(it) }
        if (next == region) return
        region.set(next)
        // 触摸区域在下次遍历时才会写进窗口，没有其它变化时手动触发
        if (registered) view.post { view.requestLayout() }
    }

    private fun applyTo(info: Any?) {
        if (info == null || region.isEmpty) return
        runCatching {
            (touchableRegionField?.get(info) as? Region)?.set(region)
            setTouchableInsets?.invoke(info, regionMode)
        }.onFailure { fail("写入触摸区域失败：${it.message}") }
    }

    private fun fail(reason: String) {
        if (failed) return
        failed = true
        Log.w("WindowTouchRegion", "窗口触摸区域不可用（$reason），悬浮窗保持整窗可点")
    }
}
