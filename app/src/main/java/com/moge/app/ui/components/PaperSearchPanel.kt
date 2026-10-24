package com.moge.app.ui.components

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.launch

internal val PaperSearchPanelCornerRadius = 17.dp

/** A single raised sheet holds the inset search field and borderless index filters. */
@Composable
internal fun PaperSearchPanel(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    filtersModifier: Modifier = Modifier,
    expandedContent: @Composable () -> Unit = {},
    filters: @Composable RowScope.() -> Unit,
) {
    val strings = rememberAppStrings()
    Surface(
        // Keep the top flush with the list viewport: a transparent top margin
        // exposes a thin strip of cards between the page header and this sheet.
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp).testTag("paper-search-panel"),
        shape = RoundedCornerShape(PaperSearchPanelCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(7.dp)) {
            TextField(
                value = query, onValueChange = onQuery, enabled = enabled, singleLine = true,
                placeholder = { Text(hint, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { onQuery("") }, enabled = enabled) {
                        Icon(Icons.Outlined.Close, strings.text(R.string.ui_clear_search_318ea))
                    }
                }),
                shape = RoundedCornerShape(11.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MogeTheme.paper.scratch,
                    unfocusedContainerColor = MogeTheme.paper.scratch,
                    disabledContainerColor = MogeTheme.paper.scratch,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().excludePageSwipe().semantics { contentDescription = hint },
            )
            Row(
                modifier = Modifier.fillMaxWidth().then(filtersModifier).excludePageSwipe()
                    .padding(top = 5.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = filters,
            )
            expandedContent()
        }
    }
}

@Composable
internal fun PaperCategoryFilter(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean) {
    val visibility = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    FilterChip(
        selected = selected, onClick = {
            onClick()
            // Request the entire chip, including its padding, so a clipped edge
            // becomes visible with the smallest necessary horizontal scroll.
            scope.launch { visibility.bringIntoView() }
        }, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).bringIntoViewRequester(visibility),
        shape = RoundedCornerShape(8.dp), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
