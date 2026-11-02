package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.db.FuelKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoiSettingsTest {
    private fun TestScope.memoryStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(storage = MemoryPreferencesStorage(), scope = backgroundScope)

    @Test
    fun `oletukset tulevat oletusauton polttoaineesta`() {
        assertEquals(PoiSettings(setOf(PoiCategory.FUEL, PoiCategory.REST), heavyOnly = true, routeOnly = false), defaultPoiSettings(FuelKind.DIESEL))
        assertEquals(PoiSettings(setOf(PoiCategory.FUEL), heavyOnly = false, routeOnly = false), defaultPoiSettings(FuelKind.PETROL))
        assertEquals(PoiSettings(setOf(PoiCategory.CHARGING), heavyOnly = false, routeOnly = false), defaultPoiSettings(FuelKind.ELECTRIC))
        assertEquals(PoiSettings(setOf(PoiCategory.GAS), heavyOnly = false, routeOnly = false), defaultPoiSettings(FuelKind.GAS))
        assertEquals(PoiSettings(setOf(PoiCategory.FUEL), heavyOnly = false, routeOnly = false), defaultPoiSettings(null))
        assertEquals(PoiSettings(setOf(PoiCategory.FUEL), heavyOnly = false, routeOnly = false), defaultPoiSettings(FuelKind.ADBLUE))
    }

    @Test
    fun `asetukset ovat tyhjät ennen ensimmäistä valintaa ja säilyvät sen jälkeen`() = runTest {
        val store = PoiSettingsStore(memoryStore())

        assertNull(store.settings.first())

        store.save(PoiSettings(setOf(PoiCategory.CHARGING, PoiCategory.REST), heavyOnly = false, routeOnly = true))
        assertEquals(PoiSettings(setOf(PoiCategory.CHARGING, PoiCategory.REST), heavyOnly = false, routeOnly = true), store.settings.first())

        store.save(PoiSettings(emptySet(), heavyOnly = true, routeOnly = false))
        assertEquals(PoiSettings(emptySet(), heavyOnly = true, routeOnly = false), store.settings.first())
    }
}
