package com.moge.app.runtime

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Android's rename replaces an existing destination atomically. Java File.renameTo on
 * Windows does not. Keep real disk IO and only adapt this platform-specific primitive;
 * failed moves still leave .new in place, so the store's commit verification detects them.
 */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    companion object {
        @JvmStatic
        @Implementation(minSdk = 30)
        protected fun rename(source: File, target: File) {
            if (target.isDirectory) target.delete()
            try {
                Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // AtomicFile logs failed rename; it does not throw. Verification must fail.
            }
        }
    }
}
