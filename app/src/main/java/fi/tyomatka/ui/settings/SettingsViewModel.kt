package fi.tyomatka.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.tyomatka.data.repo.AppSettings
import fi.tyomatka.data.repo.AppSettingsStore
import fi.tyomatka.data.repo.OnboardingStore
import fi.tyomatka.data.repo.ReminderSettings
import fi.tyomatka.data.repo.ReminderSettingsStore
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.data.repo.ServerKind
import fi.tyomatka.data.repo.SyncSettings
import fi.tyomatka.data.repo.ThemeChoice
import fi.tyomatka.ui.theme.applyTheme
import fi.tyomatka.work.WorkReminderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ForgetState { IDLE, RUNNING, DONE, FAILED }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val reminderStore: ReminderSettingsStore,
    private val reminderManager: WorkReminderManager,
    private val appSettingsStore: AppSettingsStore,
    private val syncSettings: SyncSettings,
    private val onboardingStore: OnboardingStore,
    private val reports: ReportRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val reminders: StateFlow<ReminderSettings> =
        reminderStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())

    val app: StateFlow<AppSettings> =
        appSettingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _theme = MutableStateFlow(syncSettings.theme)
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _forget = MutableStateFlow(ForgetState.IDLE)
    val forget: StateFlow<ForgetState> = _forget.asStateFlow()

    /** Asetukset avataan uudelleen: edellisen poiston tulos ei enää kerro nykytilasta. */
    fun resetForget() {
        if (_forget.value != ForgetState.RUNNING) _forget.value = ForgetState.IDLE
    }

    fun forgetReports() {
        if (_forget.value == ForgetState.RUNNING) return
        _forget.value = ForgetState.RUNNING
        viewModelScope.launch { _forget.value = if (reports.forgetMine()) ForgetState.DONE else ForgetState.FAILED }
    }

    /** Muutos tallennetaan ja kellonaikamuistutus ajastetaan sen mukaan. */
    fun updateReminders(change: (ReminderSettings) -> ReminderSettings) {
        viewModelScope.launch {
            reminderStore.update(change)
            reminderManager.reschedule()
        }
    }

    fun updateApp(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { appSettingsStore.update(change) }
    }

    fun setTheme(choice: ThemeChoice) {
        syncSettings.theme = choice
        _theme.value = choice
        applyTheme(context, choice)
    }

    fun storedServers(): Map<ServerKind, String> = syncSettings.storedServers()

    /** Kelvottomat kentät estää asetussivu; varmistus täällä pitää tallennetun arvon ehjänä. */
    fun saveServers(values: Map<ServerKind, String>) {
        values.forEach { (kind, text) -> syncSettings.setServer(kind, text) }
    }

    fun resetServers() = syncSettings.resetServers()

    fun showIntro() {
        viewModelScope.launch { onboardingStore.reset() }
    }
}
