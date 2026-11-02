package fi.tyomatka.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.BuildConfig
import fi.tyomatka.R
import fi.tyomatka.di.defaultServerMap
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.DisplayLanguage
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.FormSection

private enum class SettingsPage { MAIN, DEVICES, VOICES, SERVERS, SOURCES, LICENSES }

/**
 * Asetukset koko näytön ikkunassa: yksi vieritettävä sivu osioina ja alasivut. Takaisin-ele palaa edelliselle tasolle.
 * Viennin ja tuonnin tulos näkyy Työmatka-sivulla, joten [onImport] ja [onExportBackup] sulkevat ikkunan.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onImport: () -> Unit,
    onExportBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val app by viewModel.app.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val forget by viewModel.forget.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.resetForget() }
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    val uriHandler = LocalUriHandler.current
    val mainScroll = rememberScrollState()
    val back = { if (page == SettingsPage.MAIN) onClose() else page = SettingsPage.MAIN }

    FullScreenDialog(onDismissRequest = onClose, dismissOnBackPress = false) {
        BackHandler(onBack = back)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                ) {
                    IconButton(onClick = Tap(back)) {
                        if (page == SettingsPage.MAIN) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_close))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    }
                    Text(
                        stringResource(
                            when (page) {
                                SettingsPage.MAIN -> R.string.settings_title
                                SettingsPage.DEVICES -> R.string.reminder_devices_title
                                SettingsPage.VOICES -> R.string.settings_voice
                                SettingsPage.SERVERS -> R.string.settings_servers_row
                                SettingsPage.SOURCES -> R.string.settings_sources
                                SettingsPage.LICENSES -> R.string.settings_licenses
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
                when (page) {
                    SettingsPage.MAIN -> Column(
                        Modifier.fillMaxSize().verticalScroll(mainScroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ReminderSection(
                            settings = reminders,
                            onChange = viewModel::updateReminders,
                            onOpenDevices = { page = SettingsPage.DEVICES },
                        )
                        DriveSection(app, viewModel::updateApp, onOpenVoices = { page = SettingsPage.VOICES })
                        AppearanceSection(app, theme, viewModel::setTheme, viewModel::updateApp)
                        FormSection(stringResource(R.string.settings_section_data)) {
                            ActionRow(stringResource(R.string.settings_export_backup), stringResource(R.string.settings_export_backup_body), onExportBackup)
                            ForgetReportsRow(forget, viewModel::forgetReports)
                            ActionRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_body), onImport)
                        }
                        PermissionsSection(onShowIntro = viewModel::showIntro)
                        FormSection(stringResource(R.string.settings_section_servers)) {
                            ActionRow(stringResource(R.string.settings_servers_row), stringResource(R.string.settings_servers_row_body)) {
                                page = SettingsPage.SERVERS
                            }
                        }
                        FormSection(stringResource(R.string.settings_section_about)) {
                            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
                            ActionRow(stringResource(R.string.settings_sources)) { page = SettingsPage.SOURCES }
                            ActionRow(stringResource(R.string.settings_licenses)) { page = SettingsPage.LICENSES }
                            ActionRow(stringResource(R.string.settings_privacy)) {
                                runCatching { uriHandler.openUri(privacyUrl(DisplayLanguage.current())) }
                            }
                            ActionRow(stringResource(R.string.settings_contact), CONTACT_EMAIL) {
                                runCatching { uriHandler.openUri("mailto:$CONTACT_EMAIL") }
                            }
                            ActionRow(stringResource(R.string.settings_source_code), SOURCE_CODE_URL) {
                                runCatching { uriHandler.openUri(SOURCE_CODE_URL) }
                            }
                        }
                    }
                    SettingsPage.SOURCES -> SourcesContent(Modifier.fillMaxSize().padding(16.dp))
                    SettingsPage.LICENSES -> LicensesContent(Modifier.fillMaxSize().padding(16.dp))
                    SettingsPage.SERVERS -> {
                        var stored by remember { mutableStateOf(viewModel.storedServers()) }
                        ServersContent(
                            stored = stored,
                            defaults = defaultServerMap(),
                            onSave = {
                                viewModel.saveServers(it)
                                stored = viewModel.storedServers()
                            },
                            onReset = {
                                viewModel.resetServers()
                                stored = viewModel.storedServers()
                            },
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                        )
                    }
                    SettingsPage.VOICES -> VoicesContent(
                        selected = app.voiceName,
                        onSelect = { name -> viewModel.updateApp { it.copy(voiceName = name) } },
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                    SettingsPage.DEVICES -> BluetoothDevicesContent(
                        selected = reminders.bluetoothDevices,
                        onChange = { devices -> viewModel.updateReminders { it.copy(bluetoothDevices = devices) } },
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
            }
        }
    }
}

private const val SOURCE_CODE_URL = "https://github.com/jrs8205/tyomatka"
