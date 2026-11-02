package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.poi.PoiCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Kohdelistan muistettavat valinnat: laji ja Raskas-rajaus (kartan asetukset eivät muutu). */
data class NearbySettings(val category: PoiCategory = PoiCategory.FUEL, val heavyOnly: Boolean = false)

@Singleton
class NearbySettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Lukuvirhe tai tuntematon laji antaa oletuksen, jottei lista jää aukeamatta. */
    val settings: Flow<NearbySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            NearbySettings(
                category = prefs[KEY_CATEGORY]?.let { name -> PoiCategory.entries.firstOrNull { it.name == name } } ?: PoiCategory.FUEL,
                heavyOnly = prefs[KEY_HEAVY] ?: false,
            )
        }

    suspend fun save(settings: NearbySettings) {
        dataStore.edit {
            it[KEY_CATEGORY] = settings.category.name
            it[KEY_HEAVY] = settings.heavyOnly
        }
    }

    private companion object {
        val KEY_CATEGORY = stringPreferencesKey("nearby_category")
        val KEY_HEAVY = booleanPreferencesKey("nearby_heavy")
    }
}
