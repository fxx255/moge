package com.moge.app.ui.capture

import android.content.Context
import android.net.Uri
import com.moge.app.domain.SolveMode
import com.moge.app.ui.photo.importPhoto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 取景 → 裁剪 → 确认面板 → 解题页之间传递的一批照片。
 * 经导航参数交给解题页（见 [com.moge.app.ui.Routes.solveCaptured]），进程被回收后 SavedStateHandle 仍能还原。
 */
data class CaptureBatch(
    val photoPaths: List<String>,
    val solveMode: SolveMode,
    val note: String = "",
)

/**
 * 拍题照片的文件管理。照片一律落在 `filesDir/photos/`（不是 cacheDir）：裁剪是原地改写，
 * 确认面板停留期间进程被回收也不会丢图；提交时 [com.moge.app.runtime.DraftStore.persistAttachments]
 * 看到它已在私有目录内，会原样复用而不再复制。
 */
@Singleton
class CaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun photosDir(): File = File(context.filesDir, PHOTOS_DIR).apply { mkdirs() }

    /** 相机拍照的目标文件（还没写入内容）。 */
    fun newCameraFile(): File = File(photosDir(), "cam_${UUID.randomUUID()}.jpg")

    /** 相册选图：复制进私有目录，返回绝对路径。任何一张失败都向上抛。 */
    suspend fun importFromGallery(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.map { importPhoto(context, it, photosDir(), "pick").absolutePath }
    }

    /** 用户删掉的照片：只删本目录里、确实没被提交过的文件。 */
    suspend fun discard(paths: Collection<String>) = withContext(Dispatchers.IO) {
        val root = photosDir().canonicalPath + File.separator
        paths.map(::File).filter { it.canonicalPath.startsWith(root) }.forEach { it.delete() }
    }

    companion object {
        const val PHOTOS_DIR = "photos"

        /** 一道题最多 9 张照片（与解题请求的附件上限一致）。 */
        const val MAX_PHOTOS = 9
    }
}
