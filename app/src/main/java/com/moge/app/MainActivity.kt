package com.moge.app

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.moge.app.data.figure.StoredFigureResolver
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.ui.MogeNavHost
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.domain.SolveMode
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.usesChalk
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sharedCapture = MutableStateFlow<CaptureBatch?>(null)

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var figureResolver: StoredFigureResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedDocument(intent)
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(initialValue = Appearance.SYSTEM)
            val chalk = usesChalk(appearance, isSystemInDarkTheme())
            // 状态栏图标颜色跟随「外观」而不是系统深浅色：方格本用深色图标，黑板用浅色图标
            DisposableEffect(chalk) {
                val style = if (chalk) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            MogeTheme(appearance = appearance) {
                CompositionLocalProvider(LocalFigurePathResolver provides figureResolver) {
                    val capture by sharedCapture.collectAsStateWithLifecycle()
                    MogeNavHost(capture) { sharedCapture.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedDocument(intent)
    }

    private fun handleSharedDocument(intent: Intent?) {
        val source = intent ?: return
        val action = source.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = source.type.orEmpty()
        if (type.startsWith("image/")) return
        val uris = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        val sharedUris = if (uris.isNotEmpty()) uris else buildList {
            val clip = source.clipData ?: return@buildList
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
        if (sharedUris.isEmpty()) return
        lifecycleScope.launch {
            val store = CaptureStore(this@MainActivity)
            val copied = sharedUris.take(8).mapNotNull { uri -> runCatching { store.importDocument(uri) }.getOrNull() }
            if (copied.isNotEmpty()) {
                sharedCapture.value = CaptureBatch(
                    photoPaths = emptyList(), solveMode = SolveMode.DETAILED,
                    documentPaths = copied,
                )
            }
        }
    }
}
