package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

@Composable
internal fun LibraryActionsMenu(enabled: Boolean, onExport: () -> Unit, onCategories: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val strings = rememberAppStrings()
    var open by remember { mutableStateOf(false) }
    Box {
        PaperHeaderIconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Outlined.MoreVert, strings.text(R.string.library_more_actions))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            @Composable fun item(label: Int, icon: ImageVector, action: () -> Unit) {
                DropdownMenuItem(text = { Text(strings.text(label)) }, leadingIcon = { Icon(icon, null) },
                    onClick = { open = false; action() })
            }
            onCategories?.let { item(R.string.ui_manage_categories_fed61, Icons.Outlined.FolderOpen, it) }
            onMove?.let { item(R.string.ui_move_to_category_4bd01, Icons.AutoMirrored.Outlined.DriveFileMove, it) }
            item(R.string.pdf_export, Icons.Outlined.FileUpload, onExport)
            onRemove?.let { item(R.string.ui_remove_from_notebook_c3aca, Icons.Outlined.Delete, it) }
        }
    }
}

@Composable
internal fun LibrarySelectionExport(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(rememberAppStrings().text(R.string.pdf_export)) }
}
