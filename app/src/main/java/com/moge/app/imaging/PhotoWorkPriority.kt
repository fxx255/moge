package com.moge.app.imaging

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Foreground saves take precedence at background image stage/stripe boundaries. */
@Singleton
class PhotoWorkPriority @Inject constructor() {
    private val monitor = Object()
    private var saves = 0

    fun beginSave(): AutoCloseable {
        synchronized(monitor) { saves++ }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(monitor) {
                saves--
                monitor.notifyAll()
            }
        }
    }

    /** IO/native worker threads only. Cancellation is checked even while a save is active. */
    fun backgroundCheckpoint(checkActive: () -> Unit) {
        checkActive()
        synchronized(monitor) {
            while (saves > 0) {
                monitor.wait(50)
                checkActive()
            }
        }
    }
}
