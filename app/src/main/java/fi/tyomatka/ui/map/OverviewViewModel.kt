package fi.tyomatka.ui.map

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.core.eta.EtaEngine
import fi.tyomatka.diag.Diag
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.core.eta.EtaInput
import fi.tyomatka.core.geo.Polyline
import fi.tyomatka.core.api.RoutePreview
import fi.tyomatka.core.api.ValhallaPreviewClient
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.data.repo.RouteChoiceStore
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.HistoryRepository
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.location.currentLocation
import fi.tyomatka.ui.UserErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Yhden stopin rivi järjestelypaneelissa. */
data class OverviewStopRow(
    val stop: StopEntity,
    /** Matka edellisestä pisteestä (lähtöpaikka tai edellinen stoppi), null jos ei tiedossa. */
    val distanceFromPrevM: Long? = null,
    val arrivalEpochSec: Long? = null,
)

data class OverviewUiState(
    /** Jäljellä olevat stopit ajojärjestyksessä — hoidetut eivät näy kartalla,
     *  joten seuraava stoppi on aina numero 1. */
    val stops: List<StopEntity> = emptyList(),
    /** Päivällä on jo hoidettuja/ohitettuja stoppeja — tyhjä kartta tarkoittaa
     *  silloin valmista päivää, ei puuttuvaa suunnitelmaa. */
    val hasHandledStops: Boolean = false,
    val rows: List<OverviewStopRow> = emptyList(),
    /** Kuljettajan sijainti = reitin lähtöpiste, null jos ei saatavilla. */
    val origin: GeoPoint? = null,
    /** Reitin polyline purettuina pisteinä, tyhjä jos ei vielä haettu. */
    val routePoints: List<GeoPoint> = emptyList(),
    val totalDistanceM: Long = 0,
    val totalDurationSec: Long = 0,
    val finishEpochSec: Long? = null,
    val loadingRoute: Boolean = false,
    val error: String? = null,
    /** Nopeimman ja lyhimmän reitin vertailu. */
    val comparison: RouteComparison = RouteComparison(),
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val application: Application,
    private val routeRepository: RouteRepository,
    private val historyRepository: HistoryRepository,
    private val etaEngine: EtaEngine,
    private val previewClient: ValhallaPreviewClient,
    private val routeChoiceStore: RouteChoiceStore,
    private val sessionManager: DriveSessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(OverviewUiState())
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    private var dayId: Long? = null
    private var dateEpochDay: Long = 0
    private var fetchJob: Job? = null

    private val previewLoader = RoutePreviewLoader(previewClient::preview)
    private var mapVisible = false
    private var driving = false

    init {
        viewModelScope.launch {
            sessionManager.state.map { it.phase == DrivePhase.NAVIGATING || it.phase == DrivePhase.AT_STOP }
                .distinctUntilChanged()
                .collect { isDriving ->
                    val ended = driving && !isDriving
                    driving = isDriving
                    // Ajossa valinta ei enää muuta opastusta, joten sitä ei näytetä.
                    if (isDriving) previewLoader.clear(::publishPreviews)
                    if (ended && mapVisible) refreshOrigin()
                }
        }
        viewModelScope.launch {
            routeChoiceStore.choice.collect { choice ->
                _state.value = _state.value.copy(comparison = _state.value.comparison.copy(choice = choice))
            }
        }
        viewModelScope.launch {
            // Vuorokauden vaihtuessa siirrytään uuteen päivään lennossa.
            routeRepository.observeTodayDay().collectLatest { day ->
                dayId = day.id
                dateEpochDay = day.dateEpochDay
                routeRepository.observeStops(day.id).collect { stops ->
                    val visible = stops.filter {
                        it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED
                    }
                    _state.value = _state.value.copy(
                        stops = visible,
                        hasHandledStops = stops.size > visible.size,
                    )
                    if (visible.isNotEmpty()) {
                        fetchRoute(day.id, visible)
                    } else {
                        fetchJob?.cancel()
                        previewLoader.clear(::publishPreviews)
                        // Sijainti ennen tilan lukua: välissä tullut reittivalinta ei saa jäädä vanhan tilan alle.
                        val origin = _state.value.origin ?: currentLocation(application)
                        _state.value = _state.value.copy(
                            loadingRoute = false,
                            rows = emptyList(),
                            routePoints = emptyList(),
                            totalDistanceM = 0,
                            totalDurationSec = 0,
                            finishEpochSec = null,
                            // Kartta näkyy myös ilman stoppeja: kamera viedään
                            // kuljettajan sijaintiin, jotta näkee heti missä on.
                            origin = origin,
                        )
                    }
                }
            }
        }
    }

    /**
     * Tuore sijainti karttaa avattaessa. Ilman stoppeja kamera keskitetään
     * siihen; reitillisellä kartalla ajetaan reittihaku uudelleen tuoreella
     * lähtöpaikalla — muuten reitti ja lähtömerkki jäisivät vanhaan sijaintiin
     * (esim. sijaintilupa myönnetty vasta kartan avaamisen jälkeen).
     * Välimuisti (järjestys + ~100 m origin-lohko + 30 min) takaa, ettei
     * paikallaan pysyvä kuljettaja maksa uusia Routes-hakuja.
     */
    fun refreshOrigin() {
        viewModelScope.launch {
            val current = _state.value
            val id = dayId
            if (current.stops.isNotEmpty() && id != null) {
                fetchRoute(id, current.stops)
                return@launch
            }
            val fresh = currentLocation(application) ?: return@launch
            if (_state.value.stops.isEmpty()) {
                _state.value = _state.value.copy(origin = fresh)
            }
        }
    }

    /** Raahauksen tulos: hoitamattomien stoppien uusi järjestys. */
    fun reorder(orderedIds: List<Long>) {
        val id = dayId ?: return
        Diag.event("kartta", "järjestys ${orderedIds.joinToString(",")}")
        viewModelScope.launch { routeRepository.reorderStops(id, orderedIds) }
    }

    /** Valinta koskee sekä kartan viivaa että seuraavaa ajoa. */
    fun setRouteChoice(choice: RouteChoice) {
        viewModelScope.launch { routeChoiceStore.set(choice) }
    }

    /** Kartta-välilehti näkyvissä: piilossa ja ajon aikana vertailua ei haeta, koska palvelin on yhteinen ajon reittien kanssa. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        if (!visible) previewLoader.cancel()
    }

    private fun plannedStops(stops: List<StopEntity>) = stops.map { it.id to routeRepository.toPlanned(it, dateEpochDay).location }

    private fun publishPreviews(previews: Map<RouteChoice, RoutePreview>) {
        _state.value = _state.value.copy(comparison = _state.value.comparison.copy(previews = previews))
    }

    /** Epäonnistunut haku jättää kartalle suunnitelman oman reitin ja piilottaa valinnan: ajon voi silti aloittaa. */
    private suspend fun loadPreviews(origin: GeoPoint?, stops: List<StopEntity>) {
        if (driving || !mapVisible) return
        try {
            previewLoader.load(origin, plannedStops(stops), _state.value.comparison.choice, ::publishPreviews)
        } catch (e: RoutePreviewLoader.PreviewFailed) {
            Diag.event("kartta", "reittivertailu ei onnistunut ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun fetchRoute(dayId: Long, stops: List<StopEntity>) {
        fetchJob?.cancel()
        previewLoader.stopsChanged(plannedStops(stops), ::publishPreviews)
        fetchJob = viewModelScope.launch {
            delay(300) // koalisoi peräkkäiset siirrot yhdeksi Routes-hauksi
            _state.value = _state.value.copy(loadingRoute = true)
            val start = currentLocation(application)
            val planned = runCatching {
                // Jaettu välimuisti: sama suunnitelma kuin Suunnittelulla ja
                // ajosessiolla — verkkoon vain jos järjestys oikeasti muuttui.
                val plan = routeRepository.routePlan(dayId, start, stops, dateEpochDay)
                val stats = historyRepository.currentStats()
                val legs = routeRepository.learnedLegDurations(stops, plan.legDurationsSec, stats.speedFactor)
                val schedule = etaEngine.compute(
                    EtaInput(
                        nowEpochSec = System.currentTimeMillis() / 1000,
                        liveEtaToNextSec = null,
                        remainingStops = stops.map { routeRepository.toPlanned(it, dateEpochDay) },
                        legPredictedSec = legs,
                        stats = stats,
                    )
                )
                plan to schedule
            }.onSuccess { (plan, schedule) ->
                val rows = stops.mapIndexed { i, stop ->
                    OverviewStopRow(
                        stop = stop,
                        distanceFromPrevM = plan.legDistancesM.getOrNull(i)?.takeIf { it >= 0 },
                        arrivalEpochSec = schedule.stopEtas.getOrNull(i)?.arrivalEpochSec,
                    )
                }
                _state.value = _state.value.copy(
                    loadingRoute = false,
                    origin = plan.origin,
                    routePoints = Polyline.decode(plan.encodedPolyline),
                    rows = rows,
                    totalDistanceM = plan.legDistancesM.filter { it >= 0 }.sum(),
                    totalDurationSec = plan.totalDurationSec,
                    finishEpochSec = schedule.finishEpochSec,
                    // Myös automaattisesti toipunut haku kuittaa vanhan
                    // virheilmoituksen, ei vain Yritä uudelleen -nappi.
                    error = null,
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Diag.event("kartta", "reittivirhe ${e.javaClass.simpleName}: ${e.message}")
                // Vanha polyline ja summat eivät saa jäädä näkyviin uudella
                // järjestyksellä ilman varoitusta — numeroidut markerit
                // säilyvät, koska ne piirretään stoppilistasta.
                _state.value = _state.value.copy(
                    loadingRoute = false,
                    // Lähtömerkki samaan paikkaan kuin vertailun viiva, joka lähtee tuoreesta sijainnista.
                    origin = start ?: _state.value.origin,
                    routePoints = emptyList(),
                    rows = emptyList(),
                    totalDistanceM = 0,
                    totalDurationSec = 0,
                    finishEpochSec = null,
                    error = application.getString(UserErrors.messageRes(e)),
                )
            }
            // Oma haku: vertailu toimii, vaikka suunnitelman palvelin olisi alhaalla.
            loadPreviews(RouteComparison.previewOrigin(planned.map { (plan, _) -> plan.origin }, start), stops)
        }
    }

    /** Yritä epäonnistunutta reittihakua uudelleen nykyiselle järjestykselle. */
    fun retryRoute() {
        val id = dayId ?: return
        val stops = _state.value.stops
        if (stops.isEmpty()) return
        _state.value = _state.value.copy(error = null)
        fetchRoute(id, stops)
    }
}
