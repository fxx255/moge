package com.moge.app.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.moge.app.R
import com.moge.app.ui.Routes
import com.moge.app.ui.components.LocalPaperNavigationScope
import com.moge.app.ui.components.PaperHeaderMinHeight
import com.moge.app.ui.components.aboveMovingPages
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.navigateToSharedDraft
import com.moge.app.ui.solve.SolveViewModel

/** Navigation and live-draft ownership stay outside both the importer and the choice UI. */
@Composable
internal fun SharedImageNavigation(nav: NavHostController, vm: SharedImagesViewModel) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val destination by vm.destination.collectAsStateWithLifecycle()
    LaunchedEffect(destination) {
        destination?.let {
            navigateToSharedDraft(nav, Routes.solve(it))
            vm.destinationOpened()
        }
    }
    val share = pending.firstOrNull()
    if (share != null) {
        val solve: SolveViewModel? = entry?.takeIf { it.destination.route == Routes.SOLVE }?.let { hiltViewModel(it) }
        val state = solve?.uiState?.collectAsStateWithLifecycle()?.value
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(share.id) { focus.clearFocus(); keyboard?.hide() }
        BackHandler { if (!busy) vm.dismiss() }
        val density = LocalDensity.current
        val paper = LocalPaperNavigationScope.current
        val composerHeight = if (state != null) paper?.composerHeight ?: 0 else 0
        val inputTop = paper?.composerInputTop.takeIf { state != null }
        var originY by remember { mutableFloatStateOf(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .onGloballyPositioned { originY = it.positionInRoot().y }) {
            val bottom = if (inputTop != null && inputTop >= originY) {
                (maxHeight - with(density) { (inputTop - originY).toDp() }).coerceIn(0.dp, maxHeight) + 8.dp
            } else with(density) { composerHeight.toDp() } + 8.dp
            val available = (maxHeight - bottom - PaperHeaderMinHeight).coerceAtLeast(0.dp)
            SharedImageAttachmentPanel(share, state?.conversationMode, state?.title,
                state != null && state.draftLoaded && !state.submitting && !state.switchingBranch && !state.importingPhotos,
                busy, error,
                onConfirm = { mode ->
                    vm.deliver(mode, current = { batch ->
                        check(nav.currentBackStackEntry == entry && solve != null) { "当前对话已改变，请重新选择" }
                        solve.acceptSharedImages(batch)
                    })
                }, onDismiss = vm::dismiss,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom)
                    .widthIn(max = 600.dp).fillMaxWidth().heightIn(max = available)
                    .aboveMovingPages(enabled = true, zIndex = 4f))
        }
    } else if (error != null) {
        val strings = rememberAppStrings()
        AlertDialog(onDismissRequest = vm::clearError, text = { Text(error!!) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text(strings.text(R.string.share_dismiss_error)) } })
    }
}
