package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.nav.RouteChoice
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Ajon reittivalinta säilyy käynnistysten yli. Levyvirhe antaa nopeimman reitin, eikä valinnan tallennus kaada sovellusta. */
@Singleton
class RouteChoiceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val choice: Flow<RouteChoice> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { RouteChoice.of(it[KEY_CHOICE]) }

    suspend fun set(choice: RouteChoice) {
        try {
            dataStore.edit { it[KEY_CHOICE] = choice.key }
        } catch (_: IOException) {
            // Valinta jää voimaan tähän asti; sovellus jatkaa.
        }
    }

    private companion object {
        val KEY_CHOICE = stringPreferencesKey("route_choice")
    }
}
