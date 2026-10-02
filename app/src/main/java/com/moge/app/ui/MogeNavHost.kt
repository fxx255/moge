package com.moge.app.ui

import android.net.Uri
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
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ARG_CONVERSATION_ID = "conversationId"

    /** 拍题首问：照片路径 + 模式 + 补充说明的 JSON；解题页取用后从 SavedStateHandle 里清掉。 */
    const val ARG_CAPTURE = "capture"
    /** 相机返回值保存在导航记录中，与 ViewModel 的路由参数使用不同的键。 */
    const val CAPTURE_RESULT = "capture_result"

    /** 会话 id 是 UUID 字符串，走可选查询参数：缺省（null）表示还没有会话的新题目。 */
    const val SOLVE = "solve?$ARG_CONVERSATION_ID={$ARG_CONVERSATION_ID}&$ARG_CAPTURE={$ARG_CAPTURE}"

    /** 新建题目不传 id；打开题册里的已有题目传其 UUID。 */
    fun solve(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) "solve" else "solve?$ARG_CONVERSATION_ID=${Uri.encode(conversationId)}"

    /** 带照片的新对话只恢复待发附件，用户确认发送后才建立会话。 */
    fun solveCaptured(batch: CaptureBatch): String = "solve?$ARG_CAPTURE=${Uri.encode(encodeCapture(batch))}"

    @Serializable
    private data class CaptureArg(val photos: List<String>, val mode: String, val note: String)

    private val captureJson = Json { ignoreUnknownKeys = true }

    fun encodeCapture(batch: CaptureBatch): String =
        captureJson.encodeToString(CaptureArg.serializer(), CaptureArg(batch.photoPaths, batch.solveMode.name, batch.note))

    /** 解析失败（被篡改、旧格式）时返回 null：宁可当成普通新题目，也不发出一道残缺的题。 */
    fun decodeCapture(raw: String?): CaptureBatch? {
        if (raw.isNullOrBlank()) return null
        val arg = runCatching { captureJson.decodeFromString(CaptureArg.serializer(), raw) }.getOrNull() ?: return null
        val mode = com.moge.app.domain.SolveMode.fromName(arg.mode) ?: return null
        val photos = arg.photos.filter { it.isNotBlank() }
        if (photos.isEmpty()) return null
        return CaptureBatch(photos, mode, arg.note)
    }
}

@Composable
fun MogeNavHost() {
    val nav = rememberNavController()
    AppUpdateNotice()
    NavHost(navController = nav, startDestination = Routes.solve()) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNotebook = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onAskByText = { nav.popBackStack() },
                onStartSolve = { batch ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(Routes.CAPTURE_RESULT, Routes.encodeCapture(batch))
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { nav.popBackStack() }, onOpenConversation = { id -> nav.navigate(Routes.solve(id)) })
        }
        composable(Routes.NOTEBOOK) {
            NotebookScreen(
                onBack = { nav.popBackStack() },
                onOpenConversation = { id -> nav.navigate(Routes.solve(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
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
            LaunchedEffect(entry, vm) {
                relayCaptureResults(entry.savedStateHandle, vm::onCaptureResult)
            }
            SolveScreen(
                conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack(); Unit }) else null,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
                onOpenNotebook = { nav.navigate(Routes.NOTEBOOK) { launchSingleTop = true } },
                onNewConversation = { nav.navigate(Routes.solve()) },
                onTakePhoto = { nav.navigate(Routes.HOME) { launchSingleTop = true } },
                vm = vm,
            )
        }
    }
}

/** NavBackStackEntry 和 Hilt ViewModel 的 SavedStateHandle 并非同一个对象。 */
internal suspend fun relayCaptureResults(resultHandle: SavedStateHandle, receive: (String) -> Unit) {
    resultHandle.getStateFlow<String?>(Routes.CAPTURE_RESULT, null).filterNotNull().collect { raw ->
        receive(raw)
        resultHandle[Routes.CAPTURE_RESULT] = null
    }
}
