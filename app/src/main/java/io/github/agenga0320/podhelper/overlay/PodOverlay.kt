package io.github.agenga0320.podhelper.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.agenga0320.podhelper.PodRepository

/**
 * 浮動視窗：全螢幕半透明遮罩 + 底部卡片，點遮罩或 ✕ 關閉（和 iPhone 一樣）。
 * 需要「顯示在其他應用程式上層」權限；鎖定畫面上不會顯示。
 */
class PodOverlay(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private val visible = MutableTransitionState(false)
    private val autoHide = Runnable { hide() }

    fun show(autoHideMs: Long) {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, autoHideMs)
        if (view != null) {
            visible.targetState = true
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val o = OverlayOwner()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                val state by PodRepository.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
                                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::hide),
                        )
                    }
                    AnimatedVisibility(
                        visible,
                        Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        PodCard(
                            state, onClose = ::hide,
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(10.dp)
                                // 吃掉卡片上的點擊，避免傳到遮罩而關閉
                                .clickable(remember { MutableInteractionSource() }, indication = null) {},
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            o.destroy()
            return
        }
        view = v
        owner = o
        visible.targetState = true
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        val v = view ?: return
        visible.targetState = false
        handler.postDelayed({
            if (view === v && !visible.targetState) {
                runCatching { wm.removeView(v) }
                owner?.destroy()
                view = null
                owner = null
            }
        }, 400)
    }
}

/** 讓 ComposeView 能在 Service 裡運作（沒有 Activity 提供 lifecycle）。 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
