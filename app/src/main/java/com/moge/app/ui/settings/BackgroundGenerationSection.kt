package com.moge.app.ui.settings

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundGenerationSection(report: suspend () -> String) {
    val strings = rememberAppStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun unrestricted() = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)
    var notifications by remember { mutableStateOf(notificationsAllowed()) }
    var battery by remember { mutableStateOf(unrestricted()) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = notificationsAllowed()
                battery = unrestricted()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))) }
        }
    }
    SettingsSection(strings.text(R.string.ui_background_generation_ea5fa)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.text(R.string.ui_use_background_notifications_cpu_wake_protection_and_availab_7cb1c), style = MaterialTheme.typography.bodyMedium)
            Text(strings.text(R.string.ui_notifications_1_s_battery_2_s_9e23a, if (notifications) strings.text(R.string.ui_allowed_3dec7) else strings.text(R.string.ui_not_allowed_3cbef), if (battery) strings.text(R.string.ui_unrestricted_7fbf9) else strings.text(R.string.ui_system_managed_f2013)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(strings.text(R.string.ui_battery_optimization_may_pause_networking_when_the_screen_is_640e1),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(strings.text(R.string.ui_notification_settings_64676)) }
                TextButton(onClick = {
                    open(if (battery) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")))
                }) { Text(if (battery) strings.text(R.string.ui_background_settings_4138d) else strings.text(R.string.ui_allow_network_with_screen_off_ae15c)) }
                TextButton(onClick = {
                    scope.launch {
                        val text = report()
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(strings.text(R.string.ui_background_generation_diagnostics_99ab0), text))
                    }
                }) { Text(strings.text(R.string.ui_copy_diagnostics_47d09)) }
            }
        }
    }
}
