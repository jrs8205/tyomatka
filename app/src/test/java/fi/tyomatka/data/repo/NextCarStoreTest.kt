package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NextCarStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** DataStoren oma korutiini elää [TestScope.backgroundScope]ssa, jottei testin lopetus jää odottamaan sitä. */
    private fun TestScope.memoryStore(storage: MemoryPreferencesStorage = MemoryPreferencesStorage()): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(storage = storage, scope = backgroundScope)

    @Test
    fun `valinta on tyhjä, kunnes auto valitaan, ja säilyy tiedostossa rekisterinumeroineen, hetkineen ja jaksoineen`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFolder().resolve("prefs.preferences_pb")
        }
        val store = NextCarStore(dataStore)

        assertNull(store.choice.first())
        store.choose("EVO-456", nowEpochSec = 1_700_000_000, latestSegmentId = 42)
        assertEquals(NextCarChoice("EVO-456", 1_700_000_000, 42), store.choice.first())
        scope.cancel()
    }

    @Test
    fun `valinta ilman työjaksoja tallentuu ilman jakson tunnistetta, ja tyhjennys poistaa valinnan`() = runTest {
        val store = NextCarStore(memoryStore())

        store.choose("EVO-456", nowEpochSec = 1_700_000_000, latestSegmentId = null)
        assertEquals(NextCarChoice("EVO-456", 1_700_000_000, null), store.choice.first())
        // Uusi valinta jakson jälkeen korvaa tunnisteen; tyhjennys (auton poisto) poistaa koko valinnan.
        store.choose("ABC-123", nowEpochSec = 1_700_000_500, latestSegmentId = 7)
        assertEquals(NextCarChoice("ABC-123", 1_700_000_500, 7), store.choice.first())
        store.clear()
        assertNull(store.choice.first())
    }

    @Test
    fun `lukuvirhe on tyhjä valinta eikä kaada`() = runTest {
        val store = NextCarStore(memoryStore(MemoryPreferencesStorage(readError = IOException("rikki"))))
        assertNull(store.choice.first())
    }

    @Test
    fun `ohimenevä lukuvirhe ei lopeta valinnan virtaa`() = runTest(UnconfinedTestDispatcher()) {
        // Ensimmäinen luku epäonnistuu (tiedosto kesken kirjoituksen): tyhjä valinta heti, luku uudelleen hetken päästä,
        // ja myöhempi valinta tulee yhä virrasta.
        val storage = MemoryPreferencesStorage(readError = IOException("kesken"), failedReads = 1)
        val store = NextCarStore(memoryStore(storage))
        val seen = mutableListOf<NextCarChoice?>()
        val collector = backgroundScope.launch { store.choice.toList(seen) }
        runCurrent()
        assertEquals(listOf<NextCarChoice?>(null), seen)
        assertEquals(1, storage.reads)

        // Uusi yritys viiveen jälkeen lukee tiedoston uudelleen (advanceUntilIdle ei etene taustakeräilijän viivettä).
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, storage.reads)
        store.choose("EVO-456", nowEpochSec = 1_700_000_000, latestSegmentId = null)
        runCurrent()

        assertEquals(listOf(null, NextCarChoice("EVO-456", 1_700_000_000, null)), seen)
        collector.cancel()
    }

    @Test
    fun `kirjoitusvirhe nousee kutsujalle`() = runTest {
        // Lukuvirhe estää myös kirjoituksen: DataStore lukee nykyisen arvon ennen muutosta.
        val store = NextCarStore(memoryStore(MemoryPreferencesStorage(readError = IOException("rikki"))))
        assertFailsWith<IOException> { store.choose("EVO-456", nowEpochSec = 1, latestSegmentId = null) }
    }

    @Test
    fun `saman tiedoston muut kirjoitukset eivät toista valintaa`() = runTest(UnconfinedTestDispatcher()) {
        val dataStore = memoryStore()
        val store = NextCarStore(dataStore)
        val seen = mutableListOf<NextCarChoice?>()
        val collector = backgroundScope.launch { store.choice.toList(seen) }

        store.choose("EVO-456", nowEpochSec = 1_700_000_000, latestSegmentId = null)
        // Opitut estimaatit kirjoitetaan samaan tiedostoon ajon aikana.
        dataStore.edit { it[intPreferencesKey("leg_samples")] = 3 }
        dataStore.edit { it[intPreferencesKey("leg_samples")] = 4 }
        yield()

        assertEquals(listOf(null, NextCarChoice("EVO-456", 1_700_000_000, null)), seen)
        collector.cancel()
    }
}
