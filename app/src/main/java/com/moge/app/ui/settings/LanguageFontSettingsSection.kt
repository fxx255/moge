package com.moge.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.data.prefs.AppFont
import com.moge.app.data.prefs.AppLanguage
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.localization.appString
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.appFontFamily
import com.moge.app.ui.theme.rememberAppTypeface

private enum class LanguageFontPicker { INTERFACE, ANSWER, FONT }

@Composable
internal fun LanguageFontSettingsSection(
    prefs: UserSettings,
    onInterfaceLanguage: (AppLanguage) -> Unit,
    onAnswerLanguage: (AppLanguage) -> Unit,
    onFont: (AppFont) -> Unit,
) {
    val strings = rememberAppStrings()
    var picker by rememberSaveable { mutableStateOf<LanguageFontPicker?>(null) }
    SettingsSection(strings.text(R.string.ui_language_font_19688)) {
        PreferenceDropdown(
            title = strings.text(R.string.ui_app_language_d5d8e),
            value = prefs.interfaceLanguage.nativeName,
            expanded = picker == LanguageFontPicker.INTERFACE,
            tag = "interface-language-selector",
            onExpandedChange = { picker = if (it) LanguageFontPicker.INTERFACE else null },
        ) {
            AppLanguage.entries.forEach { language ->
                PickerOption(language.nativeName, language == prefs.interfaceLanguage) {
                    picker = null
                    onInterfaceLanguage(language)
                }
            }
        }
        PreferenceDropdown(
            title = strings.text(R.string.ui_answer_language_2b69a),
            value = prefs.answerLanguage.nativeName,
            expanded = picker == LanguageFontPicker.ANSWER,
            tag = "answer-language-selector",
            onExpandedChange = { picker = if (it) LanguageFontPicker.ANSWER else null },
        ) {
            AppLanguage.entries.forEach { language ->
                PickerOption(language.nativeName, language == prefs.answerLanguage) {
                    picker = null
                    onAnswerLanguage(language)
                }
            }
        }
        PreferenceDropdown(
            title = strings.text(R.string.ui_font_b50d4),
            value = fontName(prefs.font),
            expanded = picker == LanguageFontPicker.FONT,
            tag = "font-selector",
            onExpandedChange = { picker = if (it) LanguageFontPicker.FONT else null },
        ) {
            AppFont.entries.forEach { font ->
                PickerOption(fontName(font), font == prefs.font, font) {
                    picker = null
                    onFont(font)
                }
            }
        }
    }
}

/** Anchor choices in the settings card, using the same field and paper menu as model selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceDropdown(
    title: String,
    value: String,
    expanded: Boolean,
    tag: String,
    onExpandedChange: (Boolean) -> Unit,
    options: @Composable ColumnScope.() -> Unit,
) {
    val strings = rememberAppStrings()
    val shape = RoundedCornerShape(10.dp)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (MogeTheme.motionEnabled) 200 else 0), label = "preference-menu-arrow")
    ExposedDropdownMenuBox(expanded, onExpandedChange,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(title) },
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(rotation)) },
            shape = shape,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MogeTheme.paper.scratch,
                unfocusedContainerColor = MogeTheme.paper.scratch,
                unfocusedBorderColor = MogeTheme.paper.cardStroke.copy(alpha = 0.6f),
            ),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag(tag)
                .semantics { stateDescription = strings.text(if (expanded) R.string.ui_expanded_fa83a else R.string.ui_collapsed_5dd9d) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.heightIn(max = 320.dp).selectableGroup()
                .border(1.dp, MogeTheme.paper.cardStroke.copy(alpha = 0.5f), shape),
            content = options,
        )
    }
}

@Composable
private fun PickerOption(label: String, selected: Boolean, font: AppFont? = null, onClick: () -> Unit) {
    val typeface = rememberAppTypeface(font ?: AppFont.SYSTEM)
    DropdownMenuItem(
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = if (font == null) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.bodyLarge.copy(fontFamily = appFontFamily(typeface))) },
        onClick = onClick,
        trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                else MaterialTheme.colorScheme.surface)
            .semantics { this.selected = selected; role = Role.RadioButton },
    )
}

@Composable
internal fun fontName(font: AppFont): String = when (font) {
    AppFont.SYSTEM -> appString(R.string.ui_system_default_52298)
    AppFont.WENKAI -> appString(R.string.ui_lxgw_wenkai_b07d5)
    AppFont.SOURCE_SERIF -> appString(R.string.ui_source_han_serif_5d97f)
    AppFont.ROUNDED -> appString(R.string.ui_resource_han_rounded)
}
