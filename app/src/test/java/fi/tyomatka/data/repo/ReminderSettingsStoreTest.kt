package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderSettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun withStore(block: suspend (DataStore<Preferences>) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFolder().resolve("prefs.preferences_pb")
        }
        block(dataStore)
        scope.cancel()
    }

    @Test
    fun `oletuksena molemmat pois päältä, seitsemältä maanantaista perjantaihin`() = withStore { dataStore ->
        val settings = ReminderSettingsStore(dataStore).settings.first()

        assertFalse(settings.bluetoothEnabled)
        assertTrue(settings.bluetoothDevices.isEmpty())
        assertFalse(settings.timeEnabled)
        assertEquals(7 * 60, settings.minuteOfDay)
        assertEquals(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), settings.days)
    }

    @Test
    fun `muutos säilyy`() = withStore { dataStore ->
        val wanted = ReminderSettings(
            bluetoothEnabled = true,
            bluetoothDevices = setOf("AA:BB:CC:DD:EE:FF"),
            timeEnabled = true,
            minuteOfDay = 6 * 60 + 15,
            days = setOf(SATURDAY),
        )

        ReminderSettingsStore(dataStore).update { wanted }

        assertEquals(wanted, ReminderSettingsStore(dataStore).settings.first())
    }

    @Test
    fun `muutos saa nykyiset asetukset`() = withStore { dataStore ->
        val store = ReminderSettingsStore(dataStore)

        store.update { it.copy(timeEnabled = true) }

        val settings = store.settings.first()
        assertTrue(settings.timeEnabled)
        assertEquals(7 * 60, settings.minuteOfDay, "muut kentät ennallaan")
    }

    @Test
    fun `peräkkäiset muutokset eivät hukkaa aiempaa`() = runBlocking {
        val store = ReminderSettingsStore(MemoryDataStore())

        store.update { it.copy(minuteOfDay = 100) }
        store.update { it.copy(timeEnabled = true) }

        assertEquals(ReminderSettings(minuteOfDay = 100, timeEnabled = true), store.settings.first())
    }

    @Test
    fun `kaikkien päivien poisto säilyy tyhjänä`() = withStore { dataStore ->
        val store = ReminderSettingsStore(dataStore)

        store.update { it.copy(days = emptySet()) }

        assertEquals(emptySet<DayOfWeek>(), store.settings.first().days)
    }

    @Test
    fun `rikkinäinen minuutti palautuu oletukseen ja tuntematon päivä ohitetaan`() = withStore { dataStore ->
        dataStore.edit {
            it[intPreferencesKey("reminder_minute_of_day")] = 5_000
            it[stringSetPreferencesKey("reminder_days")] = setOf("MONDAY", "EI_PAIVA")
        }

        val settings = ReminderSettingsStore(dataStore).settings.first()

        assertEquals(7 * 60, settings.minuteOfDay)
        assertEquals(setOf(MONDAY), settings.days)
    }

    @Test
    fun `yhteys muistuttaa vain valitusta laitteesta ja asetuksen ollessa päällä`() {
        val on = ReminderSettings(bluetoothEnabled = true, bluetoothDevices = setOf("AA:BB"))

        assertTrue(shouldRemindOnConnect(on, "AA:BB"))
        assertFalse(shouldRemindOnConnect(on, "CC:DD"))
        assertFalse(shouldRemindOnConnect(on, null))
        assertFalse(shouldRemindOnConnect(on.copy(bluetoothEnabled = false), "AA:BB"))
    }
}
