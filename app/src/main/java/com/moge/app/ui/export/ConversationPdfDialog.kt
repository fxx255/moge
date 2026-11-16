package com.moge.app.ui.export

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.moge.app.R
import com.moge.app.domain.*
import com.moge.app.ui.components.PaperModalSheet
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.components.paperCard
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.awaitCancellation
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ConversationPdfDialog(
    source: ConversationExportSource,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    vm: ConversationPdfViewModel = hiltViewModel(key = "conversation-pdf-${source.name}"),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val strings = rememberAppStrings()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, source) { vm.open(source, initialSelection) }
    LaunchedEffect(state.sharePending, state.ready, lifecycle) {
        val ready = state.ready
        if (state.sharePending && ready != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                vm.retainForSharing()
                context.startActivity(Intent.createChooser(pdfShareIntent(context, ready.file), strings.text(R.string.pdf_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                vm.shared()
            } catch (_: Exception) { vm.shareFailed() }
            awaitCancellation()
        }
    }
    Dialog(onDismissRequest = { vm.close(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ConversationPdfContent(state, { vm.close(); onDismiss() }, vm::filter, vm::toggle, vm::selectAll,
            vm::selectMatching, vm::clear, vm::export, vm::cancelExport, vm::retry)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationPdfContent(
    state: ConversationPdfUiState,
    onDismiss: () -> Unit,
    onFilter: (ConversationExportFilter) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectMatching: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = rememberAppStrings()
    val visible = remember(state.items, state.filter) { filterConversationExports(state.items, state.filter) }
    var categoryMenu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var datesOpen by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy && !state.loading
    val category = if (state.filter.uncategorizedOnly) strings.text(R.string.ui_uncategorized_b28f1)
        else state.categories.firstOrNull { it.id == state.filter.categoryId }?.name ?: strings.text(R.string.pdf_all_subjects)
    val dateLabel = if (state.filter.from == null && state.filter.through == null) strings.text(R.string.pdf_all_dates)
        else "${state.filter.from ?: strings.text(R.string.pdf_date_any)} — ${state.filter.through ?: strings.text(R.string.pdf_date_any)}"
    PaperModalSheet(strings.text(R.string.pdf_export), strings.text(if (state.busy) R.string.ui_cancel_close_2475f else R.string.ui_close_sharing_59808), onDismiss,
        Modifier.testTag("conversation-pdf-dialog")) {
        if (state.busy) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(state.progress.orEmpty(), Modifier.padding(top = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge)
                TextButton(onCancel, modifier = Modifier.padding(top = 12.dp)) { Text(strings.text(R.string.pdf_cancel_export)) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("pdf-selection-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke, radius = 14.dp)
                        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { categoryMenu = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                                    Text(category, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                                }
                                DropdownMenu(categoryMenu, { categoryMenu = false }) {
                                    DropdownMenuItem(text = { Text(strings.text(R.string.pdf_all_subjects)) }, onClick = {
                                        categoryMenu = false; onFilter(state.filter.copy(categoryId = null, uncategorizedOnly = false))
                                    })
                                    DropdownMenuItem(text = { Text(strings.text(R.string.ui_uncategorized_b28f1)) }, onClick = {
                                        categoryMenu = false; onFilter(state.filter.copy(categoryId = null, uncategorizedOnly = true))
                                    })
                                    state.categories.forEach { item ->
                                        DropdownMenuItem(text = { Text(item.name) }, onClick = {
                                            categoryMenu = false; onFilter(state.filter.copy(categoryId = item.id, uncategorizedOnly = false))
                                        })
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { orderMenu = true }, enabled = enabled) {
                                    Icon(Icons.Outlined.SwapVert, strings.text(R.string.pdf_time_order))
                                }
                                DropdownMenu(orderMenu, { orderMenu = false }) {
                                    ConversationExportOrder.entries.forEach { order ->
                                        DropdownMenuItem(text = { Text(strings.text(if (order == ConversationExportOrder.NEWEST_FIRST)
                                            R.string.pdf_newest_first else R.string.pdf_oldest_first)) },
                                            leadingIcon = { if (state.filter.order == order) Icon(Icons.Outlined.Check, null) }, onClick = {
                                                orderMenu = false; onFilter(state.filter.copy(order = order))
                                            })
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { datesOpen = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp))
                            Text(dateLabel, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                        }
                        Text(strings.text(if (state.filter.order == ConversationExportOrder.NEWEST_FIRST)
                            R.string.pdf_newest_first else R.string.pdf_oldest_first), Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onSelectAll, enabled = enabled && state.items.isNotEmpty()) { Text(strings.text(R.string.pdf_select_all)) }
                            TextButton(onSelectMatching, enabled = enabled && visible.isNotEmpty()) { Text(strings.text(R.string.pdf_select_matches)) }
                            TextButton(onClear, enabled = enabled && state.selectedIds.isNotEmpty()) { Text(strings.text(R.string.pdf_clear)) }
                        }
                    }
                }
                if (state.error != null) item {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onRetry) { Text(strings.text(R.string.ui_retry_e2d53)) }
                }
                if (state.loading) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (visible.isEmpty()) item {
                    Text(strings.text(R.string.pdf_no_matches), Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visible, key = { it.id }) { item ->
                    PdfSelectionCard(item, state.categories.firstOrNull { it.id == item.categoryId }?.name
                        ?: strings.text(R.string.ui_uncategorized_b28f1), item.id in state.selectedIds) { onToggle(item.id) }
                }
                state.ready?.warnings?.let { warnings -> if (warnings.isNotEmpty()) item {
                    Text(warnings.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.text(R.string.pdf_selection_count, state.selectedIds.size, state.items.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("pdf-selection-count").semantics { liveRegion = LiveRegionMode.Polite })
                Button(onExport, enabled = enabled && state.selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .testTag("pdf-export-share")) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text(if (state.ready != null) R.string.pdf_share_again else R.string.pdf_export_share))
                }
            }
        }
    }
    if (datesOpen) PdfDateRangeDialog(state.filter, { datesOpen = false }) { from, through ->
        datesOpen = false; onFilter(state.filter.copy(from = from, through = through))
    }
}

@Composable
private fun PdfSelectionCard(item: ConversationExportItem, category: String, checked: Boolean, onToggle: () -> Unit) {
    val date = remember(item.updatedAt) { item.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("pdf-item-${item.id}").toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MogeTheme.paper.cardStroke),
        colors = CardDefaults.outlinedCardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().marginLine(MogeTheme.paper.marginLine, inset = 12.dp).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.preview.isNotBlank()) Text(item.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$category · $date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfDateRangeDialog(filter: ConversationExportFilter, onDismiss: () -> Unit, onApply: (LocalDate?, LocalDate?) -> Unit) {
    val strings = rememberAppStrings()
    var from by rememberSaveable { mutableStateOf(filter.from?.toString().orEmpty()) }
    var through by rememberSaveable { mutableStateOf(filter.through?.toString().orEmpty()) }
    val start = runCatching { LocalDate.parse(from.trim()) }.getOrNull()
    val end = runCatching { LocalDate.parse(through.trim()) }.getOrNull()
    val valid = (from.isBlank() || start != null) && (through.isBlank() || end != null) &&
        (start == null || end == null || start <= end)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings.text(R.string.pdf_date_range)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { from = ""; through = "" }) { Text(strings.text(R.string.pdf_all_dates)) }
                listOf(7, 30).forEach { days -> TextButton(onClick = {
                    val today = LocalDate.now(); from = today.minusDays(days - 1L).toString(); through = today.toString()
                }) { Text(strings.text(R.string.pdf_recent_days, days)) } }
            }
            OutlinedTextField(from, { from = it }, modifier = Modifier.fillMaxWidth().testTag("pdf-date-from"), singleLine = true,
                label = { Text(strings.text(R.string.pdf_from)) }, placeholder = { Text("YYYY-MM-DD") }, isError = from.isNotBlank() && start == null)
            OutlinedTextField(through, { through = it }, modifier = Modifier.fillMaxWidth().testTag("pdf-date-through"), singleLine = true,
                label = { Text(strings.text(R.string.pdf_through)) }, placeholder = { Text("YYYY-MM-DD") }, isError = through.isNotBlank() && end == null)
            if (!valid) Text(strings.text(R.string.pdf_invalid_dates), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { onApply(start, end) }, enabled = valid) { Text(strings.text(R.string.pdf_apply)) } },
        dismissButton = { TextButton(onDismiss) { Text(strings.text(R.string.ui_cancel_4d0b4)) } })
}
