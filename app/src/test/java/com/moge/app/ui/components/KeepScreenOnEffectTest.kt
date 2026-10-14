package com.moge.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KeepScreenOnEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class PageOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun `toggle pause resume and leaving page release screen ownership`() {
        val owner = PageOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var enabled by mutableStateOf(false)
        var visible by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible) KeepConversationScreenOn(enabled)
            }
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn); enabled = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertFalse(view.keepScreenOn); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertTrue(view.keepScreenOn); enabled = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn); enabled = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); visible = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test fun `overlapping pages and existing owner retain their flags`() {
        val owner = PageOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (first) KeepConversationScreenOn(true)
                if (second) KeepConversationScreenOn(true)
            }
        }
        compose.runOnIdle { assertTrue(view.keepScreenOn); first = false }
        compose.runOnIdle { assertTrue(view.keepScreenOn); second = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn); view.keepScreenOn = true; first = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn); first = false }
        compose.runOnIdle { assertTrue(view.keepScreenOn); view.keepScreenOn = false }
    }
}
