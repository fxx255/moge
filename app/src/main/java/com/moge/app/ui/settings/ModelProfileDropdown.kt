package com.moge.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

internal data class ModelChoice(val id: String?, val name: String, val detail: String)

/** Shared themed selector; profile management and vision routing stay with their callers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelProfileDropdown(
    label: String,
    choices: List<ModelChoice>,
    selectedId: String?,
    placeholder: String,
    enabled: Boolean,
    tag: String,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val available = enabled && choices.isNotEmpty()
    LaunchedEffect(available) { if (!available) expanded = false }
    val selected = choices.firstOrNull { it.id == selectedId }
    val duration = if (MogeTheme.motionEnabled) 200 else 0
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(duration), label = "model-menu-arrow")
    val shape = RoundedCornerShape(10.dp)
    ExposedDropdownMenuBox(
        expanded = expanded && available,
        onExpandedChange = { if (available) expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = selected?.name ?: placeholder,
            onValueChange = {},
            readOnly = true,
            enabled = available,
            label = { Text(label) },
            singleLine = true,
            shape = shape,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(rotation)) },
            supportingText = selected?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MogeTheme.paper.scratch,
                unfocusedContainerColor = MogeTheme.paper.scratch,
                unfocusedBorderColor = MogeTheme.paper.cardStroke.copy(alpha = 0.6f),
            ),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = available)
                .fillMaxWidth().animateContentSize(tween(duration)).testTag(tag)
                .semantics { stateDescription = if (expanded && available) "已展开" else "已收起" },
        )
        ExposedDropdownMenu(
            expanded = expanded && available,
            onDismissRequest = { expanded = false },
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.heightIn(max = 320.dp)
                .border(1.dp, MogeTheme.paper.cardStroke.copy(alpha = 0.5f), shape),
        ) {
            choices.forEach { choice ->
                val checked = choice.id == selectedId
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(choice.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal)
                            if (choice.detail.isNotBlank()) Text(choice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { expanded = false; onSelect(choice.id) },
                    trailingIcon = { if (checked) Icon(Icons.Outlined.Check, "已选中", tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.testTag("$tag-option-${choice.id ?: "none"}")
                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                            else MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}
