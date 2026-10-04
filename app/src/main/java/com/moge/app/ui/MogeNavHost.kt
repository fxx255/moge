package com.moge.app.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.moge.app.ui.theme.MogeTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.home.HomeScreen
import com.moge.app.ui.notebook.NotebookScreen
import com.moge.app.ui.history.HistoryScreen
import com.moge.app.ui.settings.SettingsScreen
import com.moge.app.ui.solve.SolveScreen
import com.moge.app.ui.solve.SolveViewModel
import com.moge.app.ui.update.AppUpdateNotice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.filterNotNull

object Routes {
    const val HOME = "home"
    const val NOTEBOOK = "notebook"
    const val ARG_NOTEBOOK_ENTRY_ID = "notebookEntryId"
    const val NOTEBOOK_ROUTE = "notebook?$ARG_NOTEBOOK_ENTRY_ID={$ARG_NOTEBOOK_ENTRY_ID}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ARG_CONVERSATION_ID = "conversationId"

    /** 拍题首问：照片路径 + 模式 + 补充说明的 JSON；解题页取用后从 SavedStateHandle 里清掉。 */
    const val ARG_CAPTURE = "capture"
    /** 相机返回值保存在导航记录中，与 ViewModel 的路由参数使用不同的键。 */
    const val CAPTURE_RESULT = "capture_result"

    /** 会话 id 是 UUID 字符串，走可选查询参数：缺省（null）表示还没有会话的新题目。 */
    const val SOLVE = "solve?$ARG_CONVERSATION_ID={$ARG_CONVERSATION_ID}&$ARG_CAPTURE={$ARG_CAPTURE}"

    fun notebook(entryId: String? = null): String = if (entryId.isNullOrBlank()) NOTEBOOK
        else "$NOTEBOOK?$ARG_NOTEBOOK_ENTRY_ID=${Uri.encode(entryId)}"

    /** 新建题目不传 id；打开题册里的已有题目传其 UUID。 */
    fun solve(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) "solve" else "solve?$ARG_CONVERSATION_ID=${Uri.encode(conversationId)}"

    /** 带照片的新对话只恢复待发附件，用户确认发送后才建立会话。 */
    fun solveCaptured(batch: CaptureBatch): String = "solve?$ARG_CAPTURE=${Uri.encode(encodeCapture(batch))}"

    @Serializable
    private data class CaptureArg(
        val photos: List<String>, val mode: String, val note: String,
        val documents: List<String> = emptyList(),
    )

    private val captureJson = Json { ignoreUnknownKeys = true }

    fun encodeCapture(batch: CaptureBatch): String =
        captureJson.encodeToString(CaptureArg.serializer(), CaptureArg(
            batch.photoPaths, batch.solveMode.name, batch.note, batch.documentPaths,
        ))

    /** 解析失败（被篡改、旧格式）时返回 null：宁可当成普通新题目，也不发出一道残缺的题。 */
    fun decodeCapture(raw: String?): CaptureBatch? {
        if (raw.isNullOrBlank()) return null
        val arg = runCatching { captureJson.decodeFromString(CaptureArg.serializer(), raw) }.getOrNull() ?: return null
        val mode = com.moge.app.domain.SolveMode.fromName(arg.mode) ?: return null
        val photos = arg.photos.filter { it.isNotBlank() }
        val documents = arg.documents.filter { it.isNotBlank() }
        if (photos.isEmpty() && documents.isEmpty()) return null
        return CaptureBatch(photos, mode, arg.note, documents)
    }
}

@Composable
fun MogeNavHost(
    sharedRoute: String? = null,
    onSharedCaptureConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val navigation: ConversationNavigationViewModel = hiltViewModel()
    val previousConversation by navigation.previousConversationId.collectAsStateWithLifecycle()
    var swipeTransition by remember { mutableStateOf<SwipeTransition?>(null) }
    val duration = if (MogeTheme.motionEnabled) 240 else 0
    LaunchedEffect(sharedRoute) {
        sharedRoute?.let {
            nav.navigate(it) { launchSingleTop = true }
            onSharedCaptureConsumed()
        }
    }
    val ordinary: (String) -> Unit = { route -> swipeTransition = null; nav.navigate(route) { launchSingleTop = true } }
    val back: () -> Unit = { swipeTransition = null; nav.popBackStack(); Unit }
    val swipe: (Int, () -> Unit) -> Unit = { direction, navigate ->
        swipeTransition = null
        swipeTransition = navigateWithSwipe(nav, direction, navigate)
    }
    val newPage: (Int) -> Unit = { direction ->
        if (direction == 0) { swipeTransition = null; navigateToNewConversation(nav) }
        else swipe(direction) { navigateToNewConversation(nav) }
    }
    val resume: (Int) -> Unit = { gestureDirection ->
        val navigate = { previousConversation?.let { navigateToConversation(nav, it) } ?: navigateToNewConversation(nav) }
        if (gestureDirection == 0) { swipeTransition = null; navigate() } else swipe(gestureDirection, navigate)
    }
    val openConversation: (String) -> Unit = { id ->
        swipeTransition = null
        navigation.rememberConversation(id)
        navigateToConversation(nav, id)
    }
    val openNotebook: (String?) -> Unit = { id ->
        swipeTransition = null
        // A specific favorite uses a fresh route/VM so old filters cannot conceal it.
        nav.navigate(Routes.notebook(id)) { launchSingleTop = id == null }
    }
    AppUpdateNotice()
    NavHost(navController = nav, startDestination = Routes.solve(),
        enterTransition = {
            val direction = swipeTransition?.directionFor(initialState.id, targetState.id) ?: 0
            if (direction == 0 || duration == 0) EnterTransition.None
            else slideInHorizontally(tween(duration)) { -direction * it }
        },
        exitTransition = {
            val direction = swipeTransition?.directionFor(initialState.id, targetState.id) ?: 0
            if (direction == 0 || duration == 0) ExitTransition.None
            else slideOutHorizontally(tween(duration)) { direction * it }
        },
        popEnterTransition = {
            val direction = swipeTransition?.directionFor(initialState.id, targetState.id) ?: 0
            if (direction == 0 || duration == 0) EnterTransition.None
            else slideInHorizontally(tween(duration)) { -direction * it }
        },
        popExitTransition = {
            val direction = swipeTransition?.directionFor(initialState.id, targetState.id) ?: 0
            if (direction == 0 || duration == 0) ExitTransition.None
            else slideOutHorizontally(tween(duration)) { direction * it }
        }) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNotebook = back,
                onOpenSettings = { ordinary(Routes.SETTINGS) },
                onAskByText = back,
                onStartSolve = { batch ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(Routes.CAPTURE_RESULT, Routes.encodeCapture(batch))
                    back()
                },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = back, onOpenConversation = openConversation,
                onOpenNotebook = openNotebook, onNewConversation = { newPage(-1) })
        }
        composable(Routes.NOTEBOOK_ROUTE, arguments = listOf(
            navArgument(Routes.ARG_NOTEBOOK_ENTRY_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
        )) {
            NotebookScreen(onBack = back, onOpenConversation = openConversation,
                onReturnToConversation = { resume(1) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onOpenNotebook = { openNotebook(null) })
        }
        composable(
            route = Routes.SOLVE,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_CAPTURE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val vm: SolveViewModel = hiltViewModel(entry)
            LaunchedEffect(entry, vm) { relayCaptureResults(entry.savedStateHandle, vm::onCaptureResult) }
            SolveScreen(
                conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
                onBack = if (nav.previousBackStackEntry != null) back else null,
                onOpenSettings = { ordinary(Routes.SETTINGS) },
                onOpenHistory = { ordinary(Routes.HISTORY) },
                onOpenNotebook = { openNotebook(null) },
                onNewConversation = { newPage(0) },
                onTakePhoto = { ordinary(Routes.HOME) },
                vm = vm,
                onResumeConversation = previousConversation?.let { { resume(0) } },
                onSwipeHistory = { swipe(1) { nav.navigate(Routes.HISTORY) { launchSingleTop = true } } },
                onSwipeNotebook = { swipe(-1) { nav.navigate(Routes.notebook()) } },
                onSwipeNewConversation = { newPage(1) },
                onSwipeResumeConversation = previousConversation?.let { { resume(-1) } },
                readViewport = navigation::readViewport,
                saveViewport = navigation::saveViewport,
                onConversationObserved = { id ->
                    entry.savedStateHandle[LOGICAL_CONVERSATION_ID] = id.orEmpty()
                    navigation.rememberConversation(id)
                },
                onViewFavorite = { openNotebook(it) },
            )
        }
    }
}

// A new page gains its ID after its first send; navigation arguments remain unchanged.
internal const val LOGICAL_CONVERSATION_ID = "logical_conversation_id"
internal fun NavBackStackEntry.logicalConversationId(): String? =
    (if (savedStateHandle.contains(LOGICAL_CONVERSATION_ID)) savedStateHandle.get<String>(LOGICAL_CONVERSATION_ID)
    else arguments?.getString(Routes.ARG_CONVERSATION_ID))?.takeIf { it.isNotBlank() }

/** Reuse the immediately adjacent page to preserve its draft and avoid reciprocal stack growth. */
internal fun navigateToNewConversation(nav: NavHostController) {
    val current = nav.currentBackStackEntry
    if (current?.destination?.route == Routes.SOLVE && current.logicalConversationId() == null) return
    val previous = nav.previousBackStackEntry
    if (previous?.destination?.route == Routes.SOLVE && previous.logicalConversationId() == null) nav.popBackStack()
    else nav.navigate(Routes.solve())
}

internal fun navigateToConversation(nav: NavHostController, id: String) {
    val current = nav.currentBackStackEntry
    if (current?.destination?.route == Routes.SOLVE && current.logicalConversationId() == id) return
    val previous = nav.previousBackStackEntry
    if (previous?.destination?.route == Routes.SOLVE && previous.logicalConversationId() == id) nav.popBackStack()
    else nav.navigate(Routes.solve(id))
}

/** NavBackStackEntry 和 Hilt ViewModel 的 SavedStateHandle 并非同一个对象。 */
internal suspend fun relayCaptureResults(resultHandle: SavedStateHandle, receive: (String) -> Unit) {
    resultHandle.getStateFlow<String?>(Routes.CAPTURE_RESULT, null).filterNotNull().collect { raw ->
        receive(raw)
        resultHandle[Routes.CAPTURE_RESULT] = null
    }
}
