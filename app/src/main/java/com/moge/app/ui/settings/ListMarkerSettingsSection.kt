package com.moge.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.data.prefs.ListMarkerStyle
import com.moge.app.ui.localization.appString
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.markdown.ListMarkerPainter
import com.moge.app.ui.theme.LocalBodyTextSize
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.listMarkerAccent
import kotlin.math.roundToInt

@Composable
internal fun ListMarkerSettingsSection(value: ListMarkerSettings, onChange: (ListMarkerSettings) -> Unit) {
    val strings = rememberAppStrings()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var adjustmentsExpanded by rememberSaveable { mutableStateOf(false) }
    // The inline marker updates during a drag; commit once on release.
    var draft by remember(value) { mutableStateOf(value.normalized()) }
    fun save(next: ListMarkerSettings) { draft = next; onChange(next) }
    SettingsSection(strings.text(R.string.ui_list_markers)) {
        PreferenceDropdown(strings.text(R.string.ui_list_marker_style), listMarkerName(draft.style), expanded,
            "list-marker-style-selector", { expanded = it },
            leadingIcon = { ListMarkerSwatch(draft, Modifier.testTag("list-marker-selected-swatch")) }) {
            ListMarkerStyle.entries.forEach { style ->
                val label = listMarkerName(style)
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { ListMarkerSwatch(draft.copy(style = style)) },
                    trailingIcon = { if (style == draft.style) Icon(Icons.Outlined.Check, null) },
                    onClick = { expanded = false; save(draft.copy(style = style)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("list-marker-option-${style.name}")
                        .semantics { selected = style == draft.style; role = Role.RadioButton },
                )
            }
        }
        val adjustmentState = strings.text(if (adjustmentsExpanded) R.string.ui_expanded_fa83a else R.string.ui_collapsed_5dd9d)
        TextButton(onClick = { adjustmentsExpanded = !adjustmentsExpanded },
            modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp).heightIn(min = 48.dp)
                .testTag("list-marker-adjustments-toggle").semantics { stateDescription = adjustmentState }) {
            Text(strings.text(R.string.ui_list_marker_adjust))
            Spacer(Modifier.width(4.dp))
            Icon(if (adjustmentsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (adjustmentsExpanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkerAdjustment(strings.text(R.string.ui_list_marker_size), draft.sizePercent, ListMarkerSettings.sizeRange,
                    "list-marker-size", { draft = draft.copy(sizePercent = it) }, { onChange(draft) })
                if (draft.style.hasStroke) {
                    MarkerAdjustment(strings.text(R.string.ui_list_marker_stroke), draft.strokePercent, ListMarkerSettings.strokeRange,
                        "list-marker-stroke", { draft = draft.copy(strokePercent = it) }, { onChange(draft) })
                }
                MarkerAdjustment(strings.text(R.string.ui_list_marker_indent), draft.indentPercent, ListMarkerSettings.indentRange,
                    "list-marker-indent", { draft = draft.copy(indentPercent = it) }, { onChange(draft) })
                TextButton(onClick = { save(ListMarkerSettings()) }, modifier = Modifier.testTag("list-marker-reset")) {
                    Text(strings.text(R.string.ui_list_marker_reset))
                }
            }
        }
    }
}

@Composable
private fun MarkerAdjustment(label: String, value: Int, range: IntRange, tag: String,
    onValue: (Int) -> Unit, onFinished: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$value%", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value.toFloat(), onValueChange = { onValue(it.roundToInt()) },
            onValueChangeFinished = onFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / 5 - 1,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
    }
}

@Composable
private fun ListMarkerSwatch(settings: ListMarkerSettings, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val textSizePx = with(density) { LocalBodyTextSize.current.toPx() }
    val painter = remember(settings, textSizePx) { ListMarkerPainter(settings, textSizePx) }
    val frame = with(density) { painter.minimumMarginPx.toDp() }.coerceAtLeast(28.dp)
    val accent = listMarkerAccent(MogeTheme.paper.isChalk)
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    Canvas(modifier.size(frame)) {
        drawIntoCanvas { painter.draw(it.nativeCanvas, center.x, center.y, accent, ink) }
    }
}

@Composable
internal fun listMarkerName(style: ListMarkerStyle): String = appString(when (style) {
    ListMarkerStyle.OCTAGON -> R.string.ui_list_marker_octagon
    ListMarkerStyle.RING -> R.string.ui_list_marker_ring
    ListMarkerStyle.ROUNDED_DIAMOND -> R.string.ui_list_marker_diamond
    ListMarkerStyle.DASH -> R.string.ui_list_marker_dash
    ListMarkerStyle.ROUNDED_SQUARE -> R.string.ui_list_marker_square
    ListMarkerStyle.THEME_DOT -> R.string.ui_list_marker_theme_dot
    ListMarkerStyle.CLASSIC_DOT -> R.string.ui_list_marker_classic
})
