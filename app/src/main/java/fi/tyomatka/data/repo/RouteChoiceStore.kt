package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.core.nav.routeChoiceForDay
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ajon reittivalinta Kartta-sivulta. Lyhin on voimassa valintapäivän ([routeChoiceForDay]) ja palaa keskiyöllä
 * nopeimmaksi, myös jos sovellus on auki yön yli. Levyvirhe antaa nopeimman reitin, eikä valinnan tallennus kaada
 * sovellusta. [days] antaa kuluvan epoch-päivän.
 */
@Singleton
class RouteChoiceStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val days: Flow<Long>,
) {
    @Inject constructor(dataStore: DataStore<Preferences>) : this(dataStore, localDays())

    val choice: Flow<RouteChoice> = combine(
        dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it },
        days,
    ) { prefs, today -> routeChoiceForDay(RouteChoice.of(prefs[KEY_CHOICE]), prefs[KEY_DAY], today) }
        .distinctUntilChanged()

    suspend fun set(choice: RouteChoice) {
        val today = days.first()
        try {
            dataStore.edit {
                it[KEY_CHOICE] = choice.key
                it[KEY_DAY] = today
            }
        } catch (_: IOException) {
            // Valinta jää voimaan tähän asti; sovellus jatkaa.
        }
    }

    private companion object {
        val KEY_CHOICE = stringPreferencesKey("route_choice")
        val KEY_DAY = longPreferencesKey("route_choice_day")

        /** Kuluva päivä laitteen aikavyöhykkeellä; uusi arvo heti keskiyön jälkeen. */
        fun localDays(): Flow<Long> = flow {
            while (currentCoroutineContext().isActive) {
                emit(LocalDate.now().toEpochDay())
                val zone = ZoneId.systemDefault()
                val now = ZonedDateTime.now(zone)
                delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toMillis() + 1_000)
            }
        }.distinctUntilChanged()
    }
}
