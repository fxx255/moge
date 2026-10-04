package com.moge.app.ui.solve

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation

/** State-based editing lets Android paste image content without inserting its URI as text. */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
internal fun ComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onPasteImages: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val binding = remember { ComposerTextBinding(value) }
    val onChange by rememberUpdatedState(onValueChange)
    val onImages by rememberUpdatedState(onPasteImages)
    val context = LocalContext.current
    val receiver = remember(context) { imageContentReceiver(context) { onImages(it) } }
    val interaction = remember { MutableInteractionSource() }
    SideEffect { binding.acceptExternal(value) }
    LaunchedEffect(binding) {
        snapshotFlow { binding.field.text.toString() }.collect { binding.reportText(it, onChange) }
    }
    BasicTextField(
        state = binding.field,
        modifier = modifier.heightIn(min = TextFieldDefaults.MinHeight).contentReceiver(receiver),
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
        decorator = { innerTextField ->
            TextFieldDefaults.DecorationBox(
                value = binding.field.text.toString(),
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                placeholder = { Text(placeholder) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
    )
}

/** A delayed draft-state echo must not move the cursor or overwrite more recent typing. */
internal class ComposerTextBinding(initial: String) {
    val field = TextFieldState(initial)
    private var external = initial
    private var reported = initial
    private val pendingEchoes = mutableListOf<String>()

    fun acceptExternal(value: String) {
        if (value == external) return
        external = value
        val echo = pendingEchoes.indexOfLast { it == value }
        if (echo >= 0) {
            pendingEchoes.subList(0, echo + 1).clear()
        } else {
            pendingEchoes.clear()
            reported = value
            if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value)
        }
    }

    fun reportText(value: String, onChange: (String) -> Unit) {
        if (value == reported) return
        reported = value
        pendingEchoes.add(value)
        onChange(value)
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun imageContentReceiver(context: Context, onImages: (List<Uri>) -> Unit): ReceiveContentListener =
    ReceiveContentListener { content ->
        val images = mutableListOf<Uri>()
        val remaining = content.consume { item ->
            val uri = item.uri
            if (uri == null || uri.scheme !in setOf("content", "file")) {
                false
            } else {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val isImage = if (mime.isNullOrBlank() || mime == "application/octet-stream") {
                    content.hasMediaType(MediaType.Image)
                } else mime.startsWith("image/", ignoreCase = true)
                if (isImage) images.add(uri)
                isImage
            }
        }
        if (images.isNotEmpty()) onImages(images.distinct())
        remaining
    }
