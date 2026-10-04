package com.moge.app.data.document

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.runtime.DraftStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Copies provider files while the intent grant is valid, then opens an independent draft. */
class DocumentShareImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val documents: DocumentStore,
    private val drafts: DraftStore,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
) {
    suspend fun import(intent: Intent): String? {
        val uris = documentUris(intent)
        if (uris.isEmpty()) return null
        require(uris.size <= DocumentStore.MAX_ATTACHMENTS) { "一次最多分享 8 个文档" }
        val paths = uris.map { documents.import(it) }
        val name = documents.attachment(paths.first()).name
        val conversation = conversations.createConversation(name, settings.current().defaultSolveMode)
        try {
            drafts.persist(drafts.reserveSave(conversation.id, "", emptyList(), paths))
        } catch (error: Throwable) {
            if (error !is CancellationException) conversations.discardIfEmpty(conversation.id)
            throw error
        }
        return conversation.id
    }

    companion object {
        @Suppress("DEPRECATION")
        fun documentUris(intent: Intent): List<Uri> {
            if (intent.type.orEmpty().startsWith("image/")) return emptyList()
            val supplied = when (intent.action) {
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> return emptyList()
            }
            val clipped = buildList {
                intent.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                }
            }
            return (supplied + clipped).distinct().filter { it.scheme in setOf("content", "file") }
        }
    }
}
