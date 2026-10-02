package com.moge.app.runtime

import android.content.Context
import android.util.Log
import com.moge.app.core.IoDispatcher
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.ui.capture.CaptureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 回收没人引用的照片和图表（标记-清除）。
 *
 * 删题只删数据库：同一张照片可能被别的题目、草稿或还在确认面板里的这一批照片共用，
 * 删题当下判断不了。这里换个方向，先收集**所有仍被引用**的路径（消息、封面、请求附件、草稿），
 * 再删掉目录里不在名单上、且足够旧的文件。
 *
 * 照片的宽限期是 [PHOTO_GRACE_MS]：取景页的照片条和裁剪队列只存在 SavedStateHandle 里，
 * 这里看不到，靠时间保护它们。图表只由已落库的回答引用，生成中新画的文件也很新，
 * 宽限期可以短得多。
 */
@Singleton
class AttachmentJanitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversations: ConversationRepository,
    private val drafts: DraftStore,
    private val notebook: NotebookRepository,
    private val plots: PlotImageStore,
    private val diagrams: DiagramImageStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    /**
     * 返回删掉的字节数；[dryRun] 时只统计、不删（设置页先报能释放多少再让用户确认）。
     * 引用扫描失败返回 null：名单不完整时绝不删文件，也不报一个误导人的数字。
     */
    suspend fun sweep(
        now: Long = System.currentTimeMillis(),
        photoGraceMs: Long = PHOTO_GRACE_MS,
        figureGraceMs: Long = FIGURE_GRACE_MS,
        dryRun: Boolean = false,
    ): Long? = mutex.withLock {
        withContext(io) {
            val referenced = try {
                // 先草稿后数据库：发送时消息先落库、草稿后清，按这个顺序扫，照片总会在其中一边被看到。
                val fromDrafts = drafts.referencedPhotoPaths()
                fromDrafts + conversations.referencedImagePaths() + notebook.referencedImagePaths()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "skip sweep: reference scan failed", e)
                return@withContext null
            }
            val canonical = referenced.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
            val names = referenced.map { File(it).name }.toSet()
            val photoCutoff = now - photoGraceMs
            val figureCutoff = now - figureGraceMs
            sweepDir(File(context.filesDir, CaptureStore.PHOTOS_DIR), canonical, photoCutoff, dryRun) +
                sweepDir(File(context.filesDir, DraftStore.ATTACHMENTS_DIR), canonical, photoCutoff, dryRun) +
                plots.retainOnly(names, figureCutoff, dryRun) +
                diagrams.retainOnly(names, figureCutoff, dryRun)
        }
    }

    private fun sweepDir(dir: File, keep: Set<String>, cutoff: Long, dryRun: Boolean): Long {
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() >= cutoff) return@forEach
            if (runCatching { file.canonicalPath }.getOrNull() in keep) return@forEach
            val size = file.length()
            if (dryRun || file.delete()) freed += size
        }
        return freed
    }

    companion object {
        private const val TAG = "AttachmentJanitor"
        const val PHOTO_GRACE_MS = 24L * 60 * 60 * 1000
        const val FIGURE_GRACE_MS = 10L * 60 * 1000
    }
}
