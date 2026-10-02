package com.moge.app.ui.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.core.IoDispatcher
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.SolveMode
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.components.PhotoArrivals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 当前模型配置快照；拍照流程不因模型能力限制附件交接。 */
data class ModelChip(val primary: String, val recognizer: String?, val canReadPhotos: Boolean)

data class HomeUiState(
    val chip: ModelChip? = null,
    /** 模型配置快照是否已读完。 */
    val modelsLoaded: Boolean = false,
    /** 仅同步设置偏好，用于临时保留的 CaptureBatch.solveMode 字段。 */
    val mode: SolveMode = SolveMode.DETAILED,
    /** 本次拍题已收集的照片（已裁剪，按拍摄顺序）。 */
    val photos: List<String> = emptyList(),
    /** 待裁剪的照片队列；第一张就是裁剪界面正在处理的那张。 */
    val cropQueue: List<String> = emptyList(),
    val note: String = "",
    val confirmOpen: Boolean = false,
    val message: String? = null,
) {
    val count: Int get() = photos.size + cropQueue.size
    val canAddMore: Boolean get() = count < CaptureStore.MAX_PHOTOS
}

/**
 * 取景页：照片收集（连拍、相册多选、逐张裁剪）；解题偏好只读取设置。
 *
 * 状态写进 [SavedStateHandle]：拍照跳系统相机、部分 ROM 写屏幕方向时重建 Activity，
 * 照片队列都不能丢 —— 照片是现拍的，丢了只能重拍。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val credentials: AiCredentialStore,
    private val settings: SettingsRepository,
    private val captureStore: CaptureStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(
            photos = savedState.get<ArrayList<String>>(KEY_PHOTOS).orEmpty(),
            cropQueue = savedState.get<ArrayList<String>>(KEY_CROP).orEmpty(),
            note = savedState.get<String>(KEY_NOTE).orEmpty(),
            confirmOpen = savedState.get<Boolean>(KEY_CONFIRM) ?: false,
        ),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.catch { /* 配置读取失败时保留标准解答默认值，照片仍可使用。 */ }
                .collect { prefs -> _state.update { it.copy(mode = prefs.defaultSolveMode) } }
        }
        refreshModels()
    }

    /** 从设置页回来要重读：用户可能刚加了模型或换了识题模型。 */
    fun refreshModels() {
        viewModelScope.launch {
            val chip = withContext(io) { readChip() }
            _state.update { it.copy(chip = chip, modelsLoaded = true) }
        }
    }

    private fun readChip(): ModelChip? {
        val active = credentials.activeProfile()?.takeIf { it.hasApiKey } ?: return null
        val recognizer = if (active.visionEnabled) {
            null
        } else {
            credentials.questionVisionProfileId()?.let { id -> credentials.profiles().firstOrNull { it.id == id }?.name }
        }
        return ModelChip(active.name, recognizer, canReadPhotos = active.visionEnabled || recognizer != null)
    }

    fun newCameraFile(): File = captureStore.newCameraFile()

    fun setNote(note: String) = update { it.copy(note = note) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    // ── 收集照片 ──

    /** 系统相机 / CameraX 拍好的一张：先进裁剪队列。 */
    fun onCaptured(path: String) {
        if (!state.value.canAddMore) {
            File(path).delete()
            showMessage("一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片")
            return
        }
        update { it.copy(cropQueue = it.cropQueue + path) }
    }

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = CaptureStore.MAX_PHOTOS - state.value.count
        if (room <= 0) {
            showMessage("一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片")
            return
        }
        viewModelScope.launch {
            val imported = try {
                captureStore.importFromGallery(uris.take(room))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "照片导入失败")
                return@launch
            }
            if (uris.size > room) showMessage("一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片，多出的没有加入")
            update { it.copy(cropQueue = it.cropQueue + imported) }
        }
    }

    /** 裁剪界面「确定 / 使用原图」：队首照片进入照片条；队列空了就打开确认面板。 */
    fun onCropDone() = update { current ->
        val head = current.cropQueue.firstOrNull() ?: return@update current
        PhotoArrivals.markNew(listOf(head))
        val rest = current.cropQueue.drop(1)
        current.copy(photos = current.photos + head, cropQueue = rest, confirmOpen = current.confirmOpen || rest.isEmpty())
    }

    /** 裁剪界面「重拍」：丢掉队首这张，回到取景。 */
    fun onRetake() {
        val head = state.value.cropQueue.firstOrNull() ?: return
        update { it.copy(cropQueue = it.cropQueue.drop(1)) }
        viewModelScope.launch { captureStore.discard(listOf(head)) }
    }

    fun removePhoto(path: String) {
        update { it.copy(photos = it.photos - path, confirmOpen = it.confirmOpen && it.photos.size > 1) }
        viewModelScope.launch { captureStore.discard(listOf(path)) }
    }

    fun openConfirm() = update { it.copy(confirmOpen = it.photos.isNotEmpty()) }

    /** 关掉确认面板但保留照片：用户可以继续连拍，计数还在。 */
    fun closeConfirm() = update { it.copy(confirmOpen = false) }

    /** 放弃这次拍题：删掉还没交出去的照片。 */
    fun discardAll() {
        val all = state.value.photos + state.value.cropQueue
        update { it.copy(photos = emptyList(), cropQueue = emptyList(), note = "", confirmOpen = false) }
        viewModelScope.launch { captureStore.discard(all) }
    }

    /**
     * 「使用照片」：交给调用方的待发附件区，文件由调用方接管，不能删。
     * CaptureBatch 暂时保留设置中的 solveMode；本操作只交接照片，不发送请求。
     * 没有照片或尚有照片待裁剪时返回 null。
     */
    fun takeBatch(): CaptureBatch? {
        val current = state.value
        if (current.photos.isEmpty() || current.cropQueue.isNotEmpty()) return null
        update { it.copy(photos = emptyList(), cropQueue = emptyList(), note = "", confirmOpen = false) }
        return CaptureBatch(current.photos, current.mode, current.note.trim())
    }

    private inline fun update(transform: (HomeUiState) -> HomeUiState) {
        _state.update(transform)
        val now = _state.value
        savedState[KEY_PHOTOS] = ArrayList(now.photos)
        savedState[KEY_CROP] = ArrayList(now.cropQueue)
        savedState[KEY_NOTE] = now.note
        savedState[KEY_CONFIRM] = now.confirmOpen
    }

    private companion object {
        const val KEY_PHOTOS = "capture_photos"
        const val KEY_CROP = "capture_crop_queue"
        const val KEY_NOTE = "capture_note"
        const val KEY_CONFIRM = "capture_confirm"
    }
}
