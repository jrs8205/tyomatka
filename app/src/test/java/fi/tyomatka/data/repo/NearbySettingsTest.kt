package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.poi.PoiCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class NearbySettingsTest {
    /** DataStoren oma korutiini elää [TestScope.backgroundScope]ssa, jottei testin lopetus jää odottamaan sitä. */
    private fun TestScope.store(storage: MemoryPreferencesStorage = MemoryPreferencesStorage()): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(storage = storage, scope = backgroundScope)

    @Test
    fun `oletus on polttoaine ilman raskas-rajausta`() = runTest {
        assertEquals(NearbySettings(PoiCategory.FUEL, heavyOnly = false), NearbySettingsStore(store()).settings.first())
    }

    @Test
    fun `valinta tallentuu ja luetaan takaisin`() = runTest {
        val storage = MemoryPreferencesStorage()
        NearbySettingsStore(store(storage)).save(NearbySettings(PoiCategory.CHARGING, heavyOnly = true))

        assertEquals(NearbySettings(PoiCategory.CHARGING, heavyOnly = true), NearbySettingsStore(store(storage)).settings.first())
        assertEquals("CHARGING", storage.value[stringPreferencesKey("nearby_category")])
    }

    @Test
    fun `tuntematon lajinimi on polttoaine`() = runTest {
        val storage = MemoryPreferencesStorage()
        storage.value = mutablePreferencesOf(stringPreferencesKey("nearby_category") to "WATER")

        assertEquals(PoiCategory.FUEL, NearbySettingsStore(store(storage)).settings.first().category)
    }

    @Test
    fun `lukuvirhe antaa oletuksen`() = runTest {
        val settings = NearbySettingsStore(store(MemoryPreferencesStorage(readError = IOException("rikki")))).settings.first()

        assertEquals(NearbySettings(), settings)
    }
}
