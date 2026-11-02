package fi.tyomatka.ui.plan.nearby

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.R
import fi.tyomatka.core.api.ValhallaEtaClient
import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.poi.NearbyCells
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.data.repo.NEARBY_RADIUS_KM
import fi.tyomatka.data.repo.NearbyRepository
import fi.tyomatka.data.repo.NearbySettings
import fi.tyomatka.data.repo.NearbySettingsStore
import fi.tyomatka.data.repo.PoiRepository
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.diag.Diag
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.location.currentLocation
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.ui.UserErrors
import fi.tyomatka.ui.poi.PoiSelection
import fi.tyomatka.ui.poi.ReportChoices
import fi.tyomatka.ui.poi.withReports
import fi.tyomatka.ui.poi.chargingNear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Kohteet-sivun tila: [session] lähtöpisteen luku ja lataus; [rows] valitun lajin rivit valitulla vyöhykkeellä;
 * [categoryCounts] ladattujen lajien määrät valitulla vyöhykkeellä; [bandCounts] vyöhykkeiden osumat vain, kun
 * hakukentässä on tekstiä; [selection] avattu kortti.
 */
data class NearbyUiState(
    val session: NearbySessionState = NearbySessionState.Idle,
    val choices: NearbyChoices = NearbyChoices(),
    val rows: List<NearbyRow> = emptyList(),
    val categoryCounts: Map<PoiCategory, Int> = emptyMap(),
    val bandCounts: Map<NearbyBand, Int>? = null,
    val selection: PoiSelection? = null,
    val notice: String? = null,
)

/**
 * Kohteet-sivu stopin lisäysikkunassa: lähtöpiste kerran ikkunaa kohti ([open]), lataus [NearbyRepository]sta ja listan
 * rajaus puhtailla funktioilla. Lisäys stopiksi kulkee [fi.tyomatka.ui.plan.PlanViewModel.addPoi]-kutsun kautta.
 */
@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val application: Application,
    repository: NearbyRepository,
    private val settingsStore: NearbySettingsStore,
    private val poiRepository: PoiRepository,
    private val reportRepository: ReportRepository,
    private val etaClient: ValhallaEtaClient,
    private val sessionManager: DriveSessionManager,
    private val engine: NavigationEngine,
) : ViewModel() {

    // Latauksen koosteet (etäisyydet, yhdistäminen) taustasäikeessä: tuhannet latauspisteet eivät nyi näkymää.
    private val session = NearbySession(viewModelScope, ::locate) { center, first -> repository.load(center, first).flowOn(Dispatchers.Default) }
    private val choices = MutableStateFlow(NearbyChoices())
    /** Ilmoituksen jälkeen listan ikkunoista haetut muiden ilmoitukset; null = latauksen ilmoitukset. */
    private val refreshedReports = MutableStateFlow<List<StationReport>?>(null)
    private val reports = MutableStateFlow<List<StationReport>>(emptyList())

    /** Hakukentän teksti Compose-tilana: kenttä päivittyy heti, eikä nopea kirjoitus hukkaa merkkejä listan laskennan aikana. */
    var queryText by mutableStateOf("")
        private set

    private val _state = MutableStateFlow(NearbyUiState())
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private var settingsLoaded = false
    private var etaJob: Job? = null
    private var selectionJob: Job? = null
    private var chargingJob: Job? = null
    private var reportsJob: Job? = null
    private var noticeJob: Job? = null
    /** Ikkunan avauskerta: sulkemisen jälkeen valmistuva ilmoitus ei näy eikä päivitä seuraavaa avausta. */
    private var generation = 0

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { saved ->
                settingsLoaded = true
                choices.update { it.copy(category = saved.category, heavyOnly = saved.heavyOnly) }
            }
        }
        viewModelScope.launch {
            // Omat ilmoitukset poistettu: avatun listan ilmoitukset haetaan uudelleen.
            reportRepository.forgotten.drop(1).collect {
                val center = (_state.value.session as? NearbySessionState.Loaded)?.center ?: return@collect
                refreshedReports.value = emptyList()
                refreshReports(center, generation)
            }
        }
        viewModelScope.launch {
            combine(session.state, refreshedReports, reportRepository.observeOwn()) { state, refreshed, own ->
                val remote = refreshed ?: (state as? NearbySessionState.Loaded)?.data?.reports.orEmpty()
                remote + own
            }.collect { reports.value = it }
        }
        viewModelScope.launch {
            combine(session.state, choices, reports) { state, choices, reports -> Triple(state, choices, reports) }
                .collectLatest { (state, choices, reports) ->
                    val loaded = state as? NearbySessionState.Loaded
                    val view = if (loaded == null) {
                        NearbyView(emptyList(), emptyMap(), null)
                    } else {
                        withContext(Dispatchers.Default) { nearbyView(loaded.data.items, reports, loaded.center, choices) }
                    }
                    _state.update { ui ->
                        ui.copy(
                            session = state,
                            choices = choices,
                            rows = view.rows,
                            categoryCounts = view.categoryCounts,
                            bandCounts = view.bandCounts,
                            selection = ui.selection?.withReports(reports),
                        )
                    }
                }
        }
    }

    /** Ajossa navigoinnin sijainti kuten kortin etäisyydessä, muuten puhelimen sijainti. */
    private suspend fun locate() = sessionManager.state.value.phase
        .takeIf { it == DrivePhase.NAVIGATING || it == DrivePhase.AT_STOP }
        ?.let { engine.location.value }
        ?: currentLocation(application)

    /** Kohteet-sivu näkyviin ensimmäisen kerran ikkunan aukiolon aikana: lähtöpiste ja lataus valitusta lajista alkaen. */
    fun open() {
        if (!settingsLoaded) Diag.event("kohdelista", "avaus ennen asetuksia")
        session.open(choices.value.category)
    }

    /** Ikkuna suljettu: lataus ja sijainnin luku perutaan, ja vyöhyke, haku ja kortti nollataan. */
    fun close() {
        choices.value.query.trim().takeIf { it.isNotEmpty() }?.let { Diag.event("kohdelista", "haku ${it.length} merkkiä") }
        generation++
        session.close()
        cancelSelectionJobs()
        reportsJob?.cancel()
        refreshedReports.value = null
        queryText = ""
        choices.update { it.copy(band = NearbyBand.NEAR, query = "") }
        noticeJob?.cancel()
        _state.update { it.copy(selection = null, notice = null) }
    }

    fun setBand(band: NearbyBand) {
        Diag.event("kohdelista", "vyöhyke ${band.name}")
        choices.update { it.copy(band = band) }
    }

    fun setCategory(category: PoiCategory) {
        Diag.event("kohdelista", "laji ${category.apiName}")
        choices.update { it.copy(category = category) }
        save()
    }

    /** Raskas päälle tai pois nykyisestä valinnasta (ei näkymän tilasta, jottei nopea tuplanapautus hukku). */
    fun toggleHeavy() {
        val on = !choices.value.heavyOnly
        Diag.event("kohdelista", "raskas $on")
        choices.update { it.copy(heavyOnly = on) }
        save()
    }

    fun setQuery(text: String) {
        queryText = text
        choices.update { it.copy(query = text) }
    }

    private fun save() {
        val current = choices.value
        viewModelScope.launch { runCatching { settingsStore.save(NearbySettings(current.category, current.heavyOnly)) } }
    }

    /** Kortti auki ([poiId]) tai kiinni (null): tiedot, linnuntie ja ajoaika lähtöpisteestä, korjaukset ja latauspisteet. */
    fun select(poiId: String?) {
        cancelSelectionJobs()
        val loaded = _state.value.session as? NearbySessionState.Loaded
        val item = poiId?.let { id -> loaded?.data?.items?.let { nearbyItem(it, choices.value.category, id) } }
        if (loaded == null || item == null) {
            _state.update { it.copy(selection = null) }
            return
        }
        val straightM = (distanceKm(loaded.center, item.location) * 1000).roundToLong()
        _state.update { it.copy(selection = PoiSelection(item, straightM = straightM).withReports(reports.value)) }
        if (item.category == PoiCategory.FUEL || item.category == PoiCategory.GAS) {
            chargingJob = viewModelScope.launch {
                chargingNear(poiRepository, item)?.let { near -> updateSelection(item.id) { it.copy(chargingNearby = near) } }
            }
        }
        selectionJob = viewModelScope.launch {
            poiRepository.observeCorrections(item.id).collect { corrections -> updateSelection(item.id) { it.copy(corrections = corrections) } }
        }
        etaJob = viewModelScope.launch {
            val eta = runCatching { etaClient.eta(loaded.center, item.location) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Diag.event("kohdelista", "ETA-virhe ${e.javaClass.simpleName}")
                null
            }
            updateSelection(item.id) { if (eta != null) it.copy(eta = eta) else it.copy(etaFailed = true) }
        }
    }

    private fun cancelSelectionJobs() {
        etaJob?.cancel()
        selectionJob?.cancel()
        chargingJob?.cancel()
    }

    private fun updateSelection(poiId: String, transform: (PoiSelection) -> PoiSelection) {
        _state.update { state ->
            val selection = state.selection?.takeIf { it.item.id == poiId } ?: return@update state
            state.copy(selection = transform(selection))
        }
    }

    /** Ilmoita muutos avatusta kohteesta: oma näkyy heti, ja muiden ilmoitukset haetaan uudelleen listan ikkunoille. */
    fun report(field: ReportField, value: String) {
        val item = _state.value.selection?.item ?: return
        val center = (_state.value.session as? NearbySessionState.Loaded)?.center ?: return
        val opened = generation
        viewModelScope.launch {
            runCatching { reportRepository.report(item.id, field, value) }
                .onSuccess { outcome ->
                    Diag.event("ilmoitus", "${item.category.apiName} ${field.key} $outcome")
                    if (opened != generation) return@onSuccess
                    ReportChoices.noticeRes(outcome)?.let { notice(application.getString(it)) }
                    refreshReports(center, opened)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Diag.event("ilmoitus", "ilmoitusvirhe ${e.javaClass.simpleName}")
                    if (opened == generation) notice(application.getString(UserErrors.messageRes(e)))
                }
        }
    }

    private fun refreshReports(center: GeoPoint, opened: Int) {
        reportsJob?.cancel()
        reportsJob = viewModelScope.launch {
            val items = runCatching {
                NearbyCells.windows(center, NEARBY_RADIUS_KM).flatMap { reportRepository.remote(it).items }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Diag.event("ilmoitus", "ikkunavirhe ${e.javaClass.simpleName}")
                return@launch
            }
            // Ikkuna suljettiin tai avattiin uudelleen: vanhan avauksen ilmoitukset eivät kelpaa.
            if (opened == generation) refreshedReports.value = items
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

    private companion object {
        const val NOTICE_MS = 5_000L
    }
}
