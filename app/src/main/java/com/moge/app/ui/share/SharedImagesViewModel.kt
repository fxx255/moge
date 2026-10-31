package com.moge.app.ui.share

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.QuestionPhotoPreparation
import com.moge.app.runtime.SharedImageImporter
import com.moge.app.runtime.SharedImages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Retains imported files and the destination choice across activity recreation. */
@HiltViewModel
class SharedImagesViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val importer: SharedImageImporter,
    private val photoPreparation: QuestionPhotoPreparation,
) : ViewModel() {
    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SharedImages.serializer())
    private val _pending: MutableStateFlow<List<SharedImages>> = MutableStateFlow(savedState.get<String>(QUEUE)?.let {
        runCatching { codec.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty())
    val pending: StateFlow<List<SharedImages>> = _pending.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    val destination: StateFlow<String?> = savedState.getStateFlow(DESTINATION, null)
    private val importing = mutableSetOf<String>()

    /** Return true for image shares, so the document receiver does not also handle them. */
    fun receive(intent: Intent): Boolean {
        val uris = importer.imageUris(intent)
        if (uris.isEmpty()) return false
        val key = intent.getStringExtra(RECEIPT) ?: java.util.UUID.randomUUID().toString().also { intent.putExtra(RECEIPT, it) }
        if (key in savedState.get<ArrayList<String>>(HANDLED).orEmpty()) {
            intent.action = null
            return true
        }
        if (!importing.add(key)) return true
        viewModelScope.launch {
            try {
                importer.import(intent)?.let { update(_pending.value + it.copy(id = key)) }
                savedState[HANDLED] = ArrayList((savedState.get<ArrayList<String>>(HANDLED).orEmpty() + key).takeLast(32))
                intent.action = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: "图片导入失败，请重新分享"
                intent.action = null
            } finally { importing.remove(key) }
        }
        return true
    }

    fun clearError() { _error.value = null }

    fun onPhotoCropStarted(path: String, mode: ConversationMode) = photoPreparation.beginCrop(path, mode)
    fun onPhotoCropClosed(path: String) = photoPreparation.endCrop(path)
    suspend fun savePhotoCrop(path: String, mode: ConversationMode, edit: PhotoCropEdit) =
        photoPreparation.saveCrop(path, mode, edit)

    /** Complete delivery before dequeuing; a failed destination leaves the imported batch available. */
    fun deliver(mode: ConversationMode?, current: suspend (SharedImages) -> Unit) {
        if (_busy.value) return
        val share = _pending.value.firstOrNull() ?: return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (mode != null) savedState[DESTINATION] = importer.createDraft(share, mode)
                else current(share)
                update(_pending.value.filterNot { it.id == share.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: "无法添加图片，请重试"
            } finally { _busy.value = false }
        }
    }

    fun destinationOpened() { savedState[DESTINATION] = null }

    fun dismiss() {
        if (_busy.value) return
        val share = _pending.value.firstOrNull() ?: return
        update(_pending.value.drop(1))
        _error.value = null
        viewModelScope.launch {
            try { importer.discard(share) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unreferenced files are also handled by attachment maintenance. */ }
        }
    }

    private fun update(queue: List<SharedImages>) {
        savedState[QUEUE] = codec.encodeToString(serializer, queue)
        _pending.value = queue
    }

    private companion object {
        const val QUEUE = "shared_image_queue"
        const val DESTINATION = "shared_image_destination"
        const val RECEIPT = "com.moge.app.shared_image_receipt"
        const val HANDLED = "handled_shared_image_receipts"
    }
}
