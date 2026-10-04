package com.moge.app

import android.graphics.Color
import android.content.Intent
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
import com.moge.app.data.document.DocumentShareImporter
import com.moge.app.ui.Routes
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.usesChalk
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.widget.Toast

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sharedRoutes = MutableStateFlow<List<String>>(emptyList())
    private val shareMutex = Mutex()

    @Inject lateinit var documentImporter: DocumentShareImporter

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var figureResolver: StoredFigureResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedRoutes.value = savedInstanceState?.getStringArrayList("sharedDocumentRoutes").orEmpty()
        if (savedInstanceState?.getBoolean("sharedDocumentHandled") != true) handleSharedDocument(intent)
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
                    val routes by sharedRoutes.collectAsStateWithLifecycle()
                    MogeNavHost(routes.firstOrNull()) { sharedRoutes.value = sharedRoutes.value.drop(1) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedDocument(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("sharedDocumentRoutes", ArrayList(sharedRoutes.value))
        outState.putBoolean("sharedDocumentHandled", intent?.action == null)
        super.onSaveInstanceState(outState)
    }

    private fun handleSharedDocument(intent: Intent?) {
        val source = intent ?: return
        if (DocumentShareImporter.documentUris(source).isEmpty()) return
        lifecycleScope.launch {
            shareMutex.withLock {
                try {
                    documentImporter.import(source)?.let { id -> sharedRoutes.value += Routes.solve(id) }
                    source.action = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Toast.makeText(this@MainActivity, error.message ?: "文档导入失败，请重新分享", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
