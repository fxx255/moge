package com.moge.app.imaging

import android.util.Log
import com.moge.app.core.IoDispatcher
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.PreparedPhotoCrop
import com.moge.app.data.image.decodeEditedPhoto
import com.moge.app.data.image.writePhotoFileAtomically
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.book.BookPageProcessor
import com.moge.app.imaging.book.writeBookPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import java.io.File

enum class PhotoPreparationResult { PROCESSED, SKIPPED, ORIGINAL_RETAINED }

/** Cleans an immutable cropped source into a separate presentation file; never alters model input. */
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
        expectedFingerprint: String? = null,
        reusableCrop: PreparedPhotoCrop? = null,
        publishChange: Boolean = true,
    ): PhotoPreparationResult {
        if (mode != ConversationMode.SOLVE) return PhotoPreparationResult.SKIPPED
        return try {
            if (!settings.current().questionPhotoPreprocessing) {
                return PhotoPreparationResult.SKIPPED
            }
            processing.withLock {
                withContext(io) {
                    coroutineContext.ensureActive()
                    val fingerprint = QuestionPhotoFiles.fingerprint(path)
                        ?: return@withContext PhotoPreparationResult.ORIGINAL_RETAINED
                    if (expectedFingerprint != null && expectedFingerprint != fingerprint) {
                        return@withContext PhotoPreparationResult.SKIPPED
                    }
                    val destination = QuestionPhotoFiles.output(path, fingerprint)
                    if (destination.isFile && destination.length() > 0) {
                        return@withContext PhotoPreparationResult.PROCESSED
                    }
                    val prepared = reusableCrop?.preparedFile()
                    if (prepared != null && reusableCrop.edit.isIdentity) {
                        // Keeping the entire photo can reuse the encoded result without another decode.
                        writePhotoFileAtomically(destination.path) { prepared.copyTo(it, overwrite = true) }
                    } else {
                        val reused = if (prepared != null) runCatching { decodeEditedPhoto(prepared.path, reusableCrop.edit) }.getOrNull() else null
                        val rendered = reused ?: pages.render(path)
                        try {
                            coroutineContext.ensureActive()
                            if (QuestionPhotoFiles.fingerprint(path) != fingerprint) {
                                return@withContext PhotoPreparationResult.SKIPPED
                            }
                            writeBookPhoto(destination.path, rendered)
                        } finally { rendered.recycle() }
                    }
                    coroutineContext.ensureActive()
                    if (QuestionPhotoFiles.fingerprint(path) != fingerprint) {
                        destination.delete()
                        return@withContext PhotoPreparationResult.SKIPPED
                    }
                    if (publishChange) QuestionPhotoFiles.published(path)
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

private fun PreparedPhotoCrop.preparedFile(): File? = runCatching {
    if (QuestionPhotoFiles.fingerprint(source) != fingerprint) return@runCatching null
    QuestionPhotoFiles.output(source, fingerprint).takeIf { it.isFile && it.length() > 0 }
}.getOrNull()
