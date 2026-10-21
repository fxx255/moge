package com.moge.app.data.image

import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QuestionPhotoFilesTest {
    @Test fun `canonical worker updates reach private path observers without reloading unrelated photos`() = runBlocking {
        val suffix = "com.moge.app/files/${UUID.randomUUID()}.png"
        val observed = "/data/user/0/$suffix"
        val canonical = "/data/data/$suffix"
        val revisions = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { QuestionPhotoFiles.observeRevision(observed).take(2).toList() }
        }
        QuestionPhotoFiles.published(canonical)
        assertEquals(listOf(0L, 1L), revisions.await())
        assertEquals(1L, QuestionPhotoFiles.revision(observed))
        assertEquals(1L, QuestionPhotoFiles.revision(canonical))
        assertEquals(0L, QuestionPhotoFiles.revision("/data/user/10/$suffix"))
        assertEquals(0L, QuestionPhotoFiles.revision("$observed.other"))
        QuestionPhotoFiles.published(observed)
        assertEquals(2L, QuestionPhotoFiles.revision(canonical))
    }
}
