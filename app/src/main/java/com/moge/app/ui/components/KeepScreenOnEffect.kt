package com.moge.app.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.WeakHashMap

/** Visible conversation pages own a lease; overlapping navigation animations cannot release each other. */
@Composable
internal fun KeepConversationScreenOn(enabled: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, enabled) {
        var lease: ScreenOnLease? = null
        fun update() {
            val active = enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (active && lease == null) lease = ScreenOnLease.acquire(view)
            if (!active) { lease?.close(); lease = null }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); lease?.close() }
    }
}

/** Main-thread resource ownership; restores an existing flag when the last page leaves. */
private class ScreenOnLease private constructor(private val view: View) {
    private var closed = false
    fun close() {
        if (closed) return
        closed = true
        val entry = owners[view] ?: return
        if (--entry.count == 0) {
            view.keepScreenOn = entry.original
            owners.remove(view)
        }
    }

    companion object {
        private class Owners(var count: Int, val original: Boolean)
        private val owners = WeakHashMap<View, Owners>()
        fun acquire(view: View): ScreenOnLease {
            val entry = owners.getOrPut(view) { Owners(0, view.keepScreenOn) }
            entry.count++
            view.keepScreenOn = true
            return ScreenOnLease(view)
        }
    }
}
