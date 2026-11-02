package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/** Työpäivämuistutuksen valinnat. Molemmat tavat ovat oletuksena pois päältä. */
data class ReminderSettings(
    val bluetoothEnabled: Boolean = false,
    /** Valittujen laitteiden osoitteet; pysyvät puhelimessa. */
    val bluetoothDevices: Set<String> = emptySet(),
    val timeEnabled: Boolean = false,
    val minuteOfDay: Int = DEFAULT_MINUTE_OF_DAY,
    val days: Set<DayOfWeek> = DEFAULT_DAYS,
)

private const val DEFAULT_MINUTE_OF_DAY = 7 * 60
private val DEFAULT_DAYS = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

fun shouldRemindOnConnect(settings: ReminderSettings, address: String?): Boolean =
    settings.bluetoothEnabled && address != null && address in settings.bluetoothDevices

@Singleton
class ReminderSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<ReminderSettings> = dataStore.data.map(::read)

    suspend fun update(change: (ReminderSettings) -> ReminderSettings) {
        dataStore.edit { prefs ->
            val next = change(read(prefs))
            prefs[KEY_BLUETOOTH] = next.bluetoothEnabled
            prefs[KEY_DEVICES] = next.bluetoothDevices
            prefs[KEY_TIME] = next.timeEnabled
            prefs[KEY_MINUTE] = next.minuteOfDay
            prefs[KEY_DAYS] = next.days.map { it.name }.toSet()
        }
    }

    private fun read(prefs: Preferences) = ReminderSettings(
        bluetoothEnabled = prefs[KEY_BLUETOOTH] ?: false,
        bluetoothDevices = prefs[KEY_DEVICES] ?: emptySet(),
        timeEnabled = prefs[KEY_TIME] ?: false,
        minuteOfDay = prefs[KEY_MINUTE]?.takeIf { it in 0 until 24 * 60 } ?: DEFAULT_MINUTE_OF_DAY,
        days = prefs[KEY_DAYS]?.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }?.toSet() ?: DEFAULT_DAYS,
    )

    private companion object {
        val KEY_BLUETOOTH = booleanPreferencesKey("reminder_bluetooth")
        val KEY_DEVICES = stringSetPreferencesKey("reminder_bluetooth_devices")
        val KEY_TIME = booleanPreferencesKey("reminder_time")
        val KEY_MINUTE = intPreferencesKey("reminder_minute_of_day")
        val KEY_DAYS = stringSetPreferencesKey("reminder_days")
    }
}
