package fi.tyomatka.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.tyomatka.core.learn.HistoryLearner
import fi.tyomatka.core.model.LearnedStats
import fi.tyomatka.data.db.HistoryDao
import fi.tyomatka.data.db.LegRecordEntity
import fi.tyomatka.data.db.ServiceRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import fi.tyomatka.drive.LearningStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toteumien tallennus ja niistä oppiminen. Opitut estimaatit (nopeuskerroin ja
 * keskimääräinen stoppikesto) säilyvät DataStoressa päivien yli.
 */
@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao,
    private val dataStore: DataStore<Preferences>,
    private val learner: HistoryLearner,
) : LearningStore {

    val learnedStats: Flow<LearnedStats> = dataStore.data.map { it.toStats() }

    override suspend fun currentStats(): LearnedStats {
        resetLegacyFactors()
        return dataStore.data.first().toStats()
    }

    /** Kirjaa ajetun legin toteuma ja päivitä kumpikin nopeuskerroin omasta ennusteestaan. */
    override suspend fun recordLeg(
        dayId: Long,
        toStopId: Long,
        navPredictedSec: Long,
        planPredictedSec: Long?,
        actualSec: Long,
        nowEpochSec: Long,
    ) {
        historyDao.insertLeg(
            LegRecordEntity(
                routeDayId = dayId,
                toStopId = toStopId,
                predictedSec = navPredictedSec,
                actualSec = actualSec,
                recordedAtEpochSec = nowEpochSec,
            )
        )
        dataStore.edit { prefs ->
            var stats = learner.onNavLegCompleted(prefs.toStats(), navPredictedSec, actualSec)
            if (planPredictedSec != null) stats = learner.onLegCompleted(stats, planPredictedSec, actualSec)
            prefs.writeStats(stats)
        }
    }

    /**
     * Ennen OSM-reititystä opittu kerroin kuvasi Googlen liikenteen huomioivia ennusteita, eikä se sovi Valhallalle
     * eikä OSRM:lle: se nollataan kerran. Palveluaika on mitattu aika ja säilyy. Levyvirhe ei estä ennusteita:
     * [toStats] piilottaa vanhat kertoimet jo lukiessaan, ja nollaus yritetään seuraavalla kerralla uudelleen.
     */
    private suspend fun resetLegacyFactors() {
        try {
            if (dataStore.data.first()[KEY_SOURCE] == SOURCE_OSM) return
            dataStore.edit { prefs ->
                if (prefs[KEY_SOURCE] != SOURCE_OSM) {
                    prefs.remove(KEY_SPEED_FACTOR)
                    prefs.remove(KEY_LEG_SAMPLES)
                    prefs.remove(KEY_NAV_SPEED_FACTOR)
                    prefs.remove(KEY_NAV_LEG_SAMPLES)
                    prefs[KEY_SOURCE] = SOURCE_OSM
                }
            }
        } catch (_: IOException) {
            // Kertoimet näkyvät nollattuina silti; kirjoitus onnistuu ehkä myöhemmin.
        }
    }

    /** Kirjaa stoppiin kulunut aika ja päivitä opittu stoppikesto. */
    override suspend fun recordService(dayId: Long, stopId: Long, durationSec: Long, nowEpochSec: Long) {
        historyDao.insertService(
            ServiceRecordEntity(
                routeDayId = dayId,
                stopId = stopId,
                durationSec = durationSec,
                recordedAtEpochSec = nowEpochSec,
            )
        )
        dataStore.edit { prefs ->
            prefs.writeStats(learner.onStopServiced(prefs.toStats(), durationSec))
        }
    }

    /** Ennen nollausta luettu vanha kerroin ei näy ennusteissa: tilastovirta voi olla luettu ennen [currentStats]-kutsua. */
    private fun Preferences.toStats(): LearnedStats {
        val learnedForOsm = this[KEY_SOURCE] == SOURCE_OSM
        return LearnedStats(
            speedFactor = this[KEY_SPEED_FACTOR]?.takeIf { learnedForOsm } ?: 1.0,
            avgServiceSec = this[KEY_AVG_SERVICE] ?: LearnedStats.DEFAULT_SERVICE_SEC,
            serviceSamples = this[KEY_SERVICE_SAMPLES] ?: 0,
            legSamples = this[KEY_LEG_SAMPLES]?.takeIf { learnedForOsm } ?: 0,
            navSpeedFactor = this[KEY_NAV_SPEED_FACTOR]?.takeIf { learnedForOsm } ?: 1.0,
            navLegSamples = this[KEY_NAV_LEG_SAMPLES]?.takeIf { learnedForOsm } ?: 0,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeStats(stats: LearnedStats) {
        this[KEY_SPEED_FACTOR] = stats.speedFactor
        this[KEY_AVG_SERVICE] = stats.avgServiceSec
        this[KEY_SERVICE_SAMPLES] = stats.serviceSamples
        this[KEY_LEG_SAMPLES] = stats.legSamples
        this[KEY_NAV_SPEED_FACTOR] = stats.navSpeedFactor
        this[KEY_NAV_LEG_SAMPLES] = stats.navLegSamples
        this[KEY_SOURCE] = SOURCE_OSM
    }

    private companion object {
        val KEY_SPEED_FACTOR = doublePreferencesKey("speed_factor")
        val KEY_AVG_SERVICE = longPreferencesKey("avg_service_sec")
        val KEY_SERVICE_SAMPLES = intPreferencesKey("service_samples")
        val KEY_LEG_SAMPLES = intPreferencesKey("leg_samples")
        val KEY_NAV_SPEED_FACTOR = doublePreferencesKey("nav_speed_factor")
        val KEY_NAV_LEG_SAMPLES = intPreferencesKey("nav_leg_samples")

        /** Mitä ennusteita vasten kertoimet on opittu; puuttuva arvo = ennen OSM-reititystä. */
        val KEY_SOURCE = stringPreferencesKey("learning_source")
        const val SOURCE_OSM = "osm-1"
    }
}
