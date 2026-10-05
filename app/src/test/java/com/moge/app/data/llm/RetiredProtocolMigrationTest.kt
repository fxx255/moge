package com.moge.app.data.llm

import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.migrateRetiredProtocols
import com.moge.app.ui.settings.PROVIDER_PRESETS
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RetiredProtocolMigrationTest {
    @Test fun `retired profile fields migrate without losing keys or neighboring profiles`() {
        val raw = """[
            {"id":"old","apiKey":"saved-test-key","apiProtocol":"ANTHROPIC_MESSAGES","searchProtocol":"ANTHROPIC","nativePdfEnabled":true},
            {"id":"other","apiKey":"other-test-key","apiProtocol":"RESPONSES","searchProtocol":"RESPONSES"}
        ]"""
        val result = migrateRetiredProtocols(Json.parseToJsonElement(raw)).jsonArray
        assertEquals(2, result.size)
        val migrated = result.first().jsonObject
        assertEquals("saved-test-key", migrated["apiKey"]!!.jsonPrimitive.content)
        assertEquals("CHAT_COMPLETIONS", migrated["apiProtocol"]!!.jsonPrimitive.content)
        assertEquals("CHAT_COMPLETIONS", migrated["searchProtocol"]!!.jsonPrimitive.content)
        assertFalse(migrated.containsKey("nativePdfEnabled"))
        assertEquals(Json.parseToJsonElement(raw).jsonArray[1], result[1])
        assertEquals(listOf("CHAT_COMPLETIONS", "RESPONSES"), AiApiProtocol.entries.map { it.name })
        assertFalse(PROVIDER_PRESETS.any { it.name == "Anthropic" })
    }

    @Test fun `old request snapshots remain resumable with tool based document reading`() {
        val snapshot = SnapshotCodec.decode("""{"model":"model","endpointIdentity":"https://gateway.example/v1",
            "protocol":"ANTHROPIC","apiProtocol":"ANTHROPIC_MESSAGES","nativePdfEnabled":true,
            "prepared":true,"sourceUserText":"继续分析","documentPaths":["/original.pdf"]}""")!!
        assertTrue(snapshot.isComplete)
        assertEquals("CHAT_COMPLETIONS", snapshot.toPolicy().apiProtocol)
        assertEquals("CHAT_COMPLETIONS", snapshot.toPolicy().searchProtocol)
        assertEquals(listOf("/original.pdf"), snapshot.documentPaths)
        assertEquals("继续分析", snapshot.sourceUserText)
        assertFalse(SnapshotCodec.encode(snapshot).contains("nativePdfEnabled"))
    }
}
