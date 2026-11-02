package fi.tyomatka.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fi.tyomatka.R
import fi.tyomatka.data.repo.AppSettings
import fi.tyomatka.data.repo.ThemeChoice
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.form.FormSection

@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = Tap(onClick)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun DriveSection(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onOpenVoices: () -> Unit) {
    FormSection(stringResource(R.string.settings_section_drive)) {
        ActionRow(
            title = stringResource(R.string.settings_voice),
            value = stringResource(if (settings.voiceName == null) R.string.settings_voice_default else R.string.settings_voice_chosen),
            onClick = onOpenVoices,
        )
        SwitchRow(stringResource(R.string.settings_guidance_vibration), null, settings.guidanceVibration) { on ->
            onChange { it.copy(guidanceVibration = on) }
        }
        SwitchRow(stringResource(R.string.settings_hide_bar), stringResource(R.string.settings_hide_bar_body), settings.hideBottomBarDriving) { on ->
            onChange { it.copy(hideBottomBarDriving = on) }
        }
        SwitchRow(stringResource(R.string.settings_dim), stringResource(R.string.settings_dim_body), settings.dimInDark) { on ->
            onChange { it.copy(dimInDark = on) }
        }
    }
}

@Composable
internal fun AppearanceSection(
    settings: AppSettings,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val context = LocalContext.current
    FormSection(stringResource(R.string.settings_section_appearance)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_theme_system), theme == ThemeChoice.SYSTEM) { onTheme(ThemeChoice.SYSTEM) }
            RadioRow(stringResource(R.string.settings_theme_light), theme == ThemeChoice.LIGHT) { onTheme(ThemeChoice.LIGHT) }
            RadioRow(stringResource(R.string.settings_theme_dark), theme == ThemeChoice.DARK) { onTheme(ThemeChoice.DARK) }
        }
        SwitchRow(stringResource(R.string.settings_tap_vibration), null, settings.tapVibration) { on ->
            onChange { it.copy(tapVibration = on) }
        }
        ActionRow(
            title = stringResource(R.string.settings_language),
            value = appLocale().let { locale -> locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) } },
            onClick = { context.openSafely(languageIntent(context)) },
        )
    }
}

private fun languageIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
    } else {
        Intent(Settings.ACTION_LOCALE_SETTINGS)
    }

/** Äänilista ja koeääni: moottori elää vain tämän sivun ajan. Valinta soittaa koeäänen heti. */
@Composable
internal fun VoicesContent(selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    DisposableEffect(Unit) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val tts = created
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.language = appLocale()
                engine = tts
                voices = selectableVoices(tts.voiceOptions(appLocale().language))
            } else {
                voices = emptyList()
            }
        }
        onDispose { created.shutdown() }
    }
    val sample = stringResource(R.string.settings_voice_sample)
    fun choose(name: String?) {
        onSelect(name)
        engine?.let { tts ->
            // Kieli palauttaa oletusäänen: moottorin oma oletusääni voi olla väärällä kielellä tai puuttua.
            tts.language = appLocale()
            tts.voices.orEmpty().firstOrNull { it.name == name }?.let { tts.voice = it }
            tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "koeaani")
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_voice_hint), style = MaterialTheme.typography.bodyMedium)
        RadioRow(stringResource(R.string.settings_voice_default), selected == null) { choose(null) }
        voices.orEmpty().forEachIndexed { index, voice ->
            RadioRow(stringResource(R.string.settings_voice_number, index + 1), selected == voice.name) { choose(voice.name) }
        }
        if (voices?.isEmpty() == true) {
            Text(stringResource(R.string.settings_voice_none), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Laitteen puheäänet kielellä [language]; verkkoa tai latausta vaativat merkitään käyttökelvottomiksi. */
internal fun TextToSpeech.voiceOptions(language: String): List<VoiceOption> =
    voices.orEmpty()
        .filter { it.locale.language == language }
        .map { VoiceOption(it.name, usable = !it.isNetworkConnectionRequired && "notInstalled" !in it.features) }

private enum class PermissionState { GRANTED, PARTIAL, DENIED }

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun locationState(context: Context) = when {
    context.granted(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionState.GRANTED
    context.granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionState.PARTIAL
    else -> PermissionState.DENIED
}

private fun bluetoothState(context: Context) =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.granted(Manifest.permission.BLUETOOTH_CONNECT)) {
        PermissionState.GRANTED
    } else {
        PermissionState.DENIED
    }

@Composable
internal fun PermissionsSection(onShowIntro: () -> Unit) {
    val context = LocalContext.current
    var location by remember { mutableStateOf(locationState(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var bluetooth by remember { mutableStateOf(bluetoothState(context)) }
    LifecycleResumeEffect(Unit) {
        location = locationState(context)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        bluetooth = bluetoothState(context)
        onPauseOrDispose {}
    }
    FormSection(stringResource(R.string.settings_section_permissions)) {
        PermissionRow(stringResource(R.string.onboarding_location_title), location)
        PermissionRow(stringResource(R.string.onboarding_notifications_title), if (notifications) PermissionState.GRANTED else PermissionState.DENIED)
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            PermissionRow(stringResource(R.string.settings_permission_bluetooth), bluetooth)
        }
        TextButton(onClick = Tap { context.openSafely(appDetailsIntent(context)) }) {
            Text(stringResource(R.string.onboarding_open_settings))
        }
        ActionRow(stringResource(R.string.settings_show_intro), onClick = onShowIntro)
    }
}

@Composable
private fun PermissionRow(title: String, state: PermissionState) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                when (state) {
                    PermissionState.GRANTED -> R.string.settings_permission_granted
                    PermissionState.PARTIAL -> R.string.settings_permission_coarse
                    PermissionState.DENIED -> R.string.settings_permission_denied
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Omien ilmoitusten poisto: vahvistus ennen poistoa ja tulos rivillä, koska poistoa ei voi perua. */
@Composable
internal fun ForgetReportsRow(state: ForgetState, onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    ActionRow(
        stringResource(R.string.settings_forget_reports),
        stringResource(
            when (state) {
                ForgetState.IDLE -> R.string.settings_forget_reports_body
                ForgetState.RUNNING -> R.string.settings_forget_running
                ForgetState.DONE -> R.string.settings_forget_done
                ForgetState.FAILED -> R.string.settings_forget_failed
            },
        ),
    ) {
        if (state != ForgetState.RUNNING) confirming = true
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_text)) },
            confirmButton = {
                TextButton(
                    onClick = Tap {
                        confirming = false
                        onConfirm()
                    },
                ) { Text(stringResource(R.string.settings_forget_confirm)) }
            },
            dismissButton = { TextButton(onClick = Tap { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
