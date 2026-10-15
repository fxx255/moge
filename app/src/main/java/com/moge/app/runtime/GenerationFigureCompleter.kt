package com.moge.app.runtime

import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.RequestPolicy
import com.moge.app.data.llm.UsageSample
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.findFigureAnchors
import com.moge.app.data.parse.orderedFigures
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

/** The original request identity and question are also used for a missing figure. */
data class FigureCompletionContext(
    val history: List<ChatMessage>,
    val imageBase64s: List<String>,
    val solveMode: SolveMode,
    val policy: RequestPolicy?,
    val onUsage: (UsageSample?) -> Unit,
)

/** Render once, then repair only referenced empty slots without rewriting the answer. */
@Singleton
class GenerationFigureCompleter @Inject constructor(
    private val renderer: FigureRenderer,
    private val modelClient: ModelClient,
) {
    suspend fun complete(
        text: String,
        finalAnswer: String,
        figures: List<ReplyFigure>,
        context: FigureCompletionContext?,
    ): List<String> {
        val initial = render(figures)
        currentCoroutineContext().ensureActive()
        if (context == null) return initial
        val references = (findFigureAnchors(text) + findFigureAnchors(finalAnswer))
            .mapNotNull { it.number?.takeIf { n -> n in 1..MAX_FIGURE_NUMBER } }.distinct()
        val paths = MutableList(maxOf(initial.size, references.maxOrNull() ?: 0)) { initial.getOrNull(it).orEmpty() }
        for (number in references) {
            if (paths[number - 1].isNotBlank()) continue
            currentCoroutineContext().ensureActive()
            // A temporary render/write failure can be retried locally from the same spec.
            val original = figures.getOrNull(number - 1)
            if (original != null && original != ReplyFigure.Missing) {
                paths[number - 1] = render(listOf(original)).single()
                if (paths[number - 1].isNotBlank()) continue
            }
            try {
                val description = figureContext(text, number).ifBlank { figureContext(finalAnswer, number) }
                val repaired = modelClient.chatStreaming(
                    messages = context.history + listOf(
                        ChatMessage("assistant", description),
                        ChatMessage("user", instruction(number)),
                    ),
                    imageBase64s = context.imageBase64s,
                    solveMode = context.solveMode,
                    webSearchEnabled = false,
                    forceWebSearch = false,
                    onUsage = context.onUsage,
                    policy = context.policy,
                    onEvent = {},
                ).orderedFigures().singleOrNull()?.takeUnless { it == ReplyFigure.Missing }
                currentCoroutineContext().ensureActive()
                if (repaired != null) paths[number - 1] = render(listOf(repaired)).single()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One targeted request per missing figure. A broken stream is not replayed;
                // the original answer and every successful image remain available.
            }
        }
        currentCoroutineContext().ensureActive()
        return paths
    }

    private suspend fun render(figures: List<ReplyFigure>): List<String> = try {
        val paths = renderer.render(figures)
        List(figures.size) { paths.getOrNull(it).orEmpty() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        figures.map { "" }
    }

    private fun instruction(number: Int) = """
        上一条解答中 [[FIGURE:$number]] 对应的图片未能生成。现在只补这一张图的结构化绘图参数。
        依据上面的原题和该图附近的讲解，保持数值、公式与模块含义一致，不重新批改或修改答案。
        只输出一个完整 JSON 对象，reply 和 answer 写空字符串；plots 与 diagrams 中仅一个数组含一项，另一个为空。
        曲线图提供 plots，框图提供 diagrams 的完整 nodes/edges；不要只输出图号、文字说明或模板名。
        这次返回的一项会直接替换原图 $number 的空槽，不要补其它图片，也不要重新编号原回答。
    """.trimIndent()

    companion object {
        // Match the generation manager's reservation for references in a resumed answer.
        private const val MAX_FIGURE_NUMBER = 512
    }
}

/** Keep the target's own explanation even when it occurs deep inside a long answer. */
internal fun figureContext(text: String, number: Int): String {
    val anchors = findFigureAnchors(text)
    val index = anchors.indexOfFirst { it.number == number }
    if (index < 0) return ""
    val target = anchors[index]
    val previousEnd = anchors.getOrNull(index - 1)?.range?.last?.plus(1) ?: 0
    val start = maxOf(previousEnd, target.range.first - 6_000)
    return text.substring(start, target.range.last + 1).trim()
}
