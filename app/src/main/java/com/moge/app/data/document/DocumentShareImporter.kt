package com.moge.app.data.document

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.share.sharedContentUris
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
        val nameHint = if (uris.size == 1) intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.clipData?.description?.label?.toString()?.takeIf {
                it.substringAfterLast('.', "").lowercase() in DocumentStore.EXTENSIONS
            } else null
        val paths = uris.map { documents.import(it, mimeHint = intent.type, nameHint = nameHint) }
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
        fun documentUris(intent: Intent): List<Uri> {
            if (intent.type.orEmpty().startsWith("image/")) return emptyList()
            return sharedContentUris(intent)
        }
    }
}
