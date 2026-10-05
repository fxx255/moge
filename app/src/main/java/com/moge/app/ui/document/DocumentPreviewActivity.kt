package com.moge.app.ui.document

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.document.*
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.usesChalk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DocumentPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        val locator = intent.getStringExtra("locator").orEmpty()
        val settings = SettingsRepository(applicationContext)
        setContent {
            DocumentPreviewTheme(settings.appearance, onSystemBars = { chalk ->
                val style = if (chalk) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }) { DocumentPreview(path, locator, onClose = ::finish) }
        }
    }

    companion object {
        fun open(context: Context, path: String, locator: String = "") {
            context.startActivity(Intent(context, DocumentPreviewActivity::class.java)
                .putExtra("path", path).putExtra("locator", locator).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun openLink(context: Context, uri: Uri) {
            val id = uri.host ?: return
            val file = File(context.filesDir, DocumentStore.DIRECTORY).listFiles()?.firstOrNull {
                it.nameWithoutExtension == id && it.extension.lowercase() in DocumentStore.EXTENSIONS
            } ?: return
            open(context, file.path, uri.getQueryParameter("locator").orEmpty())
        }
    }
}

/** A separate Activity must use the same saved appearance as the main window. */
@Composable
internal fun DocumentPreviewTheme(
    appearance: Flow<Appearance>,
    onSystemBars: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val selected by appearance.collectAsStateWithLifecycle(initialValue = Appearance.SYSTEM)
    val chalk = usesChalk(selected, isSystemInDarkTheme())
    DisposableEffect(chalk) {
        onSystemBars(chalk)
        onDispose { }
    }
    MogeTheme(appearance = selected, content = content)
}

@Composable
private fun DocumentPreview(path: String, initialLocator: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { DocumentStore(context) }
    var attachment by remember(path) { mutableStateOf<DocumentAttachment?>(null) }
    var index by remember(path) { mutableStateOf<DocumentIndex?>(null) }
    var selected by androidx.compose.runtime.saveable.rememberSaveable(path) { mutableIntStateOf(0) }
    var locator by androidx.compose.runtime.saveable.rememberSaveable(path) { mutableStateOf(initialLocator) }
    var text by remember(path) { mutableStateOf("") }
    var image by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadedLocator by remember(path) { mutableStateOf<String?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var indexing by remember(path) { mutableStateOf(true) }
    var reading by remember(path) { mutableStateOf(false) }
    var retry by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(path, retry) {
        indexing = true; error = null
        try {
            attachment = withContext(Dispatchers.IO) { store.attachment(path) }
            val result = withContext(Dispatchers.IO) { DocumentReader.index(context, store.ownedFile(path)) }
            index = result
            val sectionLocator = result.tables.firstOrNull { it.id == locator }?.source ?: locator
            selected = result.sections.indexOfFirst { it.id == sectionLocator }.takeIf { it >= 0 }
                ?: selected.coerceIn(0, result.sections.lastIndex.coerceAtLeast(0))
            if (locator.isBlank()) locator = result.sections.firstOrNull()?.id.orEmpty()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "文件暂时无法读取"
        } finally { indexing = false }
    }
    LaunchedEffect(attachment, index, locator, retry) {
        val doc = attachment ?: return@LaunchedEffect
        if (index == null || locator.isBlank()) return@LaunchedEffect
        reading = true; error = null; text = ""; image = null
        try {
            val result = withContext(Dispatchers.IO) {
                val file = store.ownedFile(doc.path)
                if (file.extension.equals("pdf", true) || locator in index?.images.orEmpty()) {
                    val encoded = DocumentReader.image(context, file, locator).substringAfter(',')
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    "" to (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("页面图像无法读取"))
                } else DocumentReader.read(context, file, locator).text to null
            }
            text = result.first; image = result.second; loadedLocator = locator
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: "此内容暂时无法读取"
        } finally { reading = false }
    }
    DocumentPreviewScreen(
        state = DocumentPreviewUiState(attachment, index, selected, locator, text, image,
            busy = indexing || reading || (error == null && locator.isNotBlank() && loadedLocator != locator), error = error),
        onClose = onClose,
        onOpenOriginal = {
            attachment?.let { document -> runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", store.ownedFile(document.path))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, document.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure {
                android.widget.Toast.makeText(context, "没有可打开此格式的应用", android.widget.Toast.LENGTH_SHORT).show()
            } }
        },
        onSection = { position ->
            index?.sections?.getOrNull(position)?.let { selected = position; locator = it.id }
        },
        onLocator = { locator = it },
        onRetry = { retry++ },
    )
}
