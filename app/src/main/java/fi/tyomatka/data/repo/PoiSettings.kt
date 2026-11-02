package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.db.FuelKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Kohteet-valikon kytkimet: luokat, vain raskaalle kalustolle, vain reitin varrella. Samat Kartta-välilehdellä ja Ajo-näkymässä. */
data class PoiSettings(val categories: Set<PoiCategory>, val heavyOnly: Boolean, val routeOnly: Boolean)

/**
 * Oletukset ensimmäisellä käytöllä oletusauton polttoaineesta: diesel on raskasta kalustoa (huoltoasemat ja lepopaikat,
 * raskaan kaluston suodatin päällä), bensiini huoltoasemat, sähkö latauspisteet, kaasu kaasuasemat; ilman autoa huoltoasemat.
 */
fun defaultPoiSettings(fuel: FuelKind?): PoiSettings = when (fuel) {
    FuelKind.DIESEL -> PoiSettings(setOf(PoiCategory.FUEL, PoiCategory.REST), heavyOnly = true, routeOnly = false)
    FuelKind.ELECTRIC -> PoiSettings(setOf(PoiCategory.CHARGING), heavyOnly = false, routeOnly = false)
    FuelKind.GAS -> PoiSettings(setOf(PoiCategory.GAS), heavyOnly = false, routeOnly = false)
    FuelKind.PETROL, FuelKind.ADBLUE, null -> PoiSettings(setOf(PoiCategory.FUEL), heavyOnly = false, routeOnly = false)
}

/** Kuljettajan oma valinta voittaa oletuksen pysyvästi; [settings] on null, kunnes valinta on tehty kerran. */
@Singleton
class PoiSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<PoiSettings?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            if (prefs[KEY_SET] != true) return@map null
            PoiSettings(
                categories = prefs[KEY_CATEGORIES].orEmpty().mapNotNull { name -> PoiCategory.entries.firstOrNull { it.name == name } }.toSet(),
                heavyOnly = prefs[KEY_HEAVY_ONLY] ?: false,
                routeOnly = prefs[KEY_ROUTE_ONLY] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun save(settings: PoiSettings) {
        dataStore.edit {
            it[KEY_SET] = true
            it[KEY_CATEGORIES] = settings.categories.map { c -> c.name }.toSet()
            it[KEY_HEAVY_ONLY] = settings.heavyOnly
            it[KEY_ROUTE_ONLY] = settings.routeOnly
        }
    }

    private companion object {
        val KEY_SET = booleanPreferencesKey("poi_set")
        val KEY_CATEGORIES = stringSetPreferencesKey("poi_categories")
        val KEY_HEAVY_ONLY = booleanPreferencesKey("poi_heavy_only")
        val KEY_ROUTE_ONLY = booleanPreferencesKey("poi_route_only")
    }
}
