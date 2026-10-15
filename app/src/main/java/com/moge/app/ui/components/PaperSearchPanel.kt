package com.moge.app.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** A single raised sheet holds the inset search field and borderless index filters. */
@Composable
internal fun PaperSearchPanel(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    enabled: Boolean,
    filtersModifier: Modifier = Modifier,
    filters: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(7.dp)) {
            TextField(
                value = query, onValueChange = onQuery, enabled = enabled, singleLine = true,
                placeholder = { Text(hint, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { onQuery("") }, enabled = enabled) {
                        Icon(Icons.Outlined.Close, "清除搜索")
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
        }
    }
}

@Composable
internal fun PaperCategoryFilter(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
