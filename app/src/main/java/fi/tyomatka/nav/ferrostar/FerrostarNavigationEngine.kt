package fi.tyomatka.nav.ferrostar

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.stadiamaps.ferrostar.core.AlternativeRouteProcessor
import com.stadiamaps.ferrostar.core.CorrectiveAction
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import com.stadiamaps.ferrostar.core.RouteDeviationHandler
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.tyomatka.BuildConfig
import fi.tyomatka.core.api.RateLimiter
import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.ArrivalDetector
import fi.tyomatka.core.nav.ArrivalSide
import fi.tyomatka.core.nav.ArrivalFix
import fi.tyomatka.core.nav.DeviationPolicy
import fi.tyomatka.core.nav.ProgressThrottle
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.core.nav.SpeechGate
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.core.nav.SpeechPlan
import fi.tyomatka.core.nav.ValhallaRequest
import fi.tyomatka.core.nav.VoicePolicy
import fi.tyomatka.core.nav.VolumeHint
import fi.tyomatka.core.nav.navLanguageTag
import fi.tyomatka.data.repo.RouteChoiceStore
import fi.tyomatka.data.repo.ServerUrls
import fi.tyomatka.diag.Diag
import fi.tyomatka.nav.LegProgress
import fi.tyomatka.nav.NavTarget
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.nav.PreparedRoute
import fi.tyomatka.nav.RoutingException
import fi.tyomatka.ui.appLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.TripState
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.createOsrmResponseParser
import uniffi.ferrostar.createValhallaRequestGenerator
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

/** Hiljainen media-ääni ajon alussa: taso, maksimi ja sessio, jotta ilmoitus näytetään kerran sessiota kohti. */
data class LowVolume(val level: Int, val max: Int, val session: Int)

/**
 * Navigointimoottori Ferrostarilla ja FOSSGIS-Valhallalla. Ajosessio ([startSession]…[endSession]) omistaa yhden
 * Ferrostar-ytimen ja sijaintivirran; jokainen [startGuidance] on oma opastuskertansa ([generation]), jonka
 * ulkopuoliset tapahtumat (myöhässä valmistunut uusi reitti, vanhan reitin tila) ohitetaan.
 *
 * Saapuminen päätellään omasta [ArrivalDetector]ista raa'an sijainnin ja reittietäisyyden perusteella, ja Ferrostarin
 * oma "matka valmis" kelpaa vain, jos saman opastuskerran Navigating-tila on nähty. Eteneminen harvennetaan
 * [ProgressThrottle]lla. Kaikki tilamuutokset tehdään Main-säikeessä; ytimen omat sijainti- ja verkkotyöt eivät näy
 * kutsujalle. Sama olio tarjoaa ajokartalle Ferrostarin [NavigationViewModel]-näkymän.
 */
@Singleton
class FerrostarNavigationEngine @Inject constructor(
    @ApplicationContext context: Context,
    okHttp: OkHttpClient,
    private val limiter: RateLimiter,
    private val routeChoice: RouteChoiceStore,
    private val servers: ServerUrls,
) : NavigationEngine, NavigationViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = valhallaHttpClient(okHttp, BuildConfig.VERSION_NAME)
    private val positions = NavigationLocation(context)
    private val voice = GuidanceVoice(context, GuidanceVoice.vibration(context))
    private val rerouteTone = RerouteTone()
    private val volume = GuidanceVolume(context)

    /** Sessiolaskuri: hiljaisen äänen ilmoitus näytetään kerran sessiota kohti. */
    private var session = 0
    private var volumeChecked = false
    private val annotations = valhallaExtendedOSRMAnnotationPublisher()
    private val arrivalDetector = ArrivalDetector()
    private val throttle = ProgressThrottle()

    private var core: FerrostarCore? = null
    private var valhalla: ValhallaRouteClient? = null
    private var sessionJob: Job? = null

    /** Opastuskerta: kasvaa jokaisesta [startGuidance]sta. Luetaan myös ytimen säikeestä. */
    @Volatile
    private var generation = 0
    private var navigatingSeen = false
    private var arrivalSent = false

    /** Puheohjeiden kieli (session asetus) ja nykyisen kohteen puoli reitin lopussa. */
    private var languageTag = "en-US"

    @Volatile
    private var arrivalSide = ArrivalSide.UNKNOWN

    /** Viimeksi lokitettu puheohje (debug-ajoitus). */
    private var loggedUtterance: java.util.UUID? = null

    /** Nykyisen opastuskerran viimeisin reittietäisyys saapumisen tunnistukseen (harventamaton). */
    private var remainingRouteM: Double? = null

    /** Hetki, jolloin reitti viimeksi vaihdettiin uudelleenreitityksessä; puhe on hetken hiljaa sen jälkeen. */
    @Volatile
    private var routeReplacedMs: Long? = null

    /** Opastuskerran alku: aivan alussa ei reititetä uudelleen, koska ytimen aloituspiste on edellisen legin viimeinen. */
    @Volatile
    private var guidanceStartedMs = 0L

    /** Opastuskerta, jolle viimeisin uudelleenreititys haettiin; vaihto hyväksytään vain, jos se on yhä käynnissä. */
    @Volatile
    private var rerouteGeneration = -1

    /** Ytimen aloitus, pysäytys ja reitin vaihto eivät saa limittyä: ydin ei ole säieturvallinen. */
    private val coreLock = Any()

    /** Diagnostiikkaloki: sijaintinäyte enintään kerran 10 sekunnissa. */
    private val fixDiag = fi.tyomatka.diag.DiagThrottle()
    private val _rerouteFailures = MutableSharedFlow<RouteFailure>(extraBufferCapacity = 4)
    override val rerouteFailures: SharedFlow<RouteFailure> = _rerouteFailures.asSharedFlow()

    /** Kielitunniste, jolle puhelimesta puuttuu puheääni (ajonäkymän ilmoitus). */
    val missingVoice: StateFlow<String?>
        get() = voice.missingVoice

    /** Media-ääni on ajon alussa hiljainen (ajonäkymän ilmoitus); null, kun taso riittää tai se on jo nostettu. */
    val lowVolume: StateFlow<LowVolume?>
        get() = _lowVolume
    private val _lowVolume = MutableStateFlow<LowVolume?>(null)

    /** Kuljettaja pyysi nostamaan media-äänen täysille; taso palautetaan session lopussa. */
    fun raiseVolume() {
        volume.raise()
        _lowVolume.value = null
    }

    /** Debug: sijainti simuloidaan reittiä pitkin pöytätestissä (session asetus). */
    private var simulateDrive = false

    /** Session reittivalinta: lyhimmän reitin toteumasta ei opita ennusteisiin. */
    private var learnableRoutes = true

    /** Opastus käynnissä: ääni, uudelleenreititys ja saapuminen elävät vain tällöin. */
    val guidanceActive: StateFlow<Boolean>
        get() = _guidanceActive
    private val _guidanceActive = MutableStateFlow(false)

    /** Kohde, jolle opastus viimeksi aloitettiin; nasta pysyy kartalla saapumisen jälkeenkin session loppuun. */
    val destination: StateFlow<NavTarget?>
        get() = _destination
    private val _destination = MutableStateFlow<NavTarget?>(null)

    private val _progress = MutableStateFlow<LegProgress?>(null)
    override val progress: StateFlow<LegProgress?> = _progress.asStateFlow()

    private val _arrivals = MutableSharedFlow<NavTarget>(extraBufferCapacity = 4)
    override val arrivals: SharedFlow<NavTarget> = _arrivals.asSharedFlow()

    private val _location = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()

    private val _uiState = MutableStateFlow(NavigationUiState.empty())
    override val navigationUiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    override suspend fun startSession(simulate: Boolean) {
        if (core != null) return
        simulateDrive = simulate
        session++
        volumeChecked = false
        val languageTag = navLanguageTag(appLocale().language)
        this.languageTag = languageTag
        val choice = routeChoice.choice.first()
        learnableRoutes = choice == RouteChoice.NORMAL
        Diag.event("ajo", "reittivalinta ${choice.key}")
        val generator = createValhallaRequestGenerator(
            "${servers.valhalla}/route",
            VALHALLA_PROFILE,
            ValhallaRequest.optionsJson(languageTag, choice),
        )
        val parser = createOsrmResponseParser(POLYLINE_PRECISION)
        val client = ValhallaRouteClient(
            http = http,
            limiter = limiter,
            requestBody = { fix, waypoints -> generator.generateRequest(fix, waypoints) as RouteRequest.HttpPost },
            parse = { parser.parseResponse(it) },
            latestFix = { positions.fixes.value?.toUserLocation() },
        )
        valhalla = client
        val ferrostar = FerrostarCore(
            customRouteProvider = CustomRouteProvider { fix, waypoints ->
                // Uudelleenreititys: jos opastuskerta ehti vaihtua haun aikana, tulos ei koske ketään. Ferrostar vain
                // lokittaa haun virheen, joten se välitetään ajosessiolle itse (virheteksti ja Perillä linnuntiellä).
                val requestedFor = generation
                val routes = try {
                    client.getRoutes(fix, waypoints)
                } catch (e: RoutingException) {
                    // Tarkistus pääsäikeen työn sisällä: IO-säikeessä hyväksytty virhe voisi muuten osua vasta
                    // aloitettuun uuteen opastukseen.
                    scope.launch { if (requestedFor == generation && _guidanceActive.value) onRerouteFailed(e.failure) }
                    throw e
                }
                rerouteGeneration = requestedFor
                if (requestedFor == generation) routes else emptyList()
            },
            httpClient = OkHttpClientProvider(http),
            locationProvider = positions,
            navigationControllerConfig = controllerConfig(),
        )
        ferrostar.minimumTimeBeforeRecalculation = 10.seconds
        ferrostar.minimumMovementBeforeRecalculation = 75.0
        ferrostar.deviationHandler = RouteDeviationHandler { _, kind, remainingWaypoints ->
            val reroute = DeviationPolicy.shouldReroute(
                guidanceActive = _guidanceActive.value,
                offStepOnly = kind is DeviationKind.OffStepOnRoute,
                fixAgeMs = latestFixAgeMs(),
                guidanceAgeMs = SystemClock.elapsedRealtime() - guidanceStartedMs,
            )
            // Kesken oleva lause puhutaan loppuun (puhe pätki); haun aikana uudet ohjeet hylkää
            // puhesääntö, ja uuden reitin tullessa kuuluu merkkiääni.
            if (reroute) CorrectiveAction.GetNewRoutes(remainingWaypoints) else CorrectiveAction.DoNothing
        }
        // Sama kutsu kuin Ferrostarin oletus, mutta vain käynnissä olevaan opastukseen ja vain sille opastuskerralle,
        // jolle reitti haettiin. Tarkistus ja vaihto ovat samassa lukossa aloituksen ja pysäytyksen kanssa, koska ydin
        // ei ole säieturvallinen eikä vanhentunut tulos saa osua uuteen kohteeseen.
        ferrostar.alternativeRouteProcessor = AlternativeRouteProcessor { active, routes ->
            synchronized(coreLock) {
                if (_guidanceActive.value && rerouteGeneration == generation) {
                    routes.firstOrNull()?.let {
                        // Vaihdettu reitti saa samat puhekorjaukset; lähtöohje on Jatka-muodossa, koska auto on liikkeellä.
                        val spoken = RouteSpeech.apply(it, languageTag, rerouted = true)
                        arrivalSide = RouteSpeech.arrivalSide(spoken)
                        routeReplacedMs = SystemClock.elapsedRealtime()
                        rerouteTone.play()
                        // Ytimen replaceRoute tyhjentää puhejonon: kesken oleva lause saa silti jatkua loppuun.
                        voice.keepSpeechOnNextStop()
                        fi.tyomatka.diag.Diag.event("ajo", "moottori: reitti vaihdettu")
                        active.replaceRoute(spoken)
                    }
                }
            }
        }
        // Puhe kuuluu vain käynnissä olevassa opastuksessa, ei poikkeaman tai uudelleenreitityksen aikana; heti reitin
        // vaihdon jälkeen ohje lykätään. Poikkeama luetaan ytimen tilasta, joka päivittyy samalla sijaintinäytteellä.
        voice.speechGate = {
            val trip = ferrostar.state.value.tripState
            if (!_guidanceActive.value) {
                SpeechGate.Drop
            } else {
                VoicePolicy.gate(
                    nowMs = SystemClock.elapsedRealtime(),
                    offRoute = trip is TripState.Navigating && trip.deviation is RouteDeviation.Deviation,
                    rerouting = ferrostar.isCalculatingNewRoute,
                    routeReplacedMs = routeReplacedMs,
                )
            }
        }
        ferrostar.spokenInstructionObserver = voice
        voice.start(languageTag)
        core = ferrostar
        positions.start()
        sessionJob = scope.launch {
            launch { positions.fixes.filterNotNull().collect { onFix(it) } }
            launch { ferrostar.state.collect { onCoreState(it) } }
        }
    }

    override suspend fun prepareRoute(target: NavTarget): PreparedRoute {
        val client = valhalla ?: throw RoutingException(RouteFailure.Unavailable, "Navigointisessio ei ole auki")
        val fix = positions.freshFix() ?: throw RoutingException(RouteFailure.NoLocation)
        val waypoint = Waypoint(GeographicCoordinate(target.location.lat, target.location.lng), WaypointKind.BREAK, null)
        val routes = withContext(Dispatchers.IO) { client.getRoutes(fix.toUserLocation(), listOf(waypoint)) }
        val route = routes.firstOrNull() ?: throw RoutingException(RouteFailure.NoRoute)
        return PreparedRoute(
            target = target,
            predictedSec = route.steps.sumOf { it.duration }.roundToLong(),
            distanceM = route.distance,
            payload = route,
            learnable = learnableRoutes,
        )
    }

    override fun startGuidance(route: PreparedRoute) {
        val ferrostar = core ?: return
        val payload = RouteSpeech.apply(route.payload as Route, languageTag, rerouted = false)
        arrivalSide = RouteSpeech.arrivalSide(payload)
        generation++
        navigatingSeen = false
        arrivalSent = false
        remainingRouteM = null
        routeReplacedMs = null
        _destination.value = route.target
        _progress.value = null
        guidanceStartedMs = SystemClock.elapsedRealtime()
        arrivalDetector.startLeg(guidanceStartedMs)
        throttle.reset()
        if (!volumeChecked) {
            volumeChecked = true
            val level = volume.level()
            val max = volume.max()
            if (VolumeHint.shouldWarn(level, max)) _lowVolume.value = LowVolume(level, max, session)
        }
        // Julkaisuversiossa ehto on käännösaikaisesti epätosi, joten simulaatio ei päädy sovellukseen.
        if (BuildConfig.DEBUG && simulateDrive) positions.simulate(payload)
        synchronized(coreLock) {
            _guidanceActive.value = true
            ferrostar.startNavigation(payload)
        }
    }

    override fun suspendGuidance() {
        synchronized(coreLock) {
            _guidanceActive.value = false
            core?.stopNavigation()
        }
        remainingRouteM = null
        _progress.value = null
    }

    /** Uudelleenreititys epäonnistui: vanhan reitin etäisyys ei kuvaa mitään, ja ajosessio näyttää virheen. */
    private fun onRerouteFailed(failure: RouteFailure) {
        if (!_guidanceActive.value) return
        remainingRouteM = null
        _progress.value = null
        fi.tyomatka.diag.Diag.event("ajo", "moottori: reitin haku epäonnistui $failure")
        _rerouteFailures.tryEmit(failure)
    }

    override fun endSession() {
        _guidanceActive.value = false
        sessionJob?.cancel()
        sessionJob = null
        core?.stopNavigation()
        core = null
        valhalla = null
        voice.shutdown()
        rerouteTone.release()
        volume.restore()
        _lowVolume.value = null
        positions.stop()
        _destination.value = null
        _progress.value = null
        _location.value = null
        remainingRouteM = null
        _uiState.value = NavigationUiState.empty()
    }

    /** Raaka sijainti: linnuntie-etäisyys ja saapumisen tunnistus myös silloin, kun reittiä ei ole. */
    private fun onFix(fix: Location) {
        val here = GeoPoint(fix.latitude, fix.longitude)
        _location.value = here
        if (fi.tyomatka.diag.Diag.enabled && fixDiag.allow()) {
            fi.tyomatka.diag.Diag.event(
                "ajo",
                String.format(
                    java.util.Locale.ROOT, "sijainti %.3f,%.3f tarkkuus %s m nopeus %s km/h ikä %d ms opastus=%s",
                    fix.latitude, fix.longitude,
                    if (fix.hasAccuracy()) "%.0f".format(java.util.Locale.ROOT, fix.accuracy) else "?",
                    if (fix.hasSpeed()) "%.0f".format(java.util.Locale.ROOT, fix.speed * 3.6) else "?",
                    (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000,
                    _guidanceActive.value,
                ),
            )
        }
        // Pysäytetyssä opastuksessa kartta saa pelkän sijainnin, jotta piste jää kartalle stopilla.
        if (!_guidanceActive.value) _uiState.value = _uiState.value.copy(location = fix.toUserLocation())
        val target = _destination.value ?: return
        if (!_guidanceActive.value || arrivalSent) return
        val arrival = ArrivalFix(
            nowMs = SystemClock.elapsedRealtime(),
            fixTimeMs = fix.elapsedRealtimeNanos / 1_000_000,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = if (fix.hasAccuracy()) fix.accuracy.toDouble() else Double.MAX_VALUE,
            speedMps = if (fix.hasSpeed()) fix.speed.toDouble() else null,
            remainingRouteM = remainingRouteM,
            straightLineM = distanceKm(here, target.location) * 1000,
        )
        // Pysähdys ilman reittietäisyyttä (poikkeama, epäonnistunut haku) voi tulla toisesta suunnasta: ei puolta.
        if (arrivalDetector.onFix(arrival)) sendArrival(target, sideKnown = arrival.remainingRouteM != null)
    }

    private fun onCoreState(state: NavigationState) {
        if (!_guidanceActive.value) {
            // Ydin ei ole säieturvallinen: kesken ollut päivitys voi palauttaa Navigating-tilan pysäytyksen jälkeen.
            // Pysäytettynä kartta saa vain sijainnin, jolloin otsikko ei jää näkyviin stopilla.
            _uiState.value = NavigationUiState.empty().copy(location = positions.fixes.value?.toUserLocation())
            return
        }
        val mapped = NavigationUiState.fromFerrostar(state, isMuted = null, annotation = annotations.map(state))
        _uiState.value = if (mapped.location == null) {
            mapped.copy(location = positions.fixes.value?.toUserLocation())
        } else {
            mapped
        }
        val target = _destination.value ?: return
        when (val trip = state.tripState) {
            is TripState.Navigating -> {
                navigatingSeen = true
                val tripProgress = trip.progress
                logSpokenTrigger(trip)
                // Reitiltä poikenneena reittiprojektion etäisyys voi näyttää nollaa kaukana kohteesta: ei saapumiseen.
                remainingRouteM = if (trip.deviation is RouteDeviation.Deviation) null else tripProgress.distanceRemaining
                if (throttle.shouldEmit(SystemClock.elapsedRealtime(), tripProgress.distanceRemaining)) {
                    _progress.value = LegProgress(
                        target = target,
                        generation = generation,
                        remainingSec = tripProgress.durationRemaining.roundToLong(),
                        remainingM = tripProgress.distanceRemaining.roundToInt(),
                        offRoute = trip.deviation is RouteDeviation.Deviation,
                    )
                }
            }
            // Lyhyellä legillä "matka valmis" tulee heti aloituksen jälkeen: legin vähimmäiskesto pätee siihenkin.
            is TripState.Complete ->
                if (navigatingSeen && arrivalDetector.onTripComplete(SystemClock.elapsedRealtime())) sendArrival(target, sideKnown = true)
            is TripState.Idle -> Unit
        }
    }

    /** Debug: ohjeen laukaisuhetki, laukaisumatka, matka käännökseen ja nopeus ("400 m oli 200 m"). */
    private fun logSpokenTrigger(trip: TripState.Navigating) {
        if (!BuildConfig.DEBUG) return
        val spoken = trip.spokenInstruction ?: return
        if (spoken.utteranceId == loggedUtterance) return
        loggedUtterance = spoken.utteranceId
        val speed = trip.userLocation.speed?.value
        Log.i(
            TIMING_TAG,
            "laukaisu t=${SystemClock.elapsedRealtime()} laukaisumatka=${spoken.triggerDistanceBeforeManeuver.roundToInt()} " +
                "matka=${trip.progress.distanceToNextManeuver.roundToInt()} nopeus=${speed?.let { (it * 3.6).roundToInt() }} teksti=${spoken.text}",
        )
    }

    private fun sendArrival(target: NavTarget, sideKnown: Boolean) {
        if (arrivalSent) return
        arrivalSent = true
        // Oma Perillä-lause puolen kanssa ennen kuin ajosessio pysäyttää opastuksen.
        voice.announce(SpeechPlan.arrivalText(languageTag, arrivalSide, sideKnown))
        _arrivals.tryEmit(target)
    }

    private fun latestFixAgeMs(): Long = positions.fixes.value?.let {
        (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000
    } ?: Long.MAX_VALUE

    // Ferrostarin karttanäkymän rajapinta: kohde ja mykistys hoidetaan omilla tiloilla.
    override fun setDestination(destination: String?) = Unit

    override fun toggleMute() = Unit

    override fun stopNavigation() = suspendGuidance()

    private companion object {
        const val TIMING_TAG = "GuidanceTiming"
        const val VALHALLA_PROFILE = "auto"
        val POLYLINE_PRECISION = 6u

        fun controllerConfig() = NavigationControllerConfig(
            WaypointAdvanceMode.WaypointWithinRange(100.0),
            stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
            stepAdvanceDistanceToEndOfStep(20u, 32u),
            RouteDeviationTracking.StaticThreshold(15u, 50.0),
            CourseFiltering.SNAP_TO_ROUTE,
        )
    }
}
