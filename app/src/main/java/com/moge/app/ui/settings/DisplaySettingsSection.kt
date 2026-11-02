package com.moge.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.moge.app.R
import com.moge.app.data.prefs.DisplaySettings
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.localization.rememberAppStrings

@Composable
internal fun DisplaySettingsSection(prefs: UserSettings, onBodyTextSize: (Int) -> Unit, onUiScale: (Int) -> Unit) {
    val strings = rememberAppStrings()
    var bodyExpanded by rememberSaveable { mutableStateOf(false) }
    var scaleExpanded by rememberSaveable { mutableStateOf(false) }
    SettingsSection(strings.text(R.string.ui_display_settings)) {
        PreferenceDropdown(strings.text(R.string.ui_body_text_size), prefs.bodyTextSizeSp.toString(), bodyExpanded,
            "body-text-size-selector", { bodyExpanded = it; if (it) scaleExpanded = false }) {
            DisplaySettings.bodyTextSizes.forEach { size ->
                PickerOption(size.toString(), size == prefs.bodyTextSizeSp) {
                    bodyExpanded = false
                    onBodyTextSize(size)
                }
            }
        }
        PreferenceDropdown(strings.text(R.string.ui_interface_scale), "${prefs.uiScalePercent}%", scaleExpanded,
            "interface-scale-selector", { scaleExpanded = it; if (it) bodyExpanded = false }) {
            DisplaySettings.uiScalePercents.forEach { percent ->
                PickerOption("$percent%", percent == prefs.uiScalePercent) {
                    scaleExpanded = false
                    onUiScale(percent)
                }
            }
        }
    }
}
