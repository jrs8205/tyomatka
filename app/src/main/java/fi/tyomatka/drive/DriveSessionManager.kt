package fi.tyomatka.drive

import fi.tyomatka.core.eta.EtaEngine
import fi.tyomatka.diag.Diag
import fi.tyomatka.core.eta.EtaInput
import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RetryBackoff
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.core.nav.ValhallaRequest
import fi.tyomatka.core.model.PlannedStop
import fi.tyomatka.core.model.ScheduleSnapshot
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.nav.NavTarget
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.nav.RoutingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Ajosession vaihe. */
enum class DrivePhase { IDLE, NAVIGATING, AT_STOP, FINISHED }

/** Ajonäkymän tila. */
data class DriveState(
    val phase: DrivePhase = DrivePhase.IDLE,
    val dayId: Long? = null,
    /** Stoppi, jota kohti ajetaan tai jolla ollaan. */
    val currentStop: PlannedStop? = null,
    /** Jäljellä olevat stopit ajojärjestyksessä (sis. currentStop). */
    val remaining: List<PlannedStop> = emptyList(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    /** Reaaliaikainen aikatauluennuste kaikille jäljellä oleville stopeille. */
    val schedule: ScheduleSnapshot? = null,
    /** Navigointimoottorin live-ETA nykyiselle legille sekunteina. */
    val liveEtaSec: Long? = null,
    /** Navigointimoottorin jäljellä oleva matka nykyiselle stopille metreinä. */
    val liveDistanceM: Int? = null,
    /**
     * Linnuntie nykyiselle stopille, kun sen reittihaku on epäonnistunut: Perillä-lukko aukeaa silloin tästä,
     * jottei ajo jää palvelinkatkossa ohitusten varaan.
     */
    val fallbackDistanceM: Int? = null,
    val error: String? = null,
)

/**
 * Ajaa koko päivän tilakoneen [NavigationEngine]-portin yli: käynnistys →
 * navigointi stopille → saapuminen → lähtö → seuraava stoppi → ... → päivä
 * valmis.
 *
 * Järjestys ohjaa kaikkea: Room-kannan stoppilista on ainoa totuus, ja
 * session aikana [watchStops]-tarkkailija reagoi sen jokaiseen muutokseen
 * (siirto, poisto, kuittaus, lisäys) välittömästi. Kohde vaihdetaan (uusi
 * reittihaku) vain jos seuraava stoppi oikeasti vaihtui.
 *
 * Kaikki tilakoneen käsittely ajetaan Main.immediate-säikeessä; keskeytysten
 * (DAO/DataStore-kutsut) yli jatkavat kulut vartioidaan [phaseEpoch]illa,
 * ettei rinnakkain edennyt saapuminen/lähtö jää alle.
 */
@Singleton
class DriveSessionManager @Inject constructor(
    private val host: DriveHost,
    private val store: DriveStore,
    private val learning: LearningStore,
    private val engine: NavigationEngine,
    private val etaEngine: EtaEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    /** Moottorin etenemis- ja saapumisvirtojen kuuntelu — elää vain session ajan. */
    private var engineJob: Job? = null

    /** Kesken oleva reittihaku nykyiselle kohteelle. */
    private var routeJob: Job? = null

    /** Stoppilistan tarkkailija — elää vain session ajan. */
    private var stopsJob: Job? = null

    /** Reittisuunnitelman (legien kestot) päivitys muutoksen jälkeen. */
    private var planJob: Job? = null

    /** Epäonnistuneen reittihaun uusi yritys samaan kohteeseen. */
    private var retryJob: Job? = null

    /** Kohde, jonka reittihaku epäonnistui; uusi yritys ja linnuntie koskevat vain sitä. */
    private var failedDestinationStopId: Long? = null
    private var failedDestinationLocation: GeoPoint? = null
    private var retryAttempt = 0
    private val backoff = RetryBackoff()

    /** Estää tuplakäynnistyksen ennen kuin sessio ehtii vaihtaa vaiheen. */
    private var startingDay = false

    /**
     * Kasvaa jokaisesta reittihausta ja session päättyessä — myöhässä valmistuva
     * haku tunnistetaan siitä vanhentuneeksi, ettei se herätä opastusta
     * lopetettuun sessioon tai ylikirjoita uudempaa reititystä.
     */
    private var destinationRequestSeq = 0

    /** Stoppi, jolle opastus on käynnissä (reitti haettu ja aloitettu). */
    private var activeDestinationStopId: Long? = null

    /** Koordinaatit, joille [activeDestinationStopId] on reititetty — osoitteen
     *  muokkaus säilyttää stopin id:n, joten pelkkä id-vertailu jättäisi
     *  navigoinnin vanhaan osoitteeseen. */
    private var activeDestinationLocation: GeoPoint? = null

    /**
     * Stoppi, jolle reittihaku on lähetetty mutta tulos ei ole vielä saapunut.
     * Oma ACTIVE-kirjaus laukaisee uuden Room-emission ennen tulosta — ilman
     * tätä sama haku lähtisi kahdesti.
     */
    private var requestedDestinationStopId: Long? = null

    /** Koordinaatit, joille kesken oleva kohdepyyntö lähetettiin. */
    private var requestedDestinationLocation: GeoPoint? = null

    /** Kasvaa jokaisesta vaihesiirtymästä; keskeytyksen yli jatkava käsittely
     *  hylätään jos vaihe ehti muuttua alla (phase-before-write-invariantti). */
    private var phaseEpoch = 0

    /** Aikataululaskennan versio — vain uusin laskenta saa julkaista tuloksen. */
    private var scheduleSeq = 0

    /** Legin seuranta oppimista varten. */
    private var legStartEpochSec: Long = 0
    private var legPredictedSec: Long = 0

    /** Lyhimmän reitin toteumasta ei opita: se ei kerro tavallisen reitin ajasta. */
    private var legLearnable = true

    /** Päivän suunnitelman ennuste samalle legille (suunnitelman kertoimen oppimiseen), jos tiedetään. */
    private var legPlanPredictedSec: Long? = null

    /**
     * Stopilta on juuri lähdetty eikä uutta kohdetta ole vielä asetettu: seuraava kohde jatkaa stoppiparin
     * mittausta lähdetystä stopista. Aktiivinen kohde nollataan jo lähdössä, joten tieto pidetään erikseen.
     */
    private var justDeparted = false

    /** Stoppi, jolta nykyinen legi lähti — stoppiparin väliajan oppimiseen. */
    private var lastDepartedStop: PlannedStop? = null

    private var dateEpochDay: Long = 0

    val isDriving: Boolean
        get() = _state.value.phase == DrivePhase.NAVIGATING || _state.value.phase == DrivePhase.AT_STOP

    /**
     * Käynnistää päivän ajon. Kutsutaan vasta kun sijaintilupa on myönnetty
     * (MainActivity hoitaa).
     */
    fun startDay(dayId: Long, simulate: Boolean = false) {
        if (isDriving || startingDay) return
        Diag.event("ajo", "aloitus päivä $dayId simulate=$simulate")
        startingDay = true
        scope.launch {
            try {
                beginGuidance(dayId, simulate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.event("ajo", "aloitus epäonnistui ${e.javaClass.simpleName}: ${e.message}")
                // Sessio on voinut ehtiä auki ennen virhettä — ei saa jäädä roikkumaan.
                runCatching { engine.endSession() }
                _state.value = _state.value.copy(
                    error = host.message(DriveMessage.NAV_START_FAILED, e.message ?: e.javaClass.simpleName),
                )
            } finally {
                startingDay = false
            }
        }
    }

    private suspend fun beginGuidance(dayId: Long, simulate: Boolean) {
        val day = store.day(dayId) ?: return
        dateEpochDay = day.dateEpochDay
        lastDepartedStop = null
        // Vain kenttä: päivän muut kirjoittajat (lisätiedot, pohjan viite) eivät saa jäädä vanhan rivin alle.
        store.markDayStarted(dayId, nowSec())

        // Prosessi on voinut kuolla kuljettajan ollessa stopilla: ARRIVED-rivi
        // ei kuulu remainingStops-joukkoon, mutta se on yhä kesken.
        val arrived = store.arrivedStop(dayId)
        if (arrived == null && store.remainingStops(dayId).isEmpty()) {
            Diag.event("ajo", "ei ajettavaa: FINISHED")
            _state.value = DriveState(phase = DrivePhase.FINISHED, dayId = dayId)
            return
        }

        // Moottorin resurssit (ääni, sijainti) avataan vasta kun on jotain ajettavaa.
        engine.startSession(simulate)
        attachEngineObservers()

        // Vaihe ennen tarkkailijan käynnistystä: ensimmäinen emissio ohjaa
        // listan kärkeen (tai jatkaa keskeytynyttä pysähdystä) ja jokainen
        // myöhempi muutos perässä.
        phaseEpoch++
        Diag.event("ajo", if (arrived != null) "jatketaan stopilla ${arrived.label}: AT_STOP" else "NAVIGATING, stoppeja jäljellä ${store.remainingStops(dayId).size}")
        _state.value = if (arrived != null) {
            // Palataan AT_STOPiin ilman uutta reittihakua —
            // "Valmis, jatka seuraavaan" jatkaa normaalisti.
            _state.value.copy(
                phase = DrivePhase.AT_STOP,
                dayId = dayId,
                currentStop = toPlannedNow(arrived),
                error = null,
            )
        } else {
            _state.value.copy(phase = DrivePhase.NAVIGATING, dayId = dayId, error = null)
        }
        watchStops(dayId)
        startTicker()
        host.startNavigationService()
    }

    /**
     * Saapumiset ja legin eteneminen moottorilta. Vieraan kohteen tapahtumat
     * (edellinen kohde, kesken olevan vaihdon vanha opastus) ohitetaan.
     */
    private fun attachEngineObservers() {
        engineJob?.cancel()
        engineJob = scope.launch {
            launch {
                engine.arrivals.collect { target ->
                    if (target.stopId != activeDestinationStopId) return@collect
                    // Kirjaus omassa työssään: session lopetus peruu kuuntelijan,
                    // mutta jo hyväksytyn saapumisen kantakirjoitus saa valmistua.
                    scope.launch { onArrived() }
                }
            }
            launch {
                // Diagnostiikkaloki: eteneminen harvennettuna (enintään kerran 10 sekunnissa); sijainnin tarkkuus ja
                // nopeus kirjataan moottorissa.
                val throttle = fi.tyomatka.diag.DiagThrottle()
                engine.progress.collect { progress ->
                    if (progress != null && Diag.enabled && throttle.allow()) {
                        Diag.event("ajo", "eteneminen kohde ${progress.target.label} jäljellä ${progress.remainingM} m eta ${progress.remainingSec} s reitiltä=${progress.offRoute}")
                    }
                }
            }
            launch {
                // Palvelinkatkossa Perillä aukeaa linnuntiellä: etäisyys seuraa sijaintia vain epäonnistuneelle kohteelle.
                engine.location.collect { location ->
                    val stopId = failedDestinationStopId ?: return@collect
                    val target = failedDestinationLocation ?: return@collect
                    if (_state.value.currentStop?.id != stopId) return@collect
                    _state.value = _state.value.copy(fallbackDistanceM = location?.let { metersBetween(it, target) })
                }
            }
            launch {
                engine.progress.collect { progress ->
                    // Kohteenvaihdon aikana vanha opastus syöttäisi edellisen legin
                    // lukemat juuri nollattuihin kenttiin — ohitetaan kunnes uuden
                    // kohteen reitti on aloitettu.
                    if (progress == null || requestedDestinationStopId != null) return@collect
                    if (progress.target.stopId != activeDestinationStopId) return@collect
                    // Uudelleenreititys onnistui virheen jälkeen: virhe ja linnuntie pois, reittietäisyys takaisin.
                    // Vanhan reitin lukemat poikkeamassa eivät ole toipuminen: virhe ja linnuntie
                    // säilyvät, kunnes eteneminen tulee reitiltä (uusi reitti tai paluu vanhalle).
                    val recovered = failedDestinationStopId == activeDestinationStopId
                    if (recovered && progress.offRoute) return@collect
                    if (recovered) clearRetry()
                    _state.value = _state.value.copy(
                        liveEtaSec = progress.remainingSec,
                        liveDistanceM = progress.remainingM,
                        error = if (recovered) null else _state.value.error,
                        fallbackDistanceM = if (recovered) null else _state.value.fallbackDistanceM,
                    )
                    recomputeSchedule()
                }
            }
            launch {
                engine.rerouteFailures.collect { failure -> onRerouteFailed(failure) }
            }
        }
    }

    /**
     * Poikkeaman jälkeinen uuden reitin haku epäonnistui: moottori jatkaa vanhalla reitillä ja yrittää itse uudelleen,
     * mutta kuljettaja näkee virheen, ja Perillä aukeaa linnuntiellä, koska vanhan reitin etäisyys ei kuvaa mitään.
     */
    private fun onRerouteFailed(failure: RouteFailure) {
        Diag.event("ajo", "uudelleenreititys epäonnistui: $failure")
        val st = _state.value
        if (st.phase != DrivePhase.NAVIGATING) return
        val current = st.currentStop ?: return
        if (current.id != activeDestinationStopId || current.location != activeDestinationLocation) return
        failedDestinationStopId = current.id
        failedDestinationLocation = current.location
        _state.value = st.copy(
            error = host.routingFailureText(failure),
            liveEtaSec = null,
            liveDistanceM = null,
            fallbackDistanceM = engine.location.value?.let { metersBetween(it, current.location) },
        )
        recomputeSchedule()
    }

    private fun watchStops(dayId: Long) {
        stopsJob?.cancel()
        stopsJob = scope.launch {
            store.observeStops(dayId)
                // Oma no-op-kirjaus laukaisee saman kyselyn uudelleen —
                // identtinen lista ei saa pyörittää käsittelyä silmukkana.
                .distinctUntilChanged()
                .collect { stops -> onStopsChanged(dayId, stops) }
        }
    }

    private suspend fun onStopsChanged(dayId: Long, stops: List<StopEntity>) {
        val epoch = phaseEpoch
        val upcoming = stops.filter { it.status == StopStatus.PENDING || it.status == StopStatus.ACTIVE }
        val arrived = stops.firstOrNull { it.status == StopStatus.ARRIVED }
        when (_state.value.phase) {
            DrivePhase.NAVIGATING -> {
                if (upcoming.isEmpty()) {
                    // finishDay erillisenä jobina: tarkkailijan peruminen sen
                    // sisältä katkaisisi finishDayn omat kirjoitukset.
                    scope.launch { finishDay(dayId) }
                } else {
                    setDestinationsAndGo(upcoming, dayId)
                    refreshPlan(dayId, upcoming)
                }
            }
            DrivePhase.AT_STOP -> {
                if (arrived == null) {
                    // Stoppi jolla oltiin poistettiin kesken pysähdyksen —
                    // jatka suoraan seuraavaan tai päätä päivä.
                    lastDepartedStop = null
                    if (upcoming.isEmpty()) {
                        scope.launch { finishDay(dayId) }
                    } else {
                        phaseEpoch++
                        _state.value = _state.value.copy(phase = DrivePhase.NAVIGATING)
                        setDestinationsAndGo(upcoming, dayId)
                        refreshPlan(dayId, upcoming)
                    }
                } else {
                    // Stopilla ollessa järjestys/poistot päivittävät vain
                    // tulevien listan; määränpää asetetaan vasta lähdettäessä.
                    val current = toPlannedNow(arrived)
                    val done = doneCount(dayId)
                    val total = totalCount(dayId)
                    // Laskurihaut keskeyttivät: lähtö on voinut jo siirtää
                    // vaiheen eteenpäin, eikä sen tilaa saa ylikirjoittaa.
                    if (phaseEpoch != epoch) return
                    _state.value = _state.value.copy(
                        currentStop = current,
                        remaining = listOf(current) + upcoming.map(::toPlannedNow),
                        doneCount = done,
                        totalCount = total,
                    )
                    recomputeSchedule()
                    refreshPlan(dayId, listOf(arrived) + upcoming)
                }
            }
            else -> Unit
        }
    }

    private suspend fun setDestinationsAndGo(upcoming: List<StopEntity>, dayId: Long) {
        val epoch = phaseEpoch
        val currentEntity = upcoming.first()
        val current = toPlannedNow(currentEntity)
        val wasNavigating = _state.value.phase == DrivePhase.NAVIGATING
        // Sama kohde jo opastuksessa TAI haku jo matkalla: oma ACTIVE-kirjaus
        // laukaisee toisen emission ennen tulosta, eikä samaa hakua saa
        // lähettää kahdesti. Myös koordinaattien pitää täsmätä: osoitteen
        // muokkaus säilyttää id:n, ja ilman vertailua navigointi jäisi vanhaan
        // osoitteeseen — pahimmillaan muokkaus ehtii juuri lähetetyn haun ja
        // sen emission väliin.
        // Epäonnistunut kohde odottaa uutta yritystä: muun stopin muokkaus ei saa käynnistää hakua heti uudelleen
        // eikä nollata uusintaväliä. Osoitteen muokkaus muuttaa koordinaatit, jolloin haetaan heti. Ilman
        // automaattista uutta yritystä (liian pitkä reitti, asetusvirhe) stoppilistan muutos hakee uudestaan.
        val retryingSame = retryJob != null &&
            current.id == failedDestinationStopId && current.location == failedDestinationLocation
        val sameDestination = wasNavigating && (
            (current.id == activeDestinationStopId && current.location == activeDestinationLocation) ||
                (current.id == requestedDestinationStopId && current.location == requestedDestinationLocation) ||
                retryingSame
            )
        // Lähtö juuri hoidetulta stopilta seuraavaan on normaali eteneminen ja
        // stoppiparin mittaus saa jatkua. Mikä tahansa muu kohteen vaihtuminen
        // kesken legin katkaisee mittauksen: aika ei enää kuvaa suoraa väliä
        // edelliseltä stopilta.
        val advancingFromDeparted = justDeparted && lastDepartedStop != null
        if (wasNavigating && !sameDestination && !advancingFromDeparted) {
            lastDepartedStop = null
        }
        // Aito kohteen vaihto: vanhan legin ajanotto ei saa jäädä voimaan uuden
        // kohteen reittiin asti — ennenaikainen "Perillä stopilla" kirjaisi
        // muuten vanhan kohteen ajoajan uuden stopin toteumaksi.
        if (!sameDestination) {
            legStartEpochSec = 0
            legPredictedSec = 0
            legPlanPredictedSec = null
            justDeparted = false
        }

        if (currentEntity.status != StopStatus.ACTIVE) {
            store.setStatus(current.id, StopStatus.ACTIVE)
        }
        val done = doneCount(dayId)
        val total = totalCount(dayId)
        // DAO-kutsut keskeyttivät: saapuminen on voinut siirtää vaiheen
        // AT_STOPiin tällä välin, eikä sen yli saa kirjoittaa NAVIGATINGia.
        if (phaseEpoch != epoch || !isDriving) return

        _state.value = _state.value.copy(
            phase = DrivePhase.NAVIGATING,
            dayId = dayId,
            currentStop = current,
            remaining = upcoming.map(::toPlannedNow),
            totalCount = total,
            doneCount = done,
            // Kohteen vaihtuessa edellisen legin live-ETA ja -matka eivät saa
            // jäädä näkyviin uuden stopin lukemina.
            liveEtaSec = if (sameDestination) _state.value.liveEtaSec else null,
            liveDistanceM = if (sameDestination) _state.value.liveDistanceM else null,
            error = if (sameDestination) _state.value.error else null,
            fallbackDistanceM = if (sameDestination) _state.value.fallbackDistanceM else null,
        )

        if (sameDestination) {
            recomputeSchedule()
            return
        }

        // Aito kohteen vaihto: vanha opastus seis heti, ettei moottori opasta
        // edelliseen kohteeseen uuden haun aikana eikä sen saapuminen kuittaa
        // uutta stoppia — vanhan kohteen tapahtumat eivät enää täsmää aktiiviseen.
        engine.suspendGuidance()
        activeDestinationStopId = null
        activeDestinationLocation = null
        clearRetry()
        requestRoute(current, dayId)
    }

    /**
     * VAIN nykyinen stoppi kohteeksi: reitityspalvelin rajoittaa pisteiden määrän, ja jäljellä olevan päivän ETAt
     * lasketaan erikseen koko listan reittisuunnitelmasta.
     */
    private fun requestRoute(current: PlannedStop, dayId: Long) {
        Diag.event("ajo", "reitti pyydetään kohteeseen ${current.label}")
        val target = NavTarget(current.id, current.location, current.label)
        val requestSeq = ++destinationRequestSeq
        requestedDestinationStopId = current.id
        requestedDestinationLocation = current.location
        routeJob?.cancel()
        routeJob = scope.launch {
            val result = runCatching { engine.prepareRoute(target) }
            // Oma peruutus (kohde vaihtui, sessio päättyi) päättyy tähän. Moottorin oma peruutus, esimerkiksi sen
            // sisäinen aikakatkaisu, ei saa jumittaa legiä ilman virhettä ja uutta yritystä: se on palvelinkatko.
            if (result.exceptionOrNull() is CancellationException) ensureActive()
            // Vanhentunut haku: sessio päättyi tai kohde vaihtui jo uudelleen.
            if (requestSeq != destinationRequestSeq) return@launch
            val planLegSec = if (result.isSuccess) planLegFor(dayId, current.id) else null
            if (requestSeq != destinationRequestSeq) return@launch
            requestedDestinationStopId = null
            requestedDestinationLocation = null
            result.onSuccess { route ->
                Diag.event("ajo", "reitti saatu kohteeseen ${current.label}")
                // Uuden yrityksen reitti alkaa kesken legin: toteuma ei vastaa suunnitelman koko legiä eikä
                // stoppiparin väliä, joten niistä ei opita tältä legiltä.
                val retried = current.id == failedDestinationStopId && current.location == failedDestinationLocation
                clearRetry()
                // Kohde ja legin kirjanpito ennen opastuksen aloitusta: moottorin ensimmäinen eteneminen voi tulla
                // samassa kutsussa (Main.immediate), ja keräin hylkäisi sen vieraana, jos kohde ei vielä täsmäisi.
                activeDestinationStopId = current.id
                activeDestinationLocation = current.location
                legStartEpochSec = nowSec()
                legPredictedSec = route.predictedSec
                legLearnable = route.learnable
                legPlanPredictedSec = if (retried) null else planLegSec
                if (retried) lastDepartedStop = null
                engine.startGuidance(route)
                _state.value = _state.value.copy(error = null, fallbackDistanceM = null)
                recomputeSchedule()
            }.onFailure { e ->
                val failure = when (e) {
                    is RoutingException -> e.failure
                    is CancellationException -> RouteFailure.Unavailable
                    else -> ValhallaRequest.failureOf(e)
                }
                onRouteFailed(current, dayId, failure)
            }
        }
    }

    /**
     * Ilman opastusta ajoa voi jatkaa käsin; legillä ei ole ajanottoa. Palvelinkatkossa ja sijainnin puuttuessa sama
     * kohde haetaan automaattisesti uudelleen, reitin puuttuessa vasta kun on liikuttu. Liian pitkä reitti ja väärä
     * palvelin odottavat stoppilistan muutosta.
     */
    private fun onRouteFailed(current: PlannedStop, dayId: Long, failure: RouteFailure) {
        Diag.event("ajo", "reitin haku epäonnistui kohteeseen ${current.label}: $failure, yritys ${retryAttempt + 1}, teksti '${host.routingFailureText(failure)}'")
        failedDestinationStopId = current.id
        failedDestinationLocation = current.location
        _state.value = _state.value.copy(
            error = host.routingFailureText(failure),
            fallbackDistanceM = engine.location.value?.let { metersBetween(it, current.location) },
        )
        val attempt = retryAttempt++
        retryJob?.cancel()
        retryJob = when (failure) {
            RouteFailure.Unavailable -> scope.launch {
                delay(backoff.delaySec(attempt) * 1000)
                retryRoute(current, dayId)
            }
            is RouteFailure.RateLimited -> scope.launch {
                delay(max(failure.retryAfterSec, backoff.delaySec(attempt)) * 1000)
                retryRoute(current, dayId)
            }
            RouteFailure.NoLocation -> scope.launch {
                delay(NO_LOCATION_RETRY_MS)
                engine.location.filterNotNull().first()
                retryRoute(current, dayId)
            }
            RouteFailure.NoRoute -> scope.launch {
                val from = engine.location.filterNotNull().first()
                engine.location.filterNotNull().first { metersBetween(from, it) >= NO_ROUTE_RETRY_MOVE_M }
                retryRoute(current, dayId)
            }
            RouteFailure.TooLong, RouteFailure.BadConfig -> null
        }
    }

    private fun retryRoute(current: PlannedStop, dayId: Long) {
        if (_state.value.phase != DrivePhase.NAVIGATING) return
        // Stopin tuorein tila: nimi on voinut vaihtua katkon aikana; kohde on sama vain samoilla koordinaateilla.
        val latest = _state.value.currentStop ?: return
        if (latest.id != current.id || latest.id != failedDestinationStopId || latest.location != failedDestinationLocation) return
        requestRoute(latest, dayId)
    }

    private fun clearRetry() {
        retryJob?.cancel()
        retryJob = null
        failedDestinationStopId = null
        failedDestinationLocation = null
        retryAttempt = 0
    }

    /** Moottori ilmoitti saapumisesta stopille (tai kuljettaja merkitsi käsin). */
    fun markArrived() {
        Diag.event("ajo", "Perillä painettu")
        scope.launch { onArrived() }
    }

    private suspend fun onArrived() {
        val st = _state.value
        val current = st.currentStop ?: return
        val dayId = st.dayId ?: return
        if (st.phase != DrivePhase.NAVIGATING) return
        Diag.event("ajo", "saapuminen ${current.label}: AT_STOP")
        // Juuri lähdetty stoppi näkyy nykyisenä, kunnes seuraava kohde on asetettu: toinen napautus (Valmis →
        // Perillä samassa kohdassa) ei saa palauttaa sitä saapuneeksi.
        if (justDeparted && current.id == lastDepartedStop?.id) return
        val now = nowSec()

        // Vaihe AT_STOPiin ennen ensimmäistäkään keskeytystä: stoppitarkkailija
        // ehtisi muuten reagoida ARRIVED-kirjaukseen NAVIGATING-haarassa ja
        // ohjata seuraavalle stopille, vaikka kuljettaja on vasta perillä tällä.
        phaseEpoch++
        _state.value = st.copy(phase = DrivePhase.AT_STOP)
        // Jokainen saapuminen, myös käsin merkitty, päättää opastuksen kuten Googlen yhden kohteen reitti:
        // ääni ja uudelleenreititys eivät jatku stopilla. Myös kesken oleva reittihaku ja uusi yritys perutaan,
        // ettei myöhässä valmistuva reitti aloita opastusta stopilla.
        engine.suspendGuidance()
        destinationRequestSeq++
        routeJob?.cancel()
        requestedDestinationStopId = null
        requestedDestinationLocation = null
        clearRetry()

        store.markStopArrived(current.id, now)
        // Legin toteuma → nopeuskertoimen oppiminen.
        if (legPredictedSec > 0 && legLearnable) {
            learning.recordLeg(
                dayId = dayId,
                toStopId = current.id,
                navPredictedSec = legPredictedSec,
                planPredictedSec = legPlanPredictedSec,
                actualSec = now - legStartEpochSec,
                nowEpochSec = now,
            )
        }
        // Stoppiparin toteuma → oma reitti oppii heti ensimmäisestä ajosta.
        // legStartEpochSec == 0 = manuaalieteneminen ilman navigointia, jolloin
        // "kestoa" ei ole mitattu eikä siitä saa oppia.
        if (legStartEpochSec > 0 && legLearnable) {
            lastDepartedStop?.let { from ->
                val fromEntity = store.stop(from.id)
                val toEntity = store.stop(current.id)
                if (fromEntity != null && toEntity != null) {
                    store.recordLegStat(
                        from = fromEntity,
                        to = toEntity,
                        actualSec = now - legStartEpochSec,
                        nowEpochSec = now,
                    )
                }
            }
        }
        recomputeSchedule()
    }

    /** Kuljettaja valmis stopilla → kirjaa kesto ja jatka seuraavaan. */
    fun departCurrentStop() {
        Diag.event("ajo", "lähtö stopilta ${_state.value.currentStop?.label}")
        scope.launch {
            val st = _state.value
            // Vaihevaatimus + siirtymä synkronisesti ennen ensimmäistä
            // keskeytystä: tuplapainallus ei saa kirjata kahta palvelutietuetta.
            if (st.phase != DrivePhase.AT_STOP) return@launch
            val current = st.currentStop ?: return@launch
            val dayId = st.dayId ?: return@launch
            phaseEpoch++
            lastDepartedStop = current
            justDeparted = true
            // Lähdetty stoppi ei ole enää kohde: moottorilta myöhässä tuleva saapuminen siihen ei saa palauttaa
            // sitä saapuneeksi ennen kuin uusi kohde on asetettu.
            activeDestinationStopId = null
            activeDestinationLocation = null
            // Edellisen legin lukemat eivät saa jäädä näkyviin: Perillä pysyy lukossa, kunnes uusi reitti antaa
            // etäisyyden tai sen haku epäonnistuu.
            _state.value = st.copy(
                phase = DrivePhase.NAVIGATING,
                liveEtaSec = null,
                liveDistanceM = null,
                fallbackDistanceM = null,
                error = null,
            )

            val now = nowSec()
            val entity = store.stop(current.id)
            val arrivedAt = entity?.arrivedAtEpochSec ?: now
            store.markStopDone(current.id, now)
            // Huoltoasema tai lepopaikka ei kuvaa jakelupysähdystä: sen kesto ei opeta stoppikeston keskiarvoa.
            if (!ServiceStops.isServiceStop(entity?.placeId)) {
                learning.recordService(
                    dayId = dayId,
                    stopId = current.id,
                    durationSec = now - arrivedAt,
                    nowEpochSec = now,
                )
            }
        }
    }

    /** Ohita nykyinen stoppi ajamatta sitä loppuun. */
    fun skipCurrentStop() {
        Diag.event("ajo", "ohita ${_state.value.currentStop?.label}")
        scope.launch {
            val st = _state.value
            if (st.phase != DrivePhase.NAVIGATING) return@launch
            val current = st.currentStop ?: return@launch
            // Juuri lähdetty stoppi ei ole ohitettavissa, ennen kuin seuraava kohde on asetettu.
            if (justDeparted && current.id == lastDepartedStop?.id) return@launch
            // Ohitetun legin aika ei kuvaa suoraa väliä — ei opita siitä.
            lastDepartedStop = null
            detachActiveDestination()
            store.setStatus(current.id, StopStatus.SKIPPED)
        }
    }

    /**
     * Poista mikä tahansa jäljellä oleva stoppi kesken päivän. Tarkkailija
     * päivittää navigoinnin ja numeroinnin heti — kohde vaihtuu vain jos
     * poistettiin juuri se stoppi jota kohti ajetaan.
     */
    fun removeStop(stopId: Long) {
        Diag.event("ajo", "stoppi $stopId poistetaan ajosta")
        scope.launch {
            // Myös vasta haettavana tai uutta yritystä odottava kohde irrotetaan.
            if (stopId == activeDestinationStopId || stopId == requestedDestinationStopId || stopId == failedDestinationStopId) {
                detachActiveDestination()
            }
            store.removeStop(stopId)
        }
    }

    /**
     * Kohde ei ole enää ajettava (ohitus, poisto): opastus seis, kohde irti ja sen reittihaku ja uusi yritys perutaan
     * ennen kantakirjoitusta, ettei moottorilta samaan aikaan tuleva saapuminen kirjaa sitä saapuneeksi eikä
     * kirjoituksen aikana valmistuva haku aseta sitä takaisin kohteeksi.
     */
    private fun detachActiveDestination() {
        activeDestinationStopId = null
        activeDestinationLocation = null
        destinationRequestSeq++
        routeJob?.cancel()
        requestedDestinationStopId = null
        requestedDestinationLocation = null
        clearRetry()
        engine.suspendGuidance()
    }

    private suspend fun finishDay(dayId: Long) {
        Diag.event("ajo", "päivä $dayId päättyy: FINISHED")
        stopsJob?.cancel()
        stopsJob = null
        planJob?.cancel()
        phaseEpoch++
        // Vanhentaa kesken olevat reittihaut.
        destinationRequestSeq++
        requestedDestinationStopId = null
        requestedDestinationLocation = null
        activeDestinationStopId = null
        activeDestinationLocation = null
        legStartEpochSec = 0
        legPredictedSec = 0
        legPlanPredictedSec = null
        justDeparted = false
        clearRetry()
        detachEngine()
        engine.endSession()
        store.markDayFinished(dayId, nowSec())
        tickerJob?.cancel()
        _state.value = DriveState(
            phase = DrivePhase.FINISHED,
            dayId = dayId,
            doneCount = doneCount(dayId),
            totalCount = totalCount(dayId),
        )
        host.stopNavigationService()
    }

    /** Lopeta ajo kesken (päivä jää kesken, voi jatkaa myöhemmin). */
    fun stopDriving() {
        Diag.event("ajo", "lopeta ajo")
        scope.launch {
            val dayId = _state.value.dayId
            // Tarkkailija alas ennen tilakirjoituksia, ettei se herätä
            // lopetettua sessiota henkiin.
            stopsJob?.cancel()
            stopsJob = null
            planJob?.cancel()
            phaseEpoch++
            destinationRequestSeq++
            requestedDestinationStopId = null
            requestedDestinationLocation = null
            activeDestinationStopId = null
            activeDestinationLocation = null
            legStartEpochSec = 0
            legPredictedSec = 0
            legPlanPredictedSec = null
            justDeparted = false
            clearRetry()
            detachEngine()
            engine.endSession()
            tickerJob?.cancel()
            lastDepartedStop = null
            // "Ajossa oleva" stoppi takaisin jonoon — muuten se jää haamuksi,
            // jota numerointi ja aikataulu eivät huomioi.
            dayId?.let { id ->
                store.remainingStops(id)
                    .filter { it.status == StopStatus.ACTIVE }
                    .forEach { store.setStatus(it.id, StopStatus.PENDING) }
            }
            _state.value = DriveState()
            host.stopNavigationService()
        }
    }

    // ---- Aikatauluennusteen jatkuva päivitys ----

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                recomputeSchedule()
                maybeRefreshStalePlan()
                delay(30_000)
            }
        }
    }

    /**
     * Pitkällä legillä stoppilista ei muutu eikä mikään muu pyydä tuoretta
     * reittisuunnitelmaa — vanhentuneen (yli 30 min) haku laukaistaan tickeristä,
     * jotta myöhempien stoppien ETAt eivät jäädy rajatta.
     */
    private suspend fun maybeRefreshStalePlan() {
        val st = _state.value
        val dayId = st.dayId ?: return
        if (!isDriving || st.remaining.isEmpty()) return
        val ids = st.remaining.map { it.id }
        val plan = store.cachedPlan(dayId)
        val fresh = plan != null && plan.stopIds == ids &&
            nowSec() - plan.fetchedAtEpochSec <= DriveStore.PLAN_MAX_AGE_SEC
        if (fresh) return
        val entities = st.remaining.mapNotNull { store.stop(it.id) }
        if (entities.size == ids.size) refreshPlan(dayId, entities)
    }

    /**
     * Hae tuore reittisuunnitelma muuttuneelle järjestykselle (yksi
     * reittikutsu), jotta myöhempien stoppien ETAt vastaavat uutta järjestystä.
     * Viive koalisoi peräkkäiset muutokset; välimuistiosuma ei maksa mitään.
     */
    private fun refreshPlan(dayId: Long, ordered: List<StopEntity>) {
        planJob?.cancel()
        planJob = scope.launch {
            delay(500)
            runCatching {
                store.routePlan(dayId, host.lastKnownLocation(), ordered, dateEpochDay)
            }.onFailure { e ->
                // Verkkokatko ei pysäytä ajoa: ETAt lasketaan oletuslegeillä.
                if (e is CancellationException) throw e
            }
            recomputeSchedule()
        }
    }

    private fun recomputeSchedule() {
        val st = _state.value
        val dayId = st.dayId ?: return
        if (st.remaining.isEmpty()) return
        val seq = ++scheduleSeq
        scope.launch {
            val stats = learning.currentStats()
            val ids = st.remaining.map { it.id }
            val plan = store.cachedPlan(dayId)
            val base = if (plan != null && plan.stopIds == ids) {
                plan.legDurationsSec
            } else {
                st.remaining.map { DEFAULT_LEG_SEC }
            }
            val entities = st.remaining.mapNotNull { store.stop(it.id) }
            val legs = if (entities.size == st.remaining.size) {
                store.learnedLegDurations(entities, base, stats.speedFactor)
            } else {
                base
            }
            val current = st.currentStop
            val atStopSince = if (st.phase == DrivePhase.AT_STOP && current != null) {
                store.stop(current.id)?.arrivedAtEpochSec
            } else {
                null
            }
            val snapshot = etaEngine.compute(
                EtaInput(
                    nowEpochSec = nowSec(),
                    liveEtaToNextSec = if (st.phase == DrivePhase.NAVIGATING) st.liveEtaSec else null,
                    remainingStops = st.remaining,
                    legPredictedSec = legs,
                    stats = stats,
                    atStopSinceEpochSec = atStopSince,
                )
            )
            // Vain uusin laskenta julkaisee — hitaampi vanha laskenta toisi
            // muuten poistetun stopin tai vanhan järjestyksen takaisin näkyviin.
            if (seq != scheduleSeq) return@launch
            _state.value = _state.value.copy(schedule = snapshot)
        }
    }

    // ---- Apurit ----

    private fun detachEngine() {
        routeJob?.cancel()
        routeJob = null
        engineJob?.cancel()
        engineJob = null
    }

    /**
     * Päivän suunnitelman ennuste nykyiselle legille suunnitelman kertoimen oppimiseen. Kelpaa joko legi juuri
     * lähdetystä stopista tai lähtöpisteestä ensimmäiseen stoppiin, kun suunnitelma on laskettu lähellä nykyistä
     * sijaintia (aamulla kotona laskettu suunnitelma ei kuvaa varikolta alkavaa legiä).
     */
    private suspend fun planLegFor(dayId: Long, stopId: Long): Long? {
        val plan = store.cachedPlan(dayId) ?: return null
        val i = plan.stopIds.indexOf(stopId)
        if (i < 0 || i >= plan.legDurationsSec.size) return null
        val fromDeparted = i >= 1 && plan.stopIds[i - 1] == lastDepartedStop?.id
        val origin = plan.origin
        val here = engine.location.value
        val fromOrigin = i == 0 && origin != null && here != null &&
            distanceKm(origin, here) * 1000 <= PLAN_ORIGIN_MATCH_M
        return plan.legDurationsSec[i].takeIf { fromDeparted || fromOrigin }
    }

    private fun metersBetween(a: GeoPoint, b: GeoPoint): Int = (distanceKm(a, b) * 1000).roundToInt()

    private fun toPlannedNow(entity: StopEntity): PlannedStop =
        store.toPlanned(entity, dateEpochDay)

    private suspend fun doneCount(dayId: Long): Int = store.doneCount(dayId)

    private suspend fun totalCount(dayId: Long): Int = store.totalCount(dayId)

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val DEFAULT_LEG_SEC = 10 * 60L

        /** Suunnitelman lähtöpisteen ja legin alun suurin ero, jolla ensimmäisen legin ennuste kelpaa oppimiseen. */
        const val PLAN_ORIGIN_MATCH_M = 500.0

        /** Sijainnin puuttuessa uusi yritys aikaisintaan tämän jälkeen, kun sijainti on saatu. */
        const val NO_LOCATION_RETRY_MS = 10_000L

        /** Reitin puuttuessa uusi yritys vasta, kun on liikuttu näin paljon (toinen tie voi löytää reitin). */
        const val NO_ROUTE_RETRY_MOVE_M = 200
    }
}
