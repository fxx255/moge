package com.moge.app

import android.graphics.Color
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
import com.moge.app.data.figure.StoredFigureResolver
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.ui.MogeNavHost
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.usesChalk
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var figureResolver: StoredFigureResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    MogeNavHost()
                }
            }
        }
    }
}
