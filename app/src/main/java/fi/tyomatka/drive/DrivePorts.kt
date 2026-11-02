package fi.tyomatka.drive

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.LearnedStats
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.RoutePlan
import kotlinx.coroutines.flow.Flow

/**
 * Ajosession tarvitsema osa reitti- ja stoppivarastosta. Rajapinta on
 * olemassa, jotta [DriveSessionManager] testataan JVM:ssä muistivarastolla
 * ilman Roomia.
 */
interface DriveStore {
    suspend fun day(dayId: Long): RouteDayEntity?

    /** Päivän aloitusaika, vain kun sitä ei vielä ole. */
    suspend fun markDayStarted(dayId: Long, nowEpochSec: Long)

    suspend fun markDayFinished(dayId: Long, nowEpochSec: Long)
    fun observeStops(dayId: Long): Flow<List<StopEntity>>
    suspend fun stop(stopId: Long): StopEntity?
    suspend fun setStatus(stopId: Long, status: StopStatus)
    suspend fun markStopArrived(stopId: Long, arrivedAtEpochSec: Long)
    suspend fun markStopDone(stopId: Long, departedAtEpochSec: Long)
    suspend fun removeStop(stopId: Long)
    suspend fun remainingStops(dayId: Long): List<StopEntity>
    suspend fun arrivedStop(dayId: Long): StopEntity?
    suspend fun doneCount(dayId: Long): Int
    suspend fun totalCount(dayId: Long): Int
    fun toPlanned(entity: StopEntity, dateEpochDay: Long): PlannedStop
    suspend fun routePlan(
        dayId: Long,
        origin: GeoPoint?,
        stops: List<StopEntity>,
        dateEpochDay: Long,
        maxAgeSec: Long = PLAN_MAX_AGE_SEC,
    ): RoutePlan
    suspend fun cachedPlan(dayId: Long): RoutePlan?
    suspend fun learnedLegDurations(stops: List<StopEntity>, base: List<Long>, speedFactor: Double): List<Long>
    suspend fun recordLegStat(from: StopEntity, to: StopEntity, actualSec: Long, nowEpochSec: Long)

    companion object {
        /** Reittisuunnitelma haetaan uudelleen, kun se on tätä vanhempi. */
        const val PLAN_MAX_AGE_SEC = 30 * 60L
    }
}

/** Oppimisdatan tallennus ajosession aikana. */
interface LearningStore {
    suspend fun currentStats(): LearnedStats
    /**
     * Ajetun legin toteuma. [navPredictedSec] on navigoinnin ennuste legin alussa, [planPredictedSec] päivän
     * suunnitelman ennuste samalle legille, jos se tiedetään.
     */
    suspend fun recordLeg(
        dayId: Long,
        toStopId: Long,
        navPredictedSec: Long,
        planPredictedSec: Long?,
        actualSec: Long,
        nowEpochSec: Long,
    )
    suspend fun recordService(dayId: Long, stopId: Long, durationSec: Long, nowEpochSec: Long)
}

/** Käyttäjälle näytettävät ajosession virheviestit. */
enum class DriveMessage { NAV_START_FAILED }

/** Android-puolen sivuvaikutukset, joita ajosessio tarvitsee. */
interface DriveHost {
    fun message(kind: DriveMessage, detail: String): String

    /** Paneelin virheteksti, kun nykyisen kohteen reittiä ei saatu: "Reititys epäonnistui: <syy>". */
    fun routingFailureText(failure: RouteFailure): String
    fun startNavigationService()
    fun stopNavigationService()
    suspend fun lastKnownLocation(): GeoPoint?
}
