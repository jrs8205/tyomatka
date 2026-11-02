package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seuraavan ajon auto Autot-näkymästä: rekisterinumero, valintahetki ja valintahetken viimeisin
 * työjakso pysyvästi, jotta valinta pätee vielä sovelluksen uudelleenkäynnistyksen jälkeen. Ne ratkaisevat, onko
 * valinta yhä voimassa ([defaultPlateWithChoice]). Kirjoitusvirhe ([IOException]) nousee kutsujalle, joka kertoo siitä.
 */
@Singleton
class NextCarStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val choice: Flow<NextCarChoice?> = dataStore.data
        // Rikkinäinen tai lukukelvoton tiedosto on tyhjä valinta, ei kaatuminen: valinta on mukavuus, ei kirjanpitoa.
        // Ohimenevä virhe (tiedosto kesken kirjoituksen) ei saa lopettaa virtaa, jolloin myöhempi valinta ei näkyisi
        // ennen uudelleenkäynnistystä: tyhjä valinta heti, ja luku yritetään uudelleen lyhyen viiveen jälkeen.
        .retryWhen { cause, attempt ->
            if (cause !is IOException || attempt >= READ_RETRIES) return@retryWhen false
            emit(emptyPreferences())
            delay(READ_RETRY_DELAY_MS)
            true
        }
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val plate = prefs[KEY_PLATE] ?: return@map null
            val chosenAt = prefs[KEY_CHOSEN_AT] ?: return@map null
            NextCarChoice(plate, chosenAt, prefs[KEY_LATEST_SEGMENT])
        }
        // Sama tiedosto kantaa opitut estimaatit, jotka päivittyvät ajon aikana; ne eivät saa laskea oletusautoa uudelleen.
        .distinctUntilChanged()

    /** [latestSegmentId]: viimeisin työjakso valintahetkellä ([latestWorkSegment]), null ilman jaksoja. */
    suspend fun choose(plate: String, nowEpochSec: Long, latestSegmentId: Long?) {
        dataStore.edit {
            it[KEY_PLATE] = plate
            it[KEY_CHOSEN_AT] = nowEpochSec
            if (latestSegmentId != null) it[KEY_LATEST_SEGMENT] = latestSegmentId else it.remove(KEY_LATEST_SEGMENT)
        }
    }

    /** Valitun auton poisto: valinta ei saa palata, jos sama rekisterinumero lisätään uudelleen. */
    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_PLATE)
            it.remove(KEY_CHOSEN_AT)
            it.remove(KEY_LATEST_SEGMENT)
        }
    }

    private companion object {
        val KEY_PLATE = stringPreferencesKey("next_car_plate")
        val KEY_CHOSEN_AT = longPreferencesKey("next_car_chosen_at")
        val KEY_LATEST_SEGMENT = longPreferencesKey("next_car_latest_segment")

        /** Lukuvirheen jälkeiset uudet yritykset ja niiden väli; pysyvästi rikkinäinen tiedosto jää tyhjäksi valinnaksi. */
        const val READ_RETRIES = 3L
        const val READ_RETRY_DELAY_MS = 1_000L
    }
}
