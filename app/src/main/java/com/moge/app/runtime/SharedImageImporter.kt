package com.moge.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.share.sharedContentUris
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.capture.CaptureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject

@Serializable
data class SharedImages(val id: String, val photoPaths: List<String>, val text: String = "")

/** Copy first, choose a destination afterwards. Importing never submits a model request. */
class SharedImageImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val photos: CaptureStore,
    private val conversations: ConversationRepository,
    private val drafts: DraftStore,
    private val settings: SettingsRepository,
) {
    fun imageUris(intent: Intent): List<Uri> {
        val uris = sharedContentUris(intent)
        if (uris.isEmpty()) return emptyList()
        val declaredImage = intent.type.orEmpty().startsWith("image/") ||
            intent.clipData?.description?.hasMimeType("image/*") == true
        return if (declaredImage || uris.all { uri ->
            runCatching { context.contentResolver.getType(uri)?.startsWith("image/") == true }.getOrDefault(false)
        }) uris else emptyList()
    }

    suspend fun import(intent: Intent): SharedImages? {
        val uris = imageUris(intent)
        if (uris.isEmpty()) return null
        require(uris.size <= CaptureStore.MAX_PHOTOS) { "一次最多分享 ${CaptureStore.MAX_PHOTOS} 张图片" }
        val imported = mutableListOf<String>()
        try {
            for (uri in uris) imported += photos.importSharedPhoto(uri)
            return SharedImages(UUID.randomUUID().toString(), imported.toList(),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        } catch (error: Throwable) {
            // No destination owns these files until the entire batch has been copied.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { photos.discard(imported) }
            throw error
        }
    }

    suspend fun createDraft(share: SharedImages, mode: ConversationMode): String {
        val conversation = conversations.createConversation(
            share.text.ifBlank { if (mode == ConversationMode.CHAT) "图片对话" else "图片题目" },
            settings.current().defaultSolveMode, mode)
        try {
            drafts.persist(drafts.reserveSave(conversation.id, share.text, share.photoPaths, conversationMode = mode))
        } catch (error: Throwable) {
            if (error !is CancellationException) conversations.discardIfEmpty(conversation.id)
            throw error
        }
        return conversation.id
    }

    suspend fun discard(share: SharedImages) {
        // A failed delivery can already be visible in a live draft. Keep its owned files even
        // if the destination dialog is subsequently dismissed; maintenance handles leftovers.
        val referenced = drafts.referencedPhotoPaths() + conversations.referencedImagePaths()
        photos.discard(share.photoPaths.filterNot { it in referenced })
    }
}
