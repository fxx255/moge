package com.moge.app.ui.solve

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationViewportTest {
    @Test fun `separate conversations keep independent anchors after owner recreation and parcel roundtrip`() {
        val saved = SavedStateHandle()
        val first = ConversationViewports(saved)
        first.save("a", ConversationViewport("answer-a", 8, 173))
        first.save("b", ConversationViewport("question-b", 4, 37))
        val bundle = Bundle().apply { putBundle("conversationViewports", saved.get("conversationViewports")) }
        bundle.putStringArrayList("conversationViewportOrder", saved.get("conversationViewportOrder"))
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
            val second = ConversationViewports(SavedStateHandle(mapOf(
                "conversationViewports" to restored.getBundle("conversationViewports"),
                "conversationViewportOrder" to restored.getStringArrayList("conversationViewportOrder"),
            )))
            assertEquals(ConversationViewport("answer-a", 8, 173), second.read("a"))
            assertEquals(ConversationViewport("question-b", 4, 37), second.read("b"))
            assertNull(second.read("new-conversation"))
        } finally { parcel.recycle() }
    }
    @Test fun `message identity survives inserted history and deleted anchor uses clamped index`() {
        val anchor = ConversationViewport("answer", 1, 91)
        assertEquals(3, anchor.indexIn(listOf("earlier", "intro", "question", "answer", "followup")))
        assertEquals(1, anchor.indexIn(listOf("question", "replacement")))
        assertEquals(0, anchor.indexIn(listOf("only-message")))
    }
    @Test fun `empty loading snapshot cannot overwrite an existing saved anchor`() {
        val store = ConversationViewports(SavedStateHandle())
        store.save("a", ConversationViewport("answer", 10, 201))
        store.save("a", ConversationViewport("", 0, 0))
        assertEquals(ConversationViewport("answer", 10, 201), store.read("a"))
    }
}
