package com.moge.app.ui.solve

import com.moge.app.data.parse.normalizeReplyMarkdown
import org.junit.Assert.*
import org.junit.Test

class UserMathFormattingTest {
    @Test fun all_four_delimiters_use_shared_normalization_and_keep_original_source() {
        val raw = "计算 \$x^2\$、\$\$y_1\$\$、\\(\\frac{1}{2}\\) 和 \\[\\int_0^1 x dx\\]。"
        val result = userMathSource(raw)!!
        assertEquals(raw, result.renderSource)
        assertEquals(listOf("x^2", "y_1", "\\frac{1}{2}", "\\int_0^1 x dx"), result.formulas)
        assertFalse(result.incomplete)
        assertEquals(normalizeReplyMarkdown(raw), normalizeReplyMarkdown(result.renderSource))
    }

    @Test fun multiline_display_delimiters_are_owned_by_the_normalizer() {
        val raw = "题目：\n\\[\n\\frac{1}{2}\n\\]"
        val result = userMathSource(raw)!!
        assertEquals(raw, result.renderSource)
        assertEquals(listOf("\\frac{1}{2}"), result.formulas)
        assertFalse(result.incomplete)
    }

    @Test fun standalone_pastes_get_only_a_display_wrapper() {
        for (formula in listOf("\\frac{1}{2}", "x^2", "y=x^2+1", "\\int_0^1 x^2 dx", "\\sqrt{2}", "\\alpha + \\beta")) {
            val raw = "  $formula\n"
            val result = userMathSource(raw)!!
            assertEquals("\$\$\n$formula\n\$\$", result.renderSource)
            assertEquals(listOf(formula), result.formulas)
            assertFalse(formula, result.incomplete)
            assertEquals("  $formula\n", raw)
        }
    }

    @Test fun prose_currency_paths_and_code_do_not_create_a_preview() {
        val tick = 96.toChar()
        val dollar = '$'
        for (raw in listOf(
            "", "普通问题", "What is an integral?", "integral", "x", "a + b", "2 + 2", "file_name",
            "价格 \$5 到 \$10 之间", "Cost \$5 and \$10", "\$5", "\$100.00",
            "C:\\temp\\x^2.txt", "C:\\frac{1}{2}", "/tmp/x^2", "folder/x^2", "\\frac\\notes",
            "代码 $tick\$x^2\$$tick", "$tick\\frac{1}{2}$tick",
            "$tick$tick$tick\n\$\$x^2\$\$\n$tick$tick$tick",
            "~~~latex\n\\(x^2\\)\n~~~", "\\" + dollar + "x^2\\" + dollar,
            "请计算 \\frac{1}{2}", "The expression x^2 needs work",
        )) assertNull(raw, userMathSource(raw))
    }

    @Test fun incomplete_formulas_are_recognized_without_synthesizing_a_closing_delimiter() {
        for (raw in listOf("\$x", "\$x^", "\$\$\\frac{1}{", "\\(x^2", "\\[\\int_0^1", "\\frac{1}{", "x^")) {
            val result = userMathSource(raw)!!
            assertTrue(raw, result.incomplete)
            if (raw.startsWith('$') || raw.startsWith("\\(") || raw.startsWith("\\[")) {
                assertEquals(raw, result.renderSource)
            }
        }
    }

    @Test fun closed_but_unfinished_braces_stay_in_source_mode() {
        val result = userMathSource("\$\$\\frac{1}{2\$\$")!!
        assertTrue(result.incomplete)
        assertEquals(listOf("\\frac{1}{2"), result.formulas)
    }

    @Test fun invalid_closed_latex_is_left_for_the_existing_renderer_fallback() {
        val raw = "\$\$\\unknownMogeCommand{1}\$\$"
        val result = userMathSource(raw)!!
        assertEquals(raw, result.renderSource)
        assertFalse(result.incomplete)
    }

    @Test fun numeric_delimited_formulas_and_repeated_backslashes_keep_normalizer_behavior() {
        val raw = "取 \$0\$ 和 \$1\$。\$\$ \\\\frac{1}{2} \$\$"
        val result = userMathSource(raw)!!
        assertEquals(listOf("0", "1", "\\frac{1}{2}"), result.formulas)
        assertEquals(raw, result.renderSource)
    }

    @Test fun code_and_currency_before_real_math_are_not_counted_as_formulas() {
        val tick = 96.toChar()
        val raw = "\$5 到 \$10，代码 $tick\$x^2\$$tick，求 \\(y^2\\)。"
        assertEquals(listOf("y^2"), userMathSource(raw)!!.formulas)
    }

    @Test fun preview_budget_does_not_truncate_display_or_modify_the_input() {
        val raw = "\$x^2\$\n" + "原文\n".repeat(4000)
        val preview = userMathSource(raw, COMPOSER_PREVIEW_CHAR_LIMIT)!!
        assertTrue(preview.truncated)
        assertEquals(raw.take(COMPOSER_PREVIEW_CHAR_LIMIT), preview.renderSource)
        val display = userMathSource(raw)!!
        assertFalse(display.truncated)
        assertEquals(raw, display.renderSource)
        assertTrue(raw.length > COMPOSER_PREVIEW_CHAR_LIMIT)
    }

    @Test fun truncation_inside_a_formula_is_incomplete_instead_of_parsed_as_closed() {
        val raw = "\$\$" + "x+".repeat(COMPOSER_PREVIEW_CHAR_LIMIT) + "1\$\$"
        val preview = userMathSource(raw, COMPOSER_PREVIEW_CHAR_LIMIT)!!
        assertTrue(preview.truncated)
        assertTrue(preview.incomplete)
        assertTrue(preview.formulas.isEmpty())
    }

    @Test fun source_is_not_destructively_sanitized_for_display_detection() {
        val raw = "  \\(\\cancel{x} + \\frac12\\)\n"
        assertEquals(raw, userMathSource(raw)!!.renderSource)
    }
}
