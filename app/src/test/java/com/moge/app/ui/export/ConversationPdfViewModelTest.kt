package com.moge.app.ui.export

import android.app.Application
import com.moge.app.core.localizedTestText
import com.moge.app.data.db.ConversationExportRepository
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.domain.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationPdfViewModelTest {
    private val repository = mockk<ConversationExportRepository>()
    private val exporter = mockk<ConversationPdfExporter>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val items = listOf(
        ConversationExportItem("new", "new", "", "math", Instant.parse("2026-11-16T12:00:00Z")),
        ConversationExportItem("old", "old", "", "english", Instant.parse("2026-11-10T12:00:00Z")),
    )
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.catalogue(any()) } returns items
        coEvery { repository.categories() } returns listOf(NotebookCategoryEntity(id = "math", name = "数学"))
    }
    @After fun reset() { Dispatchers.resetMain() }
    private fun model() = ConversationPdfViewModel(repository, exporter, localizedTestText())

    @Test fun `manual choices survive subject filters and filtered selection accumulates`() {
        val vm = model()
        vm.open(ConversationExportSource.HISTORY, setOf("old", "deleted"))
        assertEquals(setOf("old"), vm.state.value.selectedIds)
        vm.filter(ConversationExportFilter(categoryId = "math"))
        vm.selectMatching()
        assertEquals(setOf("new", "old"), vm.state.value.selectedIds)
        vm.toggle("new")
        assertEquals(setOf("old"), vm.state.value.selectedIds)
        vm.selectAll()
        assertEquals(setOf("new", "old"), vm.state.value.selectedIds)
        vm.clear()
        assertTrue(vm.state.value.selectedIds.isEmpty())
        vm.close()
    }

    @Test fun `export uses chosen order and repeated presses never start duplicate jobs`() {
        val vm = model()
        vm.open(ConversationExportSource.NOTEBOOK, emptySet())
        vm.selectAll()
        vm.filter(ConversationExportFilter(order = ConversationExportOrder.OLDEST_FIRST, categoryId = "math"))
        coEvery { exporter.export(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        vm.export(); vm.export()
        coVerify(exactly = 1) { exporter.export(ConversationExportSource.NOTEBOOK,
            match { it.map { item -> item.id } == listOf("old", "new") }, any(), any()) }
        vm.toggle("old")
        assertEquals(2, vm.state.value.selectedIds.size)
        vm.cancelExport()
        assertFalse(vm.state.value.busy)
        vm.export()
        coVerify(exactly = 2) { exporter.export(any(), any(), any(), any()) }
        vm.close()
    }

    @Test fun `load failure supports retry and does not allow empty export`() {
        coEvery { repository.catalogue(any()) } throws IllegalStateException("db")
        val vm = model()
        vm.open(ConversationExportSource.HISTORY, emptySet())
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
        vm.export()
        coVerify(exactly = 0) { exporter.export(any(), any(), any(), any()) }
        coEvery { repository.catalogue(any()) } returns items
        vm.retry()
        assertEquals(2, vm.state.value.items.size)
        vm.close()
    }

    @Test fun `share is one shot and changing order invalidates already generated pdf`() = runTest {
        val files = ExportFiles.create(RuntimeEnvironment.getApplication())
        val result = ConversationPdfResult(files, File(files.directory, "test.pdf").apply { writeText("pdf") }, 2, emptyList())
        coEvery { exporter.export(any(), any(), any(), any()) } returns result
        val vm = model()
        try {
            vm.open(ConversationExportSource.NOTEBOOK, setOf("new"))
            vm.export()
            assertTrue(vm.state.value.sharePending)
            vm.shared()
            assertFalse(vm.state.value.sharePending)
            vm.export()
            coVerify(exactly = 1) { exporter.export(any(), any(), any(), any()) }
            assertTrue(vm.state.value.sharePending)
            vm.shared()
            vm.filter(ConversationExportFilter(order = ConversationExportOrder.OLDEST_FIRST))
            assertNull(vm.state.value.ready)
            vm.export()
            coVerify(exactly = 2) { exporter.export(any(), any(), any(), any()) }
        } finally { vm.close(); files.close(force = true) }
    }
}
