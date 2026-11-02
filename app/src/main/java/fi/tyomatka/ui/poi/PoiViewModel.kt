package fi.tyomatka.ui.poi

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.R
import fi.tyomatka.core.api.ReverseGeocoder
import fi.tyomatka.core.api.RouteEta
import fi.tyomatka.core.api.ValhallaEtaClient
import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiCorrectionField
import fi.tyomatka.core.poi.PoiGrid
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.AddStopResult
import fi.tyomatka.data.repo.CarRepository
import fi.tyomatka.data.repo.FuelRepository
import fi.tyomatka.data.repo.NextCarStore
import fi.tyomatka.data.repo.PoiLoad
import fi.tyomatka.data.repo.PoiRepository
import fi.tyomatka.data.repo.PoiSettings
import fi.tyomatka.data.repo.PoiSettingsStore
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.data.repo.ServiceStopPlacement
import fi.tyomatka.data.repo.ServiceStopPlaces
import fi.tyomatka.data.repo.WorkLogRepository
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.defaultPlateWithChoice
import fi.tyomatka.data.repo.defaultPoiSettings
import fi.tyomatka.diag.Diag
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.location.currentLocation
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.ui.UserErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Avattu kohdekortti: kohde, matka ja aika (tai linnuntie), kuljettajan korjaukset; [info] lähteet kentittäin
 * yhdistettyinä (OSM, kertatuonti, Fintraffic, ilmoitukset); [chargingNearby] Fintrafficin latauspisteet 150 m:n säteellä.
 */
data class PoiSelection(
    val item: PoiItem,
    val info: StationInfo? = null,
    val chargingNearby: List<PoiItem> = emptyList(),
    val eta: RouteEta? = null,
    val etaFailed: Boolean = false,
    val straightM: Long? = null,
    val corrections: Map<PoiCorrectionField, String> = emptyMap(),
    /** Kohteen omat ja muiden ilmoitukset: lomake näyttää niistä voimassa olevan valinnan ja sen tilan. */
    val reports: List<StationReport> = emptyList(),
)

data class PoiUiState(
    val settings: PoiSettings = PoiSettings(emptySet(), heavyOnly = false, routeOnly = false),
    /** Näytettävät merkit suodatettuina (luokat, raskas kalusto, reitin varrella); himmennetyt omana kerroksenaan. */
    val markers: List<PoiMarker> = emptyList(),
    val loading: Boolean = false,
    /** Kartta on liian kaukana (zoom alle 9) tai ikkuna liian suuri: kohteita ei haeta. */
    val zoomedOut: Boolean = false,
    val cacheAgeDays: Int? = null,
    val offline: Boolean = false,
    val unavailable: Boolean = false,
    /** Ikkunassa on enemmän kohteita kuin palvelin palauttaa: vihje lähentää. */
    val truncated: Boolean = false,
    val hasRoute: Boolean = false,
    val driving: Boolean = false,
    val selection: PoiSelection? = null,
    val notice: String? = null,
)

private data class Viewport(val box: PoiBox?, val zoom: Double)

private data class FilterInput(
    val items: List<PoiItem>,
    val settings: PoiSettings,
    val route: List<GeoPoint>,
    val loads: List<Pair<PoiCategory, PoiLoad>>,
    val reports: List<StationReport>,
)

/**
 * Karttakohteet Kartta-välilehdelle ja Ajo-näkymälle: haku pyöristetylle ikkunalle luokka kerrallaan 300 ms:n viiveellä,
 * suodatus laitteessa, kortin ETA yhdellä Valhalla-pyynnöllä ja lisäys päivän listaan huoltostoppina.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PoiViewModel @Inject constructor(
    private val application: Application,
    private val poiRepository: PoiRepository,
    private val reportRepository: ReportRepository,
    private val settingsStore: PoiSettingsStore,
    private val routeRepository: RouteRepository,
    workLogRepository: WorkLogRepository,
    fuelRepository: FuelRepository,
    carRepository: CarRepository,
    nextCarStore: NextCarStore,
    private val etaClient: ValhallaEtaClient,
    reverse: ReverseGeocoder,
    private val sessionManager: DriveSessionManager,
    private val engine: NavigationEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(PoiUiState())
    val state: StateFlow<PoiUiState> = _state.asStateFlow()

    /** Ajo-näkymä palauttaa seurannan, kun kohde on lisätty seuraavaksi pysähdykseksi. */
    private val _recenterRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recenterRequests: SharedFlow<Unit> = _recenterRequests.asSharedFlow()

    private val viewport = MutableStateFlow<Viewport?>(null)
    private val route = MutableStateFlow<List<GeoPoint>>(emptyList())
    private val rawItems = MutableStateFlow<List<PoiItem>>(emptyList())
    /** Viimeisimmän haun vastaukset luokittain: katkaisuvihje riippuu myös rekkasuodattimesta. */
    private val windowLoads = MutableStateFlow<List<Pair<PoiCategory, PoiLoad>>>(emptyList())
    /** Ikkunan muiden kuljettajien ilmoitukset; haetaan kerran ikkunaa kohti, kortin avauksessa ja ilmoituksen jälkeen. */
    private val remoteReports = MutableStateFlow<List<StationReport>>(emptyList())
    /** Ikkunan ilmoitukset ja omat suoraan kannasta: oma ilmoitus näkyy heti, vaikka ikkunahaku hylättäisiin. */
    private val reports: StateFlow<List<StationReport>> = combine(remoteReports, reportRepository.observeOwn()) { remote, own -> remote + own }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Ilmoitusten ikkuna: vain nykyisen ikkunan haut kirjoittavat [remoteReports]-arvon. */
    private val reportWindow = ReportWindowGuard()
    private val settingsFlow: StateFlow<PoiSettings>
    private val places = ServiceStopPlaces(reverse)
    private val gate = PoiQueryGate()
    private var etaJob: Job? = null
    private var selectionJob: Job? = null
    private var noticeJob: Job? = null
    private var chargingJob: Job? = null
    private var reportsJob: Job? = null
    private var cardReportsJob: Job? = null

    init {
        viewModelScope.launch {
            // Omat ilmoitukset poistettu: muistissa olevat ja kesken olevat ikkunat eivät enää pidä paikkaansa.
            reportRepository.forgotten.drop(1).collect {
                reportsJob?.cancel()
                cardReportsJob?.cancel()
                remoteReports.value = emptyList()
                reportWindow.moveTo(reportWindow.box)
                reportsJob = reportWindow.begin()?.let { fetch -> viewModelScope.launch { refreshReports(fetch) } }
            }
        }
        val openSegmentToday = routeRepository.observeTodayDay()
            .flatMapLatest { day -> workLogRepository.observeForDay(day.id) }
            .map { segments -> segments.firstOrNull { it.endEpochSec == null } }
        val defaultFuel: Flow<FuelKind?> = combine(
            workLogRepository.observeAll(), fuelRepository.observeAll(), carRepository.observeAll(), nextCarStore.choice, openSegmentToday,
        ) { segments, fillups, cars, choice, open ->
            defaultPlateWithChoice(choice, segments, fillups, cars, open)?.let { plate -> carFuel(plate, cars.associateBy { it.plate }, fillups) }
        }
        settingsFlow = combine(settingsStore.settings, defaultFuel) { saved, fuel -> saved ?: defaultPoiSettings(fuel) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PoiUiState().settings)

        viewModelScope.launch {
            combine(viewport, settingsFlow.map { it.categories }.distinctUntilChanged()) { v, cats -> v to cats }
                .collectLatest { (v, cats) ->
                    delay(QUERY_DELAY_MS)
                    load(v, cats)
                }
        }
        viewModelScope.launch {
            combine(rawItems, settingsFlow, route, windowLoads, reports) { items, settings, points, loads, window ->
                FilterInput(items, settings, points, loads, window)
            }
                .collectLatest { input ->
                    val markers = withContext(Dispatchers.Default) { poiMarkers(input.items, input.reports, input.settings, input.route) }
                    val truncated = poiWindowTruncated(input.loads, input.settings.heavyOnly)
                    _state.update { state ->
                        state.copy(
                            markers = markers,
                            settings = input.settings,
                            hasRoute = input.route.size >= 2,
                            truncated = truncated,
                            selection = state.selection?.withReports(input.reports),
                        )
                    }
                }
        }
        viewModelScope.launch {
            sessionManager.state.collect { drive ->
                val driving = drive.phase == DrivePhase.NAVIGATING || drive.phase == DrivePhase.AT_STOP
                if (_state.value.driving != driving) _state.update { it.copy(driving = driving) }
            }
        }
    }

    /** Kartan näkyvä ikkuna ja zoom liikkeen päätyttyä; null-ikkuna (kartta ei vielä valmis) tyhjentää kohteet. */
    fun onViewport(box: PoiBox?, zoom: Double) {
        viewport.value = Viewport(box, zoom)
    }

    /** Päivän reittiviiva "Vain reitin varrella" -suodattimelle. */
    fun setRoute(points: List<GeoPoint>) {
        route.value = points
    }

    fun toggleCategory(category: PoiCategory) {
        val current = settingsFlow.value
        val categories = if (category in current.categories) current.categories - category else current.categories + category
        save(current.copy(categories = categories))
    }

    fun setHeavyOnly(on: Boolean) = save(settingsFlow.value.copy(heavyOnly = on))

    fun setRouteOnly(on: Boolean) = save(settingsFlow.value.copy(routeOnly = on))

    private fun save(settings: PoiSettings) {
        Diag.event("kohteet", "asetukset ${settings.categories.map { it.apiName }} raskas=${settings.heavyOnly} reitti=${settings.routeOnly}")
        viewModelScope.launch { runCatching { settingsStore.save(settings) } }
    }

    /** Kortti auki ([poiId]) tai kiinni (null). Yksi ETA-pyyntö korttia kohti; sulkeminen peruu sen. */
    fun select(poiId: String?) {
        etaJob?.cancel()
        selectionJob?.cancel()
        chargingJob?.cancel()
        if (poiId == null) {
            _state.update { it.copy(selection = null) }
            return
        }
        val item = rawItems.value.firstOrNull { it.id == poiId } ?: return
        Diag.event("kohteet", "kortti ${item.id} ${item.category.apiName}")
        _state.update { it.copy(selection = PoiSelection(item).withReports(reports.value)) }
        if (item.category == PoiCategory.FUEL || item.category == PoiCategory.GAS) chargingJob = nearbyCharging(item)
        // Kortin avaus hakee ikkunan ilmoitukset uudelleen hakuportista riippumatta: toisen kuljettajan alueen latauksen
        // jälkeen tekemä ilmoitus (esim. "Asema suljettu") näkyy heti.
        reportWindow.begin()?.let { fetch ->
            cardReportsJob?.cancel()
            cardReportsJob = viewModelScope.launch {
                // Jonossa odottava oma ilmoitus lähtee samalla, jos verkko on palannut.
                runCatching { reportRepository.flush() }.onFailure { if (it is CancellationException) throw it }
                refreshReports(fetch)
            }
        }
        selectionJob = viewModelScope.launch {
            poiRepository.observeCorrections(poiId).collect { corrections -> updateSelection(poiId) { it.copy(corrections = corrections) } }
        }
        etaJob = viewModelScope.launch {
            val here = (if (_state.value.driving) engine.location.value else null) ?: currentLocation(application) ?: return@launch
            updateSelection(poiId) { it.copy(straightM = (distanceKm(here, item.location) * 1000).roundToLong()) }
            val eta = runCatching { etaClient.eta(here, item.location) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Diag.event("kohteet", "ETA-virhe ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            updateSelection(poiId) { if (eta != null) it.copy(eta = eta) else it.copy(etaFailed = true) }
        }
    }

    private fun nearbyCharging(item: PoiItem): Job = viewModelScope.launch {
        val near = chargingNear(poiRepository, item) ?: return@launch
        updateSelection(item.id) { it.copy(chargingNearby = near) }
    }

    /** Ikkunan ilmoitukset; hylätään, jos kartta siirtyi tai näkyvä sisältö on uudempi. */
    private suspend fun refreshReports(fetch: ReportFetch) {
        // Odottamaton virhe (esim. kanta) ei pyyhi jo näkyviä ilmoituksia; verkko- ja vastausvirheet tulevat välimuistista.
        val window = runCatching { reportRepository.remote(fetch.box) }.getOrElse { e ->
            if (e is CancellationException) throw e
            Diag.event("ilmoitus", "ikkunavirhe ${e.javaClass.simpleName}")
            return
        }
        if (reportWindow.accept(fetch, window.version)) remoteReports.value = window.items
    }

    private fun updateSelection(poiId: String, transform: (PoiSelection) -> PoiSelection) {
        _state.update { state ->
            val selection = state.selection?.takeIf { it.item.id == poiId } ?: return@update state
            state.copy(selection = transform(selection))
        }
    }

    /** Avattu kohde päivän listan loppuun tai seuraavaksi pysähdykseksi huoltostoppina; sulkee kortin. */
    fun addSelectedAsStop(placement: ServiceStopPlacement) {
        val item = _state.value.selection?.item ?: return
        viewModelScope.launch {
            runCatching {
                val dayId = poiStopDayId(sessionManager.state.value) { routeRepository.todayDay().id }
                val place = places.place(item, application.getString(PoiCardText.categoryNameRes(item.category)))
                routeRepository.addServiceStop(dayId, place, PoiServices.defaultServiceSec(item.category), placement)
            }.onSuccess { result ->
                Diag.event("kohteet", "lisäys ${item.id} $placement ${result::class.simpleName}")
                when (result) {
                    is AddStopResult.Added -> notice(
                        application.getString(if (placement == ServiceStopPlacement.NEXT) R.string.poi_added_next else R.string.poi_added, result.stop.label),
                    )
                    is AddStopResult.Duplicate -> notice(application.getString(R.string.stop_duplicate, result.existing.label))
                }
                select(null)
                if (placement == ServiceStopPlacement.NEXT) _recenterRequests.tryEmit(Unit)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Diag.event("kohteet", "lisäysvirhe ${e.javaClass.simpleName}: ${e.message}")
                notice(application.getString(UserErrors.messageRes(e)))
            }
        }
    }

    /** Ilmoita muutos avatusta kohteesta: jonoon ja heti lähetykseen; kortti ja merkit päivittyvät ikkunan ilmoituksista. */
    fun report(field: ReportField, value: String) {
        val item = _state.value.selection?.item ?: return
        viewModelScope.launch {
            runCatching { reportRepository.report(item.id, field, value) }
                .onSuccess { outcome ->
                    Diag.event("ilmoitus", "${item.category.apiName} ${field.key} $outcome")
                    ReportChoices.noticeRes(outcome)?.let { notice(application.getString(it)) }
                    reportWindow.begin()?.let { refreshReports(it) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Diag.event("ilmoitus", "ilmoitusvirhe ${e.javaClass.simpleName}")
                    notice(application.getString(UserErrors.messageRes(e)))
                }
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _state.update { it.copy(notice = null) }
    }

    private fun notice(message: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = message) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    private suspend fun load(viewport: Viewport?, categories: Set<PoiCategory>) {
        val snapped = viewport?.box?.let { PoiGrid.snap(it, viewport.zoom) }
        val zoomedOut = viewport == null || viewport.zoom < PoiGrid.MIN_ZOOM || snapped == null
        if (zoomedOut || categories.isEmpty()) {
            gate.reset()
            rawItems.value = emptyList()
            windowLoads.value = emptyList()
            reportsJob?.cancel()
            reportWindow.moveTo(null)
            remoteReports.value = emptyList()
            _state.update { it.copy(zoomedOut = zoomedOut, loading = false, cacheAgeDays = null, offline = false, unavailable = false) }
            return
        }
        val query = PoiQuery(snapped, categories)
        if (!gate.shouldLoad(query, nowSec())) {
            _state.update { it.copy(zoomedOut = false) }
            return
        }
        _state.update { it.copy(zoomedOut = false, loading = true) }
        reportWindow.moveTo(snapped)
        // Ilmoitukset omana työnään: merkit eivät odota jonon lähetystä eivätkä ilmoitushakua (heikko verkko). Edellisen
        // ikkunan korttihaku perutaan, koska tämä haku lähettää jonon samoin; vartija hylkää sen vastauksen joka tapauksessa.
        reportsJob?.cancel()
        cardReportsJob?.cancel()
        reportsJob = reportWindow.begin()?.let { fetch ->
            viewModelScope.launch {
                runCatching { reportRepository.flush() }.onFailure { if (it is CancellationException) throw it }
                refreshReports(fetch)
            }
        }
        val loads = runCatching {
            coroutineScope { categories.map { category -> async { poiRepository.load(snapped, category) } }.awaitAll() }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Diag.event("kohteet", "hakuvirhe ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        rawItems.value = loads.flatMap { it.items }
        windowLoads.value = if (loads.size == categories.size) categories.toList().zip(loads) else emptyList()
        val offline = loads.any { it.offline }
        val unavailable = loads.any { it.unavailable }
        gate.loaded(query, nowSec(), failed = offline || unavailable || loads.size < categories.size, truncated = loads.any { it.truncated })
        Diag.event("kohteet", "haku ${PoiGrid.cacheKey(snapped, categories.first())} luokkia=${categories.size} kohteita=${rawItems.value.size}")
        _state.update {
            it.copy(
                loading = false,
                cacheAgeDays = loads.mapNotNull { load -> load.cacheAgeDays }.maxOrNull(),
                offline = offline,
                unavailable = unavailable,
            )
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val QUERY_DELAY_MS = 300L
        const val NOTICE_MS = 5_000L
    }
}

private const val CHARGING_NEARBY_KM = 0.15

/**
 * Fintrafficin latauspisteet aseman kortille: kohteen ympäriltä hienon ruudukon ikkunasta, 150 m:n säteeltä; null, kun
 * haku epäonnistui.
 */
internal suspend fun chargingNear(poiRepository: PoiRepository, item: PoiItem): List<PoiItem>? {
    val around = PoiBox(item.location.lng - 0.004, item.location.lat - 0.002, item.location.lng + 0.004, item.location.lat + 0.002)
    val box = PoiGrid.snap(around, PoiGrid.FINE_ZOOM) ?: return null
    val load = runCatching { poiRepository.load(box, PoiCategory.CHARGING) }.getOrElse { e ->
        if (e is CancellationException) throw e
        return null
    }
    return load.items.filter { it.id.startsWith("c-") && distanceKm(it.location, item.location) <= CHARGING_NEARBY_KM }
}
