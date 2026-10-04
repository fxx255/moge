package com.moge.app.ui.document

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.moge.app.data.document.*
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DocumentPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        val locator = intent.getStringExtra("locator").orEmpty()
        setContent { MogeTheme { DocumentPreview(path, locator, onClose = ::finish) } }
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

@Composable
private fun DocumentPreview(path: String, initialLocator: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { DocumentStore(context) }
    var attachment by remember { mutableStateOf<DocumentAttachment?>(null) }
    var index by remember { mutableStateOf<DocumentIndex?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var locator by remember { mutableStateOf(initialLocator) }
    var text by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(path) {
        try {
            val result = withContext(Dispatchers.IO) { store.attachment(path) to DocumentReader.index(context, store.ownedFile(path)) }
            attachment = result.first; index = result.second
            selected = result.second.sections.indexOfFirst { it.id == initialLocator }.coerceAtLeast(0)
            if (locator.isBlank()) locator = result.second.sections.firstOrNull()?.id.orEmpty()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
        finally { busy = false }
    }
    LaunchedEffect(attachment, locator) {
        val doc = attachment ?: return@LaunchedEffect
        if (locator.isBlank()) return@LaunchedEffect
        busy = true; error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val file = store.ownedFile(doc.path)
                if (file.extension == "pdf" || locator in index?.images.orEmpty()) {
                    val encoded = DocumentReader.image(context, file, locator).substringAfter(',')
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    "" to BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else DocumentReader.read(context, file, locator).text to null
            }
            text = result.first; image = result.second
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
        finally { busy = false }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onClose) { Text("返回") }
                Text(attachment?.name ?: "文档", Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = {
                    attachment?.let { document ->
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", store.ownedFile(document.path))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, document.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { error = "没有可打开此格式的应用" }
                    }
                }) { Text("打开原件") }
            }
            index?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val sections = index?.sections.orEmpty()
            if (sections.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = selected > 0, onClick = { selected--; locator = sections[selected].id }) { Text("上一项") }
                    OutlinedTextField(value = (selected + 1).toString(), onValueChange = {
                        val value = it.toIntOrNull()?.minus(1)
                        if (value != null && value in sections.indices) { selected = value; locator = sections[value].id }
                    }, label = { Text("位置 / ${sections.size}") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = selected < sections.lastIndex, onClick = { selected++; locator = sections[selected].id }) { Text("下一项") }
                }
                Text(index?.tables?.firstOrNull { it.id == locator }?.let { it.title + "（" + it.source + "）" } ?: sections[selected].title)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                image?.let { Image(it.asImageBitmap(), "文档页面", Modifier.fillMaxWidth()) }
                if (text.isNotEmpty()) SelectionContainer { Text(text) }
                index?.images.orEmpty().forEach { path ->
                    TextButton(onClick = { locator = path }) { Text("查看图片：" + path.substringAfterLast('/')) }
                }
            }
        }
    }
}
