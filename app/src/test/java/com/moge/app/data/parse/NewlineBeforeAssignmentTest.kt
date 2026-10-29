package com.moge.app.data.parse

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class NewlineBeforeAssignmentTest {
    @Test fun jsonNewlineBeforeErrorVectorIsNotAnInequality() {
        for (spacing in listOf("", " ", "  ")) {
            val body = "最可能错误图样为\n\n\$\$\ne${spacing}=0000010,\$\$\n即第6位出错。"
            val raw = buildJsonObject { put("reply", body); put("answer", body) }.toString()
            val parsed = ReplyParser.parse(raw, normalizeMarkdown = false)
            assertEquals(body, parsed.reply)
            assertEquals(body, parsed.finalAnswer)
        }
    }

    @Test fun everyStreamBoundaryAgreesWithTheJsonNewlineAssignment() {
        for (spacing in listOf("", " ", "  ")) {
            val body = "\$\$\ne${spacing}=0000010,\$\$"
            val raw = buildJsonObject { put("reply", body); put("answer", body) }.toString()
            for (key in listOf("reply", "answer")) {
                for (split in 0..raw.length) {
                    val decoder = IncrementalReplyDecoder(key)
                    val shown = decoder.append(raw.take(split)) + decoder.append(raw.drop(split))
                    assertEquals("key=$key split=$split", body, decoder.finish())
                    assertEquals(body, shown)
                    assertFalse(shown.contains("\\ne"))
                }
            }
        }
    }

    @Test fun genuineLatexNCommandsAndExplicitlyEscapedBackslashesArePreserved() {
        for (body in listOf("\$\$x\\ne 0\$\$", "\$\$x\\neq e\$\$", "\$\$\\nu=2\$\$",
            "\$\$\\nabla f\$\$", "\$\$x\\notin A\$\$", "\$\$\\ne=0\$\$")) {
            val raw = buildJsonObject { put("reply", JsonPrimitive(body)) }.toString()
            assertEquals(body, ReplyParser.parse(raw, normalizeMarkdown = false).reply)
            assertEquals(body, decodeReplyIncremental(raw))
        }
        val delimiter = "\$\$"
        val loose = """{"reply":"${delimiter}x\ne 0, x\neq e, \nu=2, \nabla f, x\notin A$delimiter"}"""
        val expected = """${delimiter}x\ne 0, x\neq e, \nu=2, \nabla f, x\notin A$delimiter"""
        assertEquals(expected, ReplyParser.parse(loose, normalizeMarkdown = false).reply)
        assertEquals(expected, decodeReplyIncremental(loose))
    }

    @Test fun newlineAssignmentAlsoSurvivesLooseLatexAndTruncatedJson() {
        val delimiter = "\$\$"
        val raw = """{"reply":"${delimiter}\ne =0000010,\quad c=0110010.$delimiter","answer":"${delimiter}\ne=0000010$delimiter"}"""
        val expected = "$delimiter\ne =0000010,\\quad c=0110010.$delimiter"
        assertEquals(expected, ReplyParser.parse(raw, normalizeMarkdown = false).reply)
        val short = raw.substringBefore(",\"answer\"")
        assertEquals(expected, ReplyParser.parse(short, normalizeMarkdown = false).reply)
        val decoder = IncrementalReplyDecoder()
        for (char in short) decoder.append(char.toString())
        assertEquals(expected, decoder.finish())
    }

    @Test fun spacesAfterARealInequalityAndEndOfInputRetainTheirOrder() {
        for (body in listOf("x\\ne   y", "x\\ne ", "\\ne", "\\ne  ")) {
            val raw = """{"reply":"${body.replace("\\\\", "\\")}"}"""
            assertEquals(body, decodeReplyIncremental(raw))
        }
        val decoder = IncrementalReplyDecoder()
        decoder.append("""{"reply":"x\ne  """)
        decoder.reset()
        decoder.append("""{"reply":"$$\ne=0000010$$"}""")
        assertEquals("\$\$\ne=0000010\$\$", decoder.finish())
    }

    @Test fun oldSavedFormulaOpenersRepairWithoutRewritingRealRelationsOrCode() {
        for (spacing in listOf("", " ", "\n")) {
            val old = "最可能错误图样为\n\n\$\$$spacing\\ne =0000010,\$\$\n即第6位出错。"
            val prepared = prepareReplyMarkdown(old)
            assertFalse(prepared, prepared.contains("\\ne ="))
            assertTrue(prepared, prepared.contains("e =0000010,"))
            assertEquals(prepared, prepareReplyMarkdown(prepared))
        }
        val protected = "\$\$x\\ne 0\$\$，\$\$x\\neq e\$\$，\$\$\\nu=2\$\$\n\n" +
            "`\$\$\\ne=1\$\$`\n\n```latex\n\$\$\\ne=1\$\$\n```\n\n" +
            "    \$\$\\ne=1\$\$\n\n\$\$x\$\$\\ne=1\n\n\$\$\\text{\\ne=1}\$\$"
        assertEquals(protected, prepareReplyMarkdown(protected))
    }
}
