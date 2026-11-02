package fi.tyomatka.data.repo

import fi.tyomatka.core.api.RouteOverviewSource
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlannedStop
import androidx.room.withTransaction
import fi.tyomatka.data.db.AppDatabase
import fi.tyomatka.data.db.LegStatDao
import fi.tyomatka.data.db.LegStatEntity
import fi.tyomatka.data.db.MatrixCacheDao
import fi.tyomatka.data.db.MatrixCacheEntity
import fi.tyomatka.data.db.RouteDayDao
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.SavedAddressDao
import fi.tyomatka.data.db.SavedAddressEntity
import fi.tyomatka.data.db.StopDao
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.db.legKey
import fi.tyomatka.drive.DriveStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Päivän reittisuunnitelma kuljettajan omassa ajojärjestyksessä: polyline
 * kartalle sekä legien kestot ja matkat ETA-laskentaan. Legi [i] on ajo
 * stopille i edellisestä pisteestä (lähtöpaikka tai stoppi i-1); ilman
 * lähtöpaikkaa legi 0 on 0 s ja matka -1 (ei tiedossa).
 */
data class RoutePlan(
    val origin: GeoPoint?,
    val stopIds: List<Long>,
    val legDurationsSec: List<Long>,
    val legDistancesM: List<Long>,
    val encodedPolyline: String,
    val totalDurationSec: Long,
    val fetchedAtEpochSec: Long,
)

/** Stopin lisäyksen tulos: lisätty tai niputettu olemassa olevaan osoitteeseen. */
sealed interface AddStopResult {
    data class Added(val stop: StopEntity) : AddStopResult

    /** Sama osoite on jo päivän hoitamattomissa stopeissa — tuplaa ei luotu. */
    data class Duplicate(val existing: StopEntity) : AddStopResult
}

@Serializable
private data class PlanDto(
    val originLat: Double? = null,
    val originLng: Double? = null,
    val stopIds: List<Long>,
    val legDurationsSec: List<Long>,
    val legDistancesM: List<Long>,
    val encodedPolyline: String,
    val totalDurationSec: Long,
)

/**
 * Päivän stoppien hallinta: CRUD, manuaalinen järjestys ja reittisuunnitelman
 * välimuisti. Suunnitelma haetaan Routes API:sta vain kun järjestys tai
 * lähtöpaikka on oikeasti muuttunut — muuten kaikki lukijat (Suunnittelu,
 * Kartta, ajosessio) jakavat saman välimuistiin tallennetun tuloksen.
 */
@Singleton
class RouteRepository @Inject constructor(
    private val db: AppDatabase,
    private val routeDayDao: RouteDayDao,
    private val stopDao: StopDao,
    private val matrixCacheDao: MatrixCacheDao,
    private val savedAddressDao: SavedAddressDao,
    private val legStatDao: LegStatDao,
    private val routeSource: RouteOverviewSource,
    private val stopsLock: StopsWriteLock,
) : DriveStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** Suojaa luku-muokkaa-kirjoita-operaatiot samanaikaisilta kutsujilta; yhteinen [RouteTemplateRepository]n kanssa. */
    private val writeMutex get() = stopsLock.mutex

    /** Yksi suunnitelmahaku kerrallaan — rinnakkaiset kutsujat saavat välimuistiosuman. */
    private val planMutex = Mutex()

    /** Tämän päivän RouteDay; luodaan jos puuttuu. */
    suspend fun todayDay(): RouteDayEntity = writeMutex.withLock {
        val epochDay = LocalDate.now().toEpochDay()
        routeDayDao.byDate(epochDay)?.let { return it }
        val id = routeDayDao.insert(RouteDayEntity(dateEpochDay = epochDay))
        // -1 = kilpaileva polku (esim. HTML-tuonti) ehti luoda saman päivän.
        return if (id != -1L) {
            RouteDayEntity(id = id, dateEpochDay = epochDay)
        } else {
            requireNotNull(routeDayDao.byDate(epochDay))
        }
    }

    override suspend fun day(dayId: Long): RouteDayEntity? = routeDayDao.byId(dayId)

    override suspend fun markDayStarted(dayId: Long, nowEpochSec: Long) = routeDayDao.setStartedIfNull(dayId, nowEpochSec)

    override suspend fun markDayFinished(dayId: Long, nowEpochSec: Long) = routeDayDao.setFinished(dayId, nowEpochSec)

    override fun observeStops(dayId: Long): Flow<List<StopEntity>> = stopDao.observeForDay(dayId)

    /**
     * Kuluva päivä; emittoi uuden RouteDayn heti kun vuorokausi vaihtuu, myös
     * jos sovellus on ollut auki yön yli — muuten aamun kirjaukset ja stopit
     * osuisivat eiliselle päivälle kunnes sovellus käynnistetään uudelleen.
     */
    fun observeTodayDay(): Flow<RouteDayEntity> = flow {
        while (currentCoroutineContext().isActive) {
            emit(todayDay())
            // Zonetetut ajat: LocalDateTime-erotus laskisi kesäaikasiirtymän
            // yön yli tunnin väärin.
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
        }
    }.distinctUntilChangedBy { it.id }

    /**
     * Lisää stopin päivän listalle. Jos sama osoite on jo hoitamattomissa
     * stopeissa, tuplaa ei luoda vaan palautetaan [AddStopResult.Duplicate] —
     * hoidettuun (DONE/SKIPPED) osoitteeseen saa uuden stopin, koska sinne
     * voi joutua ajamaan uudelleen myöhemmin päivällä.
     */
    suspend fun addStop(dayId: Long, place: PlaceDetails): AddStopResult = writeMutex.withLock {
        val existing = stopDao.forDay(dayId)
            .filter { it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
            .firstOrNull { sameAddress(it, place) }
        if (existing != null) {
            // Käyttö kirjataan silti, jotta osoitemuisti pysyy tuoreena.
            recordAddressUse(place)
            return@withLock AddStopResult.Duplicate(existing)
        }
        // Seuraava vapaa järjestysnumero — pelkkä count antaisi duplikaatin,
        // jos välistä on poistettu stoppeja. Mutex estää tuplanumerot myös
        // nopeissa peräkkäisissä lisäyksissä.
        val orderIndex = stopDao.forDay(dayId).maxOfOrNull { it.orderIndex }?.plus(1) ?: 0
        val entity = StopEntity(
            routeDayId = dayId,
            label = place.name.ifBlank { place.address },
            address = place.address,
            placeId = place.placeId.ifBlank { null },
            lat = place.location.lat,
            lng = place.location.lng,
            orderIndex = orderIndex,
        )
        val id = stopDao.insert(entity)
        recordAddressUse(place)
        return@withLock AddStopResult.Added(entity.copy(id = id))
    }

    /**
     * Karttakohteen stoppi (huoltoasema, lataus, kaasu, lepopaikka) listan loppuun tai seuraavaksi pysähdykseksi
     * ([serviceStopInsertion]); siirrot, tilan palautus ja lisäys yhdessä transaktiossa. Sama kohde hoitamattomissa
     * stopeissa → [AddStopResult.Duplicate]. Huoltoasema ei ole osoitemuistin osoite.
     */
    suspend fun addServiceStop(dayId: Long, place: PlaceDetails, serviceSec: Long, placement: ServiceStopPlacement): AddStopResult =
        writeMutex.withLock {
            db.withTransaction {
                val stops = stopDao.forDay(dayId)
                stops.filter { it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
                    .firstOrNull { sameAddress(it, place) }
                    ?.let { return@withTransaction AddStopResult.Duplicate(it) }
                val insertion = serviceStopInsertion(stops, placement)
                val byId = stops.associateBy { it.id }
                insertion.shiftUp.forEach { id -> stopDao.setOrder(id, byId.getValue(id).orderIndex + 1) }
                insertion.demoteActive?.let { stopDao.setStatus(it, StopStatus.PENDING) }
                val entity = StopEntity(
                    routeDayId = dayId,
                    label = place.name.ifBlank { place.address },
                    address = place.address,
                    placeId = place.placeId.ifBlank { null },
                    lat = place.location.lat,
                    lng = place.location.lng,
                    orderIndex = insertion.orderIndex,
                    serviceSec = serviceSec,
                )
                val id = stopDao.insert(entity)
                AddStopResult.Added(entity.copy(id = id))
            }
        }

    /** Sama osoite myös silloin, kun eri hakupalvelu antoi sille eri tunnisteen ([isSameAddress]). */
    private fun sameAddress(stop: StopEntity, place: PlaceDetails): Boolean =
        isSameAddress(stop.placeId, stop.address, GeoPoint(stop.lat, stop.lng), place.placeId, place.address, place.location)

    /** Ennakoivan haun paikalliset osumat; tyhjällä haulla viimeksi käytetyt. */
    suspend fun searchSavedAddresses(query: String): List<SavedAddressEntity> =
        if (query.isBlank()) savedAddressDao.recent()
        else savedAddressDao.search(query.trim().lowercase())

    private suspend fun recordAddressUse(place: PlaceDetails) {
        val now = System.currentTimeMillis() / 1000
        val existing = place.placeId.takeIf { it.isNotBlank() }?.let { savedAddressDao.byPlaceId(it) }
            ?: savedAddressDao.byAddress(place.address)
        if (existing != null) {
            savedAddressDao.update(
                existing.copy(useCount = existing.useCount + 1, lastUsedEpochSec = now)
            )
        } else {
            savedAddressDao.insert(
                SavedAddressEntity(
                    placeId = place.placeId.ifBlank { null },
                    label = place.name.ifBlank { place.address },
                    address = place.address,
                    lat = place.location.lat,
                    lng = place.location.lng,
                    searchKey = "${place.name} ${place.address}".lowercase(),
                    lastUsedEpochSec = now,
                )
            )
        }
    }

    override suspend fun removeStop(stopId: Long) {
        stopDao.delete(stopId)
    }

    /** Vaihda stopin nimi. Ei vaikuta reittiin, joten välimuisti säilyy. */
    suspend fun renameStop(stopId: Long, label: String) = stopDao.setLabel(stopId, label)

    /**
     * Vaihda stopin osoite (ja koordinaatit) hakutulokseen. Päivä luetaan
     * stopin omalta riviltä — keskiyön yli venynyt valinta ei saa osua uuden
     * päivän tauluihin. Palauttaa null jos stoppi ehti kadota valinnan ja
     * verkkovastauksen välissä (vanhentunut muokkaus ohitetaan hiljaa).
     * Jos sama osoite on jo toisella hoitamattomalla stopilla, muutosta ei
     * tehdä (Duplicate).
     */
    suspend fun changeStopAddress(
        stopId: Long,
        place: PlaceDetails,
        label: String,
    ): AddStopResult? = writeMutex.withLock {
        val existing = stopDao.byId(stopId) ?: return@withLock null
        val dayId = existing.routeDayId
        val duplicate = stopDao.forDay(dayId)
            .filter { it.id != stopId && it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
            .firstOrNull { sameAddress(it, place) }
        if (duplicate != null) {
            return@withLock AddStopResult.Duplicate(duplicate)
        }
        stopDao.updatePlace(
            id = stopId,
            label = label,
            address = place.address,
            placeId = place.placeId.ifBlank { null },
            lat = place.location.lat,
            lng = place.location.lng,
        )
        // Suunnitelmavälimuisti tyhjennetään vain kun reitti oikeasti muuttui:
        // stopIds-lista ei muutu osoitteenvaihdossa, joten pelkkä id-vertailu
        // tarjoaisi vanhan reitin vanhoilla koordinaateilla. Hoidetun stopin
        // muokkaus tai saman paikan uudelleenvalinta ei kuitenkaan saa maksaa
        // uutta Routes-hakua. planMutexin sisällä, ettei kesken oleva vanha
        // haku kirjoita tyhjennyksen jälkeen vanhoja koordinaatteja takaisin.
        val routeAffected = existing.status != StopStatus.DONE &&
            existing.status != StopStatus.SKIPPED &&
            (existing.lat != place.location.lat || existing.lng != place.location.lng)
        if (routeAffected) {
            planMutex.withLock { matrixCacheDao.clear(dayId) }
        }
        recordAddressUse(place)
        return@withLock stopDao.byId(stopId)?.let { AddStopResult.Added(it) }
    }

    /**
     * Poista päivän kaikki stopit kerralla. Tyhjä päivä ei ole enää avattu mistään pohjasta; viite nollataan samassa
     * transaktiossa, jottei muutosrivi välähdä poistettujen stoppien määrällä.
     */
    suspend fun clearStops(dayId: Long): Unit = writeMutex.withLock {
        db.withTransaction {
            routeDayDao.setTemplate(dayId, null)
            stopDao.deleteForDay(dayId)
            matrixCacheDao.clear(dayId)
        }
    }

    /**
     * Kirjoittaa kuljettajan raahaaman järjestyksen kantaan. [orderedIds] on
     * hoitamattomien (PENDING/ACTIVE) stoppien uusi järjestys; hoidetut,
     * ohitetut ja ARRIVED-stoppi pysyvät paikoillaan. Jos näkymä oli
     * vanhentunut (stoppi ehti poistua tai valmistua), siirto hylätään —
     * tarkkailijat piirtävät tuoreen tilanteen heti perään.
     */
    suspend fun reorderStops(dayId: Long, orderedIds: List<Long>): Unit = writeMutex.withLock {
        // Tuore luku, vanhentumistarkistus ja kirjoitukset samassa
        // transaktiossa: stoppi voi poistua tai valmistua tarkistuksen ja
        // kirjoituksen välissä, jos ne tehtäisiin erikseen.
        db.withTransaction {
            val movable = stopDao.forDay(dayId)
                .filter { it.status == StopStatus.PENDING || it.status == StopStatus.ACTIVE }
            if (movable.map { it.id }.toSet() != orderedIds.toSet()) return@withTransaction
            // Siirretyt jaetaan liikuteltavien alkuperäisiin orderIndex-paikkoihin,
            // jolloin hoidettujen rivien indeksit eivät muutu.
            val slots = movable.map { it.orderIndex }
            val byId = movable.associateBy { it.id }
            orderedIds.forEachIndexed { index, id ->
                val stop = byId.getValue(id)
                if (stop.orderIndex != slots[index]) stopDao.setOrder(id, slots[index])
            }
        }
    }

    suspend fun setDeadline(stopId: Long, minutesOfDay: Int?) = stopDao.setDeadline(stopId, minutesOfDay)

    suspend fun setServiceSec(stopId: Long, serviceSec: Long?) = stopDao.setServiceSec(stopId, serviceSec)

    override suspend fun setStatus(stopId: Long, status: StopStatus) = stopDao.setStatus(stopId, status)

    override suspend fun stop(stopId: Long): StopEntity? = stopDao.byId(stopId)

    /** Kirjaa saapumisaika ja siirrä stoppi ARRIVED-tilaan. */
    override suspend fun markStopArrived(stopId: Long, arrivedAtEpochSec: Long) {
        stopDao.markArrived(stopId, StopStatus.ARRIVED, arrivedAtEpochSec)
    }

    /** Merkitse stoppi hoidetuksi lähtöaikoineen. */
    override suspend fun markStopDone(stopId: Long, departedAtEpochSec: Long) {
        stopDao.markDone(stopId, StopStatus.DONE, departedAtEpochSec)
    }

    /** Jäljellä olevat (hoitamattomat) stopit ajojärjestyksessä. */
    override suspend fun remainingStops(dayId: Long): List<StopEntity> =
        stopDao.forDay(dayId).filter { it.status == StopStatus.PENDING || it.status == StopStatus.ACTIVE }

    /** Stoppi, jolla kuljettaja oli prosessin kuollessa (ARRIVED), tai null. */
    override suspend fun arrivedStop(dayId: Long): StopEntity? =
        stopDao.forDay(dayId).firstOrNull { it.status == StopStatus.ARRIVED }

    override suspend fun doneCount(dayId: Long): Int =
        stopDao.forDay(dayId).count { it.status == StopStatus.DONE || it.status == StopStatus.SKIPPED }

    override suspend fun totalCount(dayId: Long): Int = stopDao.countForDay(dayId)

    /** StopEntity → core-mallin PlannedStop. Aikaraja muunnetaan päivän epoch-ajaksi. */
    override fun toPlanned(entity: StopEntity, dateEpochDay: Long): PlannedStop {
        val deadlineEpochSec = entity.deadlineMinutesOfDay?.let { minutes ->
            LocalDate.ofEpochDay(dateEpochDay)
                .atStartOfDay(ZoneId.systemDefault())
                .toEpochSecond() + minutes * 60L
        }
        return PlannedStop(
            id = entity.id,
            label = entity.label,
            location = GeoPoint(entity.lat, entity.lng),
            deadlineEpochSec = deadlineEpochSec,
            serviceSec = entity.serviceSec,
        )
    }

    /**
     * Reittisuunnitelma annetulle järjestykselle. Palauttaa välimuistista, jos
     * stoppijärjestys ja lähtöpaikka (~100 m tarkkuudella) eivät ole muuttuneet
     * eikä suunnitelma ole vanhentunut; muuten hakee reitityspalvelimelta yhdellä
     * kutsulla ja tallentaa.
     */
    override suspend fun routePlan(
        dayId: Long,
        origin: GeoPoint?,
        stops: List<StopEntity>,
        dateEpochDay: Long,
        maxAgeSec: Long,
    ): RoutePlan = planMutex.withLock {
        require(stops.isNotEmpty()) { "Reittisuunnitelma vaatii vähintään yhden stopin" }
        val now = System.currentTimeMillis() / 1000
        val ids = stops.map { it.id }
        cachedPlan(dayId)?.let { cached ->
            if (cached.stopIds == ids &&
                originKey(cached.origin) == originKey(origin) &&
                now - cached.fetchedAtEpochSec <= maxAgeSec
            ) {
                return@withLock cached
            }
        }

        val planned = stops.map { toPlanned(it, dateEpochDay) }
        val plan = when {
            origin != null -> {
                val overview = routeSource.computeRouteOverview(origin, planned)
                RoutePlan(
                    origin = origin,
                    stopIds = ids,
                    legDurationsSec = overview.legDurationsSec,
                    legDistancesM = overview.legDistancesM,
                    encodedPolyline = overview.encodedPolyline,
                    totalDurationSec = overview.totalDurationSec,
                    fetchedAtEpochSec = now,
                )
            }
            // Ei sijaintia (lupa puuttuu): reitti 1. stopilta, jolle nollalegi.
            planned.size >= 2 -> {
                val overview = routeSource.computeRouteOverview(planned.first().location, planned.drop(1))
                RoutePlan(
                    origin = null,
                    stopIds = ids,
                    legDurationsSec = listOf(0L) + overview.legDurationsSec,
                    legDistancesM = listOf(-1L) + overview.legDistancesM,
                    encodedPolyline = overview.encodedPolyline,
                    totalDurationSec = overview.totalDurationSec,
                    fetchedAtEpochSec = now,
                )
            }
            // Yksi stoppi ilman sijaintia: nollasuunnitelma, ettei vanha
            // polyline ja legilista jää näkyviin.
            else -> RoutePlan(
                origin = null,
                stopIds = ids,
                legDurationsSec = listOf(0L),
                legDistancesM = listOf(-1L),
                encodedPolyline = "",
                totalDurationSec = 0,
                fetchedAtEpochSec = now,
            )
        }
        matrixCacheDao.put(MatrixCacheEntity(dayId, json.encodeToString(plan.toDto()), now))
        plan
    }

    /** Välimuistissa oleva suunnitelma tai null — ei koskaan verkkohakua. */
    override suspend fun cachedPlan(dayId: Long): RoutePlan? =
        matrixCacheDao.get(dayId)?.let { entity ->
            runCatching {
                json.decodeFromString<PlanDto>(entity.json).toPlan(entity.fetchedAtEpochSec)
            }.getOrNull()
        }

    /**
     * Korvaa legien kestot opituilla väliajoilla (EWMA) niille peräkkäisille
     * stoppipareille, joista on toteumia. Legiä 0 (lähtöpaikasta ensimmäiselle
     * stopille) ei korvata, koska lähtöpaikka ei ole stoppi.
     */
    override suspend fun learnedLegDurations(
        stops: List<StopEntity>,
        base: List<Long>,
        speedFactor: Double,
    ): List<Long> {
        if (stops.size != base.size) return base
        val stats = legStatDao.all()
        if (stats.isEmpty()) return base
        val byPair = stats.associateBy { it.fromKey to it.toKey }
        // ewmaSec on jo toteuma, mutta EtaEngine kertoo kaikki legit
        // nopeuskertoimella — normalisointi tässä estää tuplakorjauksen.
        val factor = if (speedFactor > 0) speedFactor else 1.0
        return base.mapIndexed { i, duration ->
            if (i == 0) {
                duration
            } else {
                val fromKey = learnKey(stops[i - 1].lat, stops[i - 1].lng)
                val toKey = learnKey(stops[i].lat, stops[i].lng)
                byPair[fromKey to toKey]?.let { (it.ewmaSec / factor).toLong() } ?: duration
            }
        }
    }

    /**
     * Kirjaa toteutuneen ajoajan stoppiparille. Kun kuljettaja ajaa oman
     * reittinsä, EWMA oppii sen heti ensimmäisestä kerrasta ja syrjäyttää
     * reittipalvelun ennusteen seuraavissa ETA-laskuissa.
     */
    override suspend fun recordLegStat(from: StopEntity, to: StopEntity, actualSec: Long, nowEpochSec: Long) {
        if (actualSec < MIN_LEARNABLE_LEG_SEC) return
        val fromKey = learnKey(from.lat, from.lng)
        val toKey = learnKey(to.lat, to.lng)
        val existing = legStatDao.get(fromKey, toKey)
        val ewma = if (existing == null || existing.samples == 0) {
            actualSec
        } else {
            (LEG_EWMA_ALPHA * actualSec + (1 - LEG_EWMA_ALPHA) * existing.ewmaSec).toLong()
        }
        legStatDao.upsert(
            LegStatEntity(
                fromKey = fromKey,
                toKey = toKey,
                ewmaSec = ewma,
                samples = (existing?.samples ?: 0) + 1,
                updatedEpochSec = nowEpochSec,
            )
        )
    }

    /**
     * Stoppiparin oppimisavain: pyöristetyt koordinaatit (~10 m, [legKey]). Paikkatunniste vaihtelee hakupalvelun
     * mukaan (Digitransit, Photon, vanhoissa stopeissa Geoapify), piste ei; vanhat `p:`-avaimet muunnettiin migraatiossa 7 → 8
     * samalla funktiolla.
     */
    private fun learnKey(lat: Double, lng: Double): String = legKey(lat, lng)

    /** Lähtöpaikan vertailuavain ~100 m tarkkuudella; pieni liike ei mitätöi suunnitelmaa. */
    private fun originKey(origin: GeoPoint?): String? =
        origin?.let { String.format(java.util.Locale.ROOT, "%.3f,%.3f", it.lat, it.lng) }

    private fun RoutePlan.toDto() = PlanDto(
        originLat = origin?.lat,
        originLng = origin?.lng,
        stopIds = stopIds,
        legDurationsSec = legDurationsSec,
        legDistancesM = legDistancesM,
        encodedPolyline = encodedPolyline,
        totalDurationSec = totalDurationSec,
    )

    private fun PlanDto.toPlan(fetchedAtEpochSec: Long) = RoutePlan(
        origin = if (originLat != null && originLng != null) GeoPoint(originLat, originLng) else null,
        stopIds = stopIds,
        legDurationsSec = legDurationsSec,
        legDistancesM = legDistancesM,
        encodedPolyline = encodedPolyline,
        totalDurationSec = totalDurationSec,
        fetchedAtEpochSec = fetchedAtEpochSec,
    )

    companion object {
        /** Alle tämän mittaiset toteumat ovat todennäköisesti virhepainalluksia. */
        private const val MIN_LEARNABLE_LEG_SEC = 30L

        /** Uusin toteuma painaa puolet — oppii nopeasti, tasoittaa poikkeamat. */
        private const val LEG_EWMA_ALPHA = 0.5

        /** Suunnitelman enimmäisikä — liikennetilanne vanhenee, vaikkei järjestys
         *  muutu. Julkinen, jotta ajosession ticker tunnistaa vanhentuneen
         *  suunnitelman ja pyytää tuoreen. */
        const val PLAN_MAX_AGE_SEC = DriveStore.PLAN_MAX_AGE_SEC
    }
}
