package com.moge.app.ui.export

import android.app.Application
import android.graphics.BitmapFactory
import com.moge.app.core.LatexEngine
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.data.figure.StoredFigureResolver
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.parse.orderedFigures
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.DefaultFigureRenderer
import com.moge.app.runtime.FigureCompletionContext
import com.moge.app.runtime.GenerationFigureCompleter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MissingQamFigureExportTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun resource(path: String) = requireNotNull(javaClass.getResource(path)).readText()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        LatexEngine.init(context)
    }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun `third QAM figure survives native rendering theme conversion and long image export`() = runTest {
        val body = resource("/fixtures/qam-missing-demodulator.md")
        val modulator = Json.parseToJsonElement(resource("/diagrams/communication/shared-carrier-qam.json"))
        val receiver = Json.parseToJsonElement(resource("/diagrams/communication/shared-carrier-qam-receiver.json"))
        val spectrum = Json.parseToJsonElement("""{"title":"E 点功率谱","x":{"min":-25,"max":25},"y":{"min":0,"max":1.3},"series":[{"points":[[-25,0],[-15,1],[15,1],[25,0]]}]}""")
        val initial = ReplyParser.parse(buildJsonObject {
            put("reply", body)
            put("plots", JsonArray(listOf(spectrum)))
            put("diagrams", JsonArray(listOf(modulator, JsonNull)))
        }.toString())
        val model = mockk<ModelClient>()
        coEvery { model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>()) } returns
            ReplyParser.parse(buildJsonObject { put("diagrams", JsonArray(listOf(receiver))) }.toString())
        val settings = mockk<SettingsRepository> {
            coEvery { current() } returns UserSettings(appearance = Appearance.CHALK)
        }
        val plots = PlotImageStore(context)
        val diagrams = DiagramImageStore(context)
        val paths = GenerationFigureCompleter(DefaultFigureRenderer(plots, diagrams, settings, context), model)
            .complete(initial.reply, "", initial.orderedFigures(), FigureCompletionContext(
                listOf(ChatMessage("user", "批改 PCM 复用与 64QAM 调制、解调和功率谱")), emptyList(),
                SolveMode.DETAILED, null, {},
            ))
        assertEquals(3, paths.size)
        paths.forEach { path ->
            assertTrue(File(path).isFile)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(path))
            assertTrue(bitmap.width > 500 && bitmap.height > 100)
            bitmap.recycle()
        }
        val resolver = StoredFigureResolver(plots, diagrams)
        val result = renderAnswerExport(context, AnswerExportContent("64QAM 缺图恢复", "原题保留", answerText = body,
            figurePaths = paths), ExportChoice.FULL, resolver) { }
        try {
            assertTrue(result.warnings.toString(), result.warnings.isEmpty())
            assertTrue(result.pages.isNotEmpty())
            if (System.getenv("FIGURE_LAYOUT_EXPORT_SAMPLES") == "1") {
                val folder = File("build/figure-layout-samples").apply { mkdirs() }
                File(requireNotNull(resolver.resolve(paths[2], false))).copyTo(File(folder, "qam-receiver-paper.png"), overwrite = true)
                result.pages.forEachIndexed { index, page -> page.copyTo(File(folder, "qam-restored-export-${index + 1}.png"), overwrite = true) }
            }
        } finally { result.files.close(force = true) }
    }
}
