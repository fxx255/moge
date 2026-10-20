package com.moge.app.imaging

import android.util.Log
import com.moge.app.core.IoDispatcher
import com.moge.app.data.image.writePhotoBitmap
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.book.BookPageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

enum class PhotoPreparationStage { PROCESSING }
enum class PhotoPreparationResult { PROCESSED, SKIPPED, ORIGINAL_RETAINED }

/** Only newly acquired, private photos enter here, before user cropping or draft display. */
@Singleton
class QuestionPhotoPreprocessor @Inject internal constructor(
    private val settings: SettingsRepository,
    private val pages: BookPageProcessor,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    // Bound native memory across camera and gallery batches, including rapid navigation.
    private val processing = Mutex()

    suspend fun prepare(
        path: String,
        mode: ConversationMode,
        onStage: (PhotoPreparationStage) -> Unit = {},
    ): PhotoPreparationResult {
        if (mode != ConversationMode.SOLVE) return PhotoPreparationResult.SKIPPED
        return try {
            if (!settings.current().questionPhotoPreprocessing) {
                return PhotoPreparationResult.SKIPPED
            }
            processing.withLock {
                withContext(io) {
                    coroutineContext.ensureActive()
                    onStage(PhotoPreparationStage.PROCESSING)
                    val rendered = pages.render(path)
                    try {
                        coroutineContext.ensureActive()
                        writePhotoBitmap(path, rendered)
                    } finally { rendered.recycle() }
                    PhotoPreparationResult.PROCESSED
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("MogePhoto", "Question preprocessing unavailable; keeping original", error)
            PhotoPreparationResult.ORIGINAL_RETAINED
        } catch (error: LinkageError) {
            Log.w("MogePhoto", "Native image processing unavailable; keeping original", error)
            PhotoPreparationResult.ORIGINAL_RETAINED
        } catch (error: OutOfMemoryError) {
            Log.w("MogePhoto", "Image processing memory budget exceeded; keeping original", error)
            PhotoPreparationResult.ORIGINAL_RETAINED
        }
    }
}
