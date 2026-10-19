package com.moge.app.imaging

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.moge.app.data.image.decodePhotoBitmap
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import javax.inject.Inject

internal class QuestionPhotoClassifier @Inject constructor() {
    suspend fun inspect(path: String): QuestionPhotoEvidence {
        val bitmap = decodePhotoBitmap(path, maxEdge = 2000) ?: error("无法读取照片")
        try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                // ML Kit cannot cancel a running recognition task. Keep its bitmap alive until
                // completion, then honor cancellation before touching the original file.
                val text = suspendCoroutine { continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                        .addOnCanceledListener { continuation.resumeWithException(CancellationException("题目识别已取消")) }
                }
                coroutineContext.ensureActive()
                val regions = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let {
                        TextRegion(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height,
                            it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height)
                    }
                }
                return QuestionPhotoEvidence(text.text, regions)
            } finally { recognizer.close() }
        } finally {
            bitmap.recycle()
        }
    }
}
