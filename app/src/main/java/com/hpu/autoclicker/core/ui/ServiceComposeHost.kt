package com.hpu.autoclicker.core.ui

import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 共享基础设施：Service 内使用 ComposeView 的最小宿主实现。
 *
 * ComposeView.setContent 在 attach 时要求 ViewTreeLifecycleOwner；
 * 而 Service / 系统 AlertDialog 场景下默认没有该 Owner，
 * 不补齐会抛 “ViewTreeLifecycleOwner not found from ...”。
 */
class ServiceComposeHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    // Compose 在 Service 场景下还会查找 SavedStateRegistryOwner / ViewModelStoreOwner，
    // 缺失时 rememberSaveable、LocalViewModelStoreOwner 等能力不可用，一并补齐
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * 把宿主注入 ComposeView，并让 Compose 认为宿主已 RESUMED（可正常组合/重组）。
     * 必须保证 lifecycle 处于 INITIALIZED 时 restore，否则 SavedStateRegistry 会抛异常。
     */
    fun attach(view: ComposeView) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        attachWindowRoot(view)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * 把宿主 Owner 挂到「窗口根视图」上（Service 场景即对话框窗口的 DecorView）。
     *
     * 只给 ComposeView 自己挂 Owner 在对话框场景下是不够的：
     * Compose 取窗口 Recomposer 时，会先从 ComposeView 向上找到「父级 id == android.R.id.content」
     * 的那一层视图（WindowRecomposer 的 contentChild），对话框里这一层正是 AlertDialogLayout，
     * 然后从该视图开始向上查找 ViewTreeLifecycleOwner。
     * Activity 场景下 Activity 已把 Owner 挂在 DecorView 上，所以能一路找到；
     * Service 场景没有这一步，就会抛
     * “ViewTreeLifecycleOwner not found from com.android.internal.widget.AlertDialogLayout”。
     * 因此必须在 dialog.show() 之前，把 Owner 同时挂到对话框窗口的 DecorView 上。
     */
    fun attachWindowRoot(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
