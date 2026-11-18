package com.moge.app.ui.export

import android.content.Context
import com.moge.app.R
import com.moge.app.data.db.ConversationExportRepository
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.figure.StoredFigureResolver
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationExportItem
import com.moge.app.domain.ConversationExportSource
import com.moge.app.ui.localization.AppStrings
import com.moge.app.ui.localization.localizedContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Coordinates storage snapshots, localized labels and the renderer outside Compose. */
class ConversationPdfExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ConversationExportRepository,
    private val settings: SettingsRepository,
    private val resolver: StoredFigureResolver,
    private val documents: DocumentStore,
) {
    internal suspend fun export(
        source: ConversationExportSource,
        items: List<ConversationExportItem>,
        categories: List<NotebookCategoryEntity>,
        progress: (String) -> Unit,
    ): ConversationPdfResult {
        val preferences = settings.settings.first()
        val local = localizedContext(context, preferences.interfaceLanguage)
        val strings = AppStrings(local)
        val title = strings.text(if (source == ConversationExportSource.NOTEBOOK) R.string.ui_my_notebook_7ec53 else R.string.ui_history_68387)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return renderConversationPdf(local, items.size, "${title}_$stamp.pdf", resolver,
            section = { index ->
                val item = items[index]
                val snapshot = repository.snapshot(source, item.id)
                val parts = withContext(Dispatchers.IO) {
                    conversationPdfParts(snapshot, strings) { path ->
                        runCatching { documents.attachment(path).name }.getOrElse { File(path).name }
                    }
                }
                val category = categories.firstOrNull { it.id == item.categoryId }?.name ?: strings.text(R.string.ui_uncategorized_b28f1)
                val date = item.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
                ConversationPdfSection(snapshot.title, "$category · $date", parts)
            }, onProgress = progress)
    }
}
