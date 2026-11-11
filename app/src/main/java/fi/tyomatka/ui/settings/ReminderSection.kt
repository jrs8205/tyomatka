package fi.tyomatka.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fi.tyomatka.R
import fi.tyomatka.data.repo.ReminderSettings
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.form.FormSection
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import fi.tyomatka.ui.components.AppTimePicker
import fi.tyomatka.ui.components.AppFilterChip

/** Asetussivua ei välttämättä ole kaikissa puhelimissa, eikä se saa kaataa sovellusta. */
internal fun Context.openSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

internal fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun grantedReminderPermissions(context: Context): Set<String> = buildSet {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun hasBluetooth(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

private fun bluetoothOn(context: Context) =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Ilman lupaa tai Bluetoothia pariluettelo on tyhjä, vaikka laitteet ovat yhä paritettuja. */
private fun pairedListAvailable(context: Context) =
    bluetoothOn(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))

/** Parilaitteet; ilman lupaa tai Bluetoothia tyhjä. */
@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BondedDevice> = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .map { BondedDevice(it.address, it.name) }
}.getOrDefault(emptyList())

private enum class ReminderKind { BLUETOOTH, TIME }

@Composable
internal fun ReminderSection(
    settings: ReminderSettings,
    onChange: ((ReminderSettings) -> ReminderSettings) -> Unit,
    onOpenDevices: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(grantedReminderPermissions(context)) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var radioOn by remember { mutableStateOf(bluetoothOn(context)) }
    var pending by remember { mutableStateOf<ReminderKind?>(null) }
    var bluetoothDenied by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Manifest.permission.BLUETOOTH_CONNECT in granted

    fun enable(kind: ReminderKind, bluetoothAllowed: Boolean) {
        when (kind) {
            ReminderKind.BLUETOOTH -> if (bluetoothAllowed) {
                bluetoothDenied = false
                onChange { it.copy(bluetoothEnabled = true) }
                if (settings.bluetoothDevices.isEmpty()) onOpenDevices()
            } else {
                bluetoothDenied = true
            }
            ReminderKind.TIME -> onChange { it.copy(timeEnabled = true) }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = grantedReminderPermissions(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Manifest.permission.BLUETOOTH_CONNECT in granted
        pending?.let { enable(it, allowed) }
        pending = null
    }
    LifecycleResumeEffect(Unit) {
        granted = grantedReminderPermissions(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        radioOn = bluetoothOn(context)
        onPauseOrDispose {}
    }

    fun request(kind: ReminderKind) {
        val missing = remindersPermissions(Build.VERSION.SDK_INT, kind == ReminderKind.BLUETOOTH, granted)
        if (missing.isEmpty()) {
            enable(kind, bluetoothGranted)
        } else {
            pending = kind
            launcher.launch(missing.toTypedArray())
        }
    }

    FormSection(stringResource(R.string.settings_section_reminders)) {
        if (hasBluetooth(context)) {
            SwitchRow(
                title = stringResource(R.string.reminder_bluetooth_title),
                body = stringResource(R.string.reminder_bluetooth_body),
                checked = settings.bluetoothEnabled,
            ) { on -> if (on) request(ReminderKind.BLUETOOTH) else onChange { it.copy(bluetoothEnabled = false) } }
            if (settings.bluetoothEnabled) {
                ActionRow(
                    title = stringResource(R.string.reminder_devices),
                    value = if (settings.bluetoothDevices.isEmpty()) {
                        stringResource(R.string.reminder_devices_none)
                    } else {
                        stringResource(R.string.reminder_devices_count, settings.bluetoothDevices.size)
                    },
                    onClick = onOpenDevices,
                )
                if (!radioOn) HelperLine(stringResource(R.string.reminder_bluetooth_off), HelperTone.ERROR)
            }
            if ((bluetoothDenied || settings.bluetoothEnabled) && !bluetoothGranted) {
                HelperLine(stringResource(R.string.reminder_bluetooth_denied), HelperTone.ERROR)
                TextButton(onClick = Tap { context.openSafely(appDetailsIntent(context)) }) {
                    Text(stringResource(R.string.onboarding_open_settings))
                }
            }
            HorizontalDivider()
        }
        SwitchRow(
            title = stringResource(R.string.reminder_time_title),
            body = stringResource(R.string.reminder_time_body),
            checked = settings.timeEnabled,
        ) { on -> if (on) request(ReminderKind.TIME) else onChange { it.copy(timeEnabled = false) } }
        if (settings.timeEnabled) {
            ActionRow(
                title = stringResource(R.string.reminder_time_at),
                value = formatMinuteOfDay(settings.minuteOfDay),
                onClick = { pickTime = true },
            )
            DayChips(settings.days) { days -> onChange { it.copy(days = days) } }
            if (settings.days.isEmpty()) HelperLine(stringResource(R.string.reminder_no_days), HelperTone.ERROR)
        }
        if ((settings.bluetoothEnabled || settings.timeEnabled) && !notificationsOn) {
            HelperLine(stringResource(R.string.reminder_notifications_denied), HelperTone.ERROR)
            TextButton(onClick = Tap { context.openSafely(appDetailsIntent(context)) }) {
                Text(stringResource(R.string.onboarding_open_settings))
            }
        }
    }

    if (pickTime) {
        ReminderTimeDialog(
            minuteOfDay = settings.minuteOfDay,
            onSet = { minute ->
                onChange { it.copy(minuteOfDay = minute) }
                pickTime = false
            },
            onDismiss = { pickTime = false },
        )
    }
}

private fun formatMinuteOfDay(minuteOfDay: Int): String =
    LocalTime.ofSecondOfDay(minuteOfDay * 60L).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale()))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            val label = day.getDisplayName(TextStyle.SHORT, appLocale()).replaceFirstChar { it.uppercase(appLocale()) }
            AppFilterChip(
                selected = day in days,
                onClick = Tap { onChange(if (day in days) days - day else days + day) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(minuteOfDay: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_time_at)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { AppTimePicker(state = state) } },
        confirmButton = {
            TextButton(onClick = Tap { onSet(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Valitaan yksi tai useampi parilaite. Poistettu laite näkyy, kunnes valinta poistetaan. */
@Composable
internal fun BluetoothDevicesContent(selected: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bonded by remember { mutableStateOf(bondedDevices(context)) }
    var available by remember { mutableStateOf(pairedListAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        bonded = bondedDevices(context)
        available = pairedListAvailable(context)
        onPauseOrDispose {}
    }
    val rows = deviceRows(bonded, selected, pairedKnown = available)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                when {
                    !available -> R.string.reminder_devices_unavailable
                    rows.isEmpty() -> R.string.reminder_devices_empty
                    else -> R.string.reminder_devices_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = Tap { onChange(if (row.selected) selected - row.address else selected + row.address) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.selected, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MaterialTheme.typography.bodyLarge)
                    if (!row.paired) {
                        Text(
                            stringResource(R.string.reminder_device_unpaired),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
