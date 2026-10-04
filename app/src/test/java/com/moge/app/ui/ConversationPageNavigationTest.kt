package com.moge.app.ui

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationPageNavigationTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class DraftViewModel : ViewModel() {
        var draft = ""
    }

    private val owner = Owner()
    private val viewModels = ViewModelStore()
    private lateinit var nav: NavHostController
    private lateinit var composeNavigator: ComposeNavigator
    private val draftFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DraftViewModel() as T
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        nav = NavHostController(ApplicationProvider.getApplicationContext())
        composeNavigator = ComposeNavigator()
        nav.navigatorProvider.addNavigator(NavGraphNavigator(nav.navigatorProvider))
        nav.navigatorProvider.addNavigator(composeNavigator)
        nav.setViewModelStore(viewModels)
        nav.setLifecycleOwner(owner)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    @After fun tearDown() {
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun start(conversationId: String? = null): NavBackStackEntry {
        nav.graph = nav.createGraph(startDestination = Routes.solve(conversationId)) {
            composable(Routes.SOLVE, arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument(Routes.ARG_CAPTURE) { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { }
            composable(Routes.NOTEBOOK_ROUTE, arguments = listOf(
                navArgument(Routes.ARG_NOTEBOOK_ENTRY_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
            )) { }
            composable(Routes.HISTORY) { }
        }
        composeNavigator.backStack.value.forEach(composeNavigator::onTransitionComplete)
        return requireNotNull(nav.currentBackStackEntry)
    }

    private fun draft(entry: NavBackStackEntry): DraftViewModel =
        ViewModelProvider(entry, draftFactory)[DraftViewModel::class.java]

    private fun navigate(action: () -> Unit) {
        val outgoing = nav.currentBackStackEntry
        action()
        // Without a rendered NavHost, complete transitions explicitly, including a popped entry.
        (composeNavigator.backStack.value + listOfNotNull(outgoing)).distinctBy { it.id }
            .forEach(composeNavigator::onTransitionComplete)
    }

    private fun assertReturned(entry: NavBackStackEntry, model: DraftViewModel, expectedDraft: String) {
        assertSame(entry, nav.currentBackStackEntry)
        assertSame(model, draft(requireNotNull(nav.currentBackStackEntry)))
        assertEquals(expectedDraft, model.draft)
        assertEquals(1, composeNavigator.backStack.value.size)
    }

    @Test fun `new to existing to new returns the same entry and unfinished draft`() {
        val original = start()
        val model = draft(original).apply { draft = "unfinished new question" }
        navigate { navigateToConversation(nav, "existing") }
        val existing = requireNotNull(nav.currentBackStackEntry)
        assertNotSame(original, existing)
        assertEquals("existing", existing.logicalConversationId())
        assertEquals(2, composeNavigator.backStack.value.size)
        navigate { navigateToNewConversation(nav) }
        assertReturned(original, model, "unfinished new question")
        assertNull(original.logicalConversationId())
        navigate { navigateToNewConversation(nav) }
        assertReturned(original, model, "unfinished new question")
    }

    @Test fun `existing to new to existing returns the same entry and followup draft`() {
        val original = start("existing")
        val model = draft(original).apply { draft = "unfinished followup" }
        navigate { navigateToNewConversation(nav) }
        val newPage = requireNotNull(nav.currentBackStackEntry)
        assertNotSame(original, newPage)
        assertNotSame(model, draft(newPage))
        assertNull(newPage.logicalConversationId())
        assertEquals(2, composeNavigator.backStack.value.size)
        navigate { navigateToConversation(nav, "existing") }
        assertReturned(original, model, "unfinished followup")
        assertEquals("existing", original.logicalConversationId())
    }

    @Test fun `existing to notebook to existing reuses the conversation and its draft`() {
        val original = start("existing")
        val model = draft(original).apply { draft = "question after viewing a favorite" }
        navigate { nav.navigate(Routes.notebook("favorite")) }
        assertEquals(Routes.NOTEBOOK_ROUTE, nav.currentBackStackEntry?.destination?.route)
        assertEquals(2, composeNavigator.backStack.value.size)
        navigate { navigateToConversation(nav, "existing") }
        assertReturned(original, model, "question after viewing a favorite")
    }

    @Test fun `first send turns a null route into an existing logical conversation without replacing its entry`() {
        val original = start()
        val model = draft(original).apply { draft = "followup after first send" }
        assertNull(original.arguments?.getString(Routes.ARG_CONVERSATION_ID))
        original.savedStateHandle[LOGICAL_CONVERSATION_ID] = "created-after-send"
        assertEquals("created-after-send", original.logicalConversationId())
        navigate { navigateToConversation(nav, "created-after-send") }
        assertReturned(original, model, "followup after first send")
        navigate { navigateToNewConversation(nav) }
        val newPage = requireNotNull(nav.currentBackStackEntry)
        assertNotSame(original, newPage)
        assertNotSame(model, draft(newPage))
        assertNull(newPage.logicalConversationId())
        assertEquals(2, composeNavigator.backStack.value.size)
        navigate { navigateToConversation(nav, "created-after-send") }
        assertReturned(original, model, "followup after first send")
        assertNull(original.arguments?.getString(Routes.ARG_CONVERSATION_ID))
        assertEquals("created-after-send", original.logicalConversationId())
    }

    @Test fun `different conversation creates a fresh entry and ViewModel on the same destination`() {
        val original = start("first")
        val model = draft(original).apply { draft = "first conversation draft" }
        navigate { navigateToConversation(nav, "second") }
        val second = requireNotNull(nav.currentBackStackEntry)
        val secondModel = draft(second)
        assertEquals(original.destination.route, second.destination.route)
        assertNotSame(original, second)
        assertNotSame(original.viewModelStore, second.viewModelStore)
        assertNotSame(model, secondModel)
        assertEquals("", secondModel.draft)
        assertEquals("second", second.arguments?.getString(Routes.ARG_CONVERSATION_ID))
        assertEquals("second", second.logicalConversationId())
        assertSame(original, nav.previousBackStackEntry)
        assertEquals("first conversation draft", model.draft)
        assertEquals(2, composeNavigator.backStack.value.size)
        navigate { navigateToConversation(nav, "first") }
        assertReturned(original, model, "first conversation draft")
    }

    @Test fun `shared drafts receive fresh ViewModels on cold and subsequent opens`() {
        val original = start()
        val model = draft(original).apply { draft = "unfinished question before sharing" }
        navigate { navigateToSharedDraft(nav, Routes.solve("shared-pdf")) }
        val first = requireNotNull(nav.currentBackStackEntry)
        val firstModel = draft(first).apply { draft = "shared PDF attachment" }
        assertNotSame(model, firstModel)
        assertEquals("shared-pdf", first.logicalConversationId())
        assertEquals("unfinished question before sharing", model.draft)

        navigate { navigateToSharedDraft(nav, Routes.solve("shared-word")) }
        val second = requireNotNull(nav.currentBackStackEntry)
        assertNotSame(firstModel, draft(second))
        assertEquals("shared-word", second.logicalConversationId())
        assertEquals("shared PDF attachment", firstModel.draft)
        navigate { nav.popBackStack() }
        assertSame(firstModel, draft(requireNotNull(nav.currentBackStackEntry)))
        assertEquals("shared PDF attachment", firstModel.draft)
    }

    @Test fun `opening the current existing conversation is a no op preserving its entry and draft`() {
        val original = start("existing")
        val model = draft(original).apply { draft = "current draft" }
        navigate { navigateToConversation(nav, "existing") }
        assertReturned(original, model, "current draft")
    }
    @Test fun `system back from history and new page cannot reuse swipe animation`() {
        val old = start("existing")
        var ticket: SwipeTransition? = null
        navigate { ticket = navigateWithSwipe(nav, 1) { navigateToNewConversation(nav) } }
        val newPage = requireNotNull(nav.currentBackStackEntry)
        assertEquals(1, ticket!!.directionFor(old.id, newPage.id))
        navigate { nav.popBackStack() }
        assertEquals(0, ticket!!.directionFor(newPage.id, requireNotNull(nav.currentBackStackEntry).id))
        navigate { navigateToNewConversation(nav) }
        val newAgain = requireNotNull(nav.currentBackStackEntry)
        navigate { ticket = navigateWithSwipe(nav, 1) { nav.navigate(Routes.HISTORY) } }
        val history = requireNotNull(nav.currentBackStackEntry)
        navigate { nav.popBackStack() }
        assertSame(newAgain, nav.currentBackStackEntry)
        assertEquals(0, ticket!!.directionFor(history.id, newAgain.id))
    }
    @Test fun `left swipe popping to old conversation preserves its leftward animation and no op has none`() {
        val old = start("existing")
        navigate { navigateToNewConversation(nav) }
        val newPage = requireNotNull(nav.currentBackStackEntry)
        var ticket: SwipeTransition? = null
        navigate { ticket = navigateWithSwipe(nav, -1) { navigateToConversation(nav, "existing") } }
        assertSame(old, nav.currentBackStackEntry)
        assertEquals(-1, ticket!!.directionFor(newPage.id, old.id))
        navigate { ticket = navigateWithSwipe(nav, -1) { navigateToConversation(nav, "existing") } }
        assertNull(ticket)
    }
}
