package com.moge.app.ui.settings

import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiSearchProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileDraftTest {

    private fun draft(
        name: String = "DeepSeek",
        baseUrl: String = "https://api.deepseek.com",
        model: String = "deepseek-chat",
        apiKey: String = "sk-test",
    ) = ModelProfileDraft(name, baseUrl, model, apiKey, false, AiSearchProtocol.RESPONSES, AiReasoningEffort.LOW)

    private fun profile(id: String, vision: Boolean, name: String = id) = AiModelProfile(
        id, name, "https://x", "m-$id", vision, AiSearchProtocol.OFF, AiReasoningEffort.LOW, true,
    )

    @Test
    fun baseUrlAcceptsHttpAndHttpsOnly() {
        assertNull(baseUrlError("https://api.deepseek.com"))
        assertNull(baseUrlError("  http://192.168.1.2:8000/v1 "))
        assertEquals("请填写接口地址", baseUrlError("   "))
        assertNotNull(baseUrlError("api.deepseek.com"))
        assertNotNull(baseUrlError("ftp://example.com"))
        assertNotNull(baseUrlError("https://"))
    }

    @Test
    fun cleartextDetection() {
        assertTrue(isCleartextUrl("HTTP://10.0.0.2/v1"))
        assertFalse(isCleartextUrl("https://api.openai.com/v1"))
    }

    @Test
    fun validateRequiresKeyOnlyWhenNoneStored() {
        assertNull(validateDraft(draft(), hasStoredKey = false))
        assertEquals("请填写 API 密钥", validateDraft(draft(apiKey = ""), hasStoredKey = false))
        assertNull(validateDraft(draft(apiKey = ""), hasStoredKey = true))
        assertNull(validateDraft(draft(name = " "), hasStoredKey = true))
        assertEquals("请填写模型名", validateDraft(draft(model = ""), hasStoredKey = true))
        assertNotNull(validateDraft(draft(baseUrl = "deepseek"), hasStoredKey = true))
    }

    @Test
    fun blankNameFallsBackToModel() {
        assertEquals("deepseek-chat", effectiveName(draft(name = " ", model = " deepseek-chat ")))
        assertEquals("我的", effectiveName(draft(name = " 我的 ")))
    }

    @Test
    fun photoRouteFollowsPreparerRules() {
        val text = profile("text", vision = false)
        val eye = profile("eye", vision = true)
        assertEquals("还没有模型，先添加一个。", photoRouteHint(emptyList(), null, null))
        assertTrue(photoRouteHint(listOf(eye), "eye", null).contains("直接把照片发给它"))
        assertTrue(photoRouteHint(listOf(text), "text", null).contains("只能文字提问"))
        assertTrue(photoRouteHint(listOf(text, eye), "text", "eye").startsWith("先由「eye」"))
        // 指定的识题模型后来关掉了多模态：不能再当识题模型
        assertTrue(photoRouteHint(listOf(text, profile("eye", false)), "text", "eye").contains("只能文字提问"))
        // 活动 id 失效时回退到第一个配置，与 AiCredentialStore 一致
        assertTrue(photoRouteHint(listOf(eye), "gone", null).contains("「eye」可看图"))
    }

    @Test
    fun summaryAndBytes() {
        val p = AiModelProfile("a", "A", "u", "deepseek-chat", true, AiSearchProtocol.CHAT_COMPLETIONS, AiReasoningEffort.HIGH, true)
        assertEquals("deepseek-chat · 可看图 · Chat Completions · 允许联网 · 高思考", profileSummary(p))
        assertTrue(profileSummary(p.copy(searchEnabled = false)).contains("Chat Completions · 不联网"))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
    }
}
