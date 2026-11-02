package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Onko ensikäynnistyksen esittely käyty läpi. */
@Singleton
class OnboardingStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val isDone: Flow<Boolean> = dataStore.data.map { it[KEY_DONE] ?: false }

    /** Asetuksista: esittely näytetään uudelleen. Ei muuta tietoja. */
    suspend fun reset() {
        dataStore.edit { it[KEY_DONE] = false }
    }

    suspend fun markDone() {
        dataStore.edit { it[KEY_DONE] = true }
    }

    private companion object {
        val KEY_DONE = booleanPreferencesKey("onboarding_done")
    }
}
