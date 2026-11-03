package com.moge.app.data.parse

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class NewlineBeforeExponentialTest {
    private val exponential = """e^{-j2\pi n t/T_s}=e^{-j2\pi n(u+kT_s)/T_s}=e^{-j2\pi n u/T_s}e^{-j2\pi nk}=e^{-j2\pi n u/T_s},"""
    private val correct = "指数部分则变为\n\n$$\n$exponential\n$$\n\n" +
        "最后一步用到了 \$\$n,k\$\$ 都是整数，所以 \$\$e^{-j2\\pi nk}=1\$\$。"

    @Test fun completeAndTruncatedJsonKeepTheScreenshotsLeadingExponential() {
        val raw = buildJsonObject { put("reply", correct); put("answer", correct) }.toString()
        val parsed = ReplyParser.parse(raw, normalizeMarkdown = false)
        assertEquals(correct, parsed.reply)
        assertEquals(correct, parsed.finalAnswer)
        assertEquals(correct, ReplyParser.parse(raw.dropLast(2), normalizeMarkdown = false).reply)

        // Recover loose LaTeX as well as the provider's correctly escaped JSON.
        val loose = raw.replace("\\\\pi", "\\pi")
        assertEquals(correct, ReplyParser.parse(loose, normalizeMarkdown = false).reply)
        assertEquals(correct, decodeReplyIncremental(loose))
    }

    @Test fun everyStreamBoundaryKeepsTheExponentialForAnswerAndReplyWithoutFlashingAnInequality() {
        val raw = buildJsonObject { put("reply", correct); put("answer", correct) }.toString()
        for (key in listOf("reply", "answer")) {
            for (split in 0..raw.length) {
                val decoder = IncrementalReplyDecoder(key)
                val shown = decoder.append(raw.take(split)) + decoder.append(raw.drop(split))
                assertEquals("key=$key split=$split", correct, decoder.finish())
                assertEquals(correct, shown)
                assertFalse(shown.contains("\\ne^"))
            }
        }
        val decoder = IncrementalReplyDecoder()
        for (char in raw) {
            decoder.append(char.toString())
            assertFalse(decoder.text.contains("\\ne^"))
        }
        assertEquals(correct, decoder.finish())
    }

    @Test fun leadingPowersSurviveSpacesNewlinesAndEverySupportedFormulaOpener() {
        for ((open, close) in listOf("$$" to "$$", "$" to "$", "\\[" to "\\]", "\\(" to "\\)")) {
            for (spacing in listOf("", " ", "\n  ")) {
                val body = "$open${spacing}\ne ^{-x}$close"
                val raw = buildJsonObject { put("reply", body) }.toString()
                assertEquals(body, ReplyParser.parse(raw, normalizeMarkdown = false).reply)
                for (split in 0..raw.length) {
                    val decoder = IncrementalReplyDecoder()
                    decoder.append(raw.take(split))
                    decoder.append(raw.drop(split))
                    assertEquals("opener=$open split=$split", body, decoder.finish())
                }
            }
        }
    }

    @Test fun savedRepliesRepairAtRenderTimeWithoutChangingStoredText() {
        for (spacing in listOf("", " ", "\n", "\n    ")) {
            val old = "指数部分则变为\n\n$$$spacing\\n$exponential\n$$"
            val expected = old.replace("\\n$exponential", "\n$exponential")
            val prepared = prepareReplyMarkdown(old)
            assertEquals(expected, prepared)
            assertEquals(prepared, prepareReplyMarkdown(prepared))
            val raw = buildJsonObject { put("reply", old) }.toString()
            // An explicit JSON backslash stays literal in storage; presentation repairs it.
            assertEquals(old, ReplyParser.parse(raw, normalizeMarkdown = false).reply)
            assertEquals(expected, prepareReplyMarkdown(ReplyParser.parse(raw).reply))
            assertEquals(expected, prepareReplyMarkdown(decodeReplyIncremental(raw)))
        }
    }

    @Test fun inequalitiesCommandsCodeAndSuperscriptedRelationsRetainTheirOriginalMeaning() {
        val formulas = listOf(
            """x\ne y""", """x\ne^{\ast}y""", """\ne 0""", """\neq 0""",
            """\nu^2+\nabla f+\neg P""", """\text{literal \ne^{-x}}""",
        )
        for (formula in formulas) {
            val body = "$$$formula$$"
            val raw = buildJsonObject { put("reply", body) }.toString()
            assertEquals(body, ReplyParser.parse(raw, normalizeMarkdown = false).reply)
            assertEquals(body, decodeReplyIncremental(raw))
            assertEquals(body, prepareReplyMarkdown(body))
        }
        val delimiter = "$$"
        val loose = """{"reply":"${delimiter}x\ne^{\ast}y, x\ne y, \neq 0, \nu^2$delimiter"}"""
        val expected = """${delimiter}x\ne^{\ast}y, x\ne y, \neq 0, \nu^2$delimiter"""
        assertEquals(expected, ReplyParser.parse(loose, normalizeMarkdown = false).reply)
        assertEquals(expected, decodeReplyIncremental(loose))
        val code = "`\$\$\\ne^{-x}\$\$`\n\n```latex\n\$\$\\ne^{-x}\$\$\n```\n\n" +
            "    \$\$\\ne^{-x}\$\$"
        assertEquals(code, prepareReplyMarkdown(code))
    }
}
