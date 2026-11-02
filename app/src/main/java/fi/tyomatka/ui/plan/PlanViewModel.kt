package fi.tyomatka.ui.plan

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.R
import fi.tyomatka.diag.Diag
import fi.tyomatka.core.text.ImportedText
import fi.tyomatka.core.api.PlaceSearch
import fi.tyomatka.core.api.ReverseGeocoder
import fi.tyomatka.core.eta.EtaEngine
import fi.tyomatka.core.eta.EtaInput
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.ScheduleSnapshot
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.data.db.SavedAddressEntity
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.AddStopResult
import fi.tyomatka.data.repo.HistoryRepository
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.data.repo.ServiceStopPlacement
import fi.tyomatka.data.repo.ServiceStopPlaces
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.location.currentLocation
import fi.tyomatka.location.lastLocation
import fi.tyomatka.ui.UserErrors
import fi.tyomatka.ui.poi.PoiCardText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlanUiState(
    val dayId: Long? = null,
    val stops: List<StopEntity> = emptyList(),
    val suggestions: List<PlaceSuggestion> = emptyList(),
    /** Osoitemuistin osumat — tyhjällä haulla viimeksi käytetyt. */
    val savedSuggestions: List<SavedAddressEntity> = emptyList(),
    val searchText: String = "",
    val geocodingList: Boolean = false,
    /** Aikatauluennuste kuljettajan omalle järjestykselle (per stoppi -ETAt + valmistumisaika). */
    val schedule: ScheduleSnapshot? = null,
    /** Stoppi, jota kohti ajetaan tai jolla ollaan — sen osoitetta ei voi
     *  muokata ajon aikana, ja kenttä lukitaan jo ennen verkkohakuja. */
    val activeDriveStopId: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val application: Application,
    private val routeRepository: RouteRepository,
    private val historyRepository: HistoryRepository,
    private val placeSearch: PlaceSearch,
    private val etaEngine: EtaEngine,
    private val sessionManager: DriveSessionManager,
    reverse: ReverseGeocoder,
) : ViewModel() {

    private val places = ServiceStopPlaces(reverse)

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var scheduleJob: Job? = null
    private var noticeJob: Job? = null
    private var dateEpochDay: Long = 0

    init {
        viewModelScope.launch {
            refreshSavedSuggestions("")
            // Vuorokauden vaihtuessa siirrytään uuteen päivään lennossa.
            routeRepository.observeTodayDay().collectLatest { day ->
                dateEpochDay = day.dateEpochDay
                _state.value = _state.value.copy(dayId = day.id, stops = emptyList(), schedule = null)
                routeRepository.observeStops(day.id).collect { stops ->
                    _state.value = _state.value.copy(stops = stops)
                    refreshSchedule(day.id, stops)
                }
            }
        }
        viewModelScope.launch {
            // Muokkausnäkymä lukitsee ajossa olevan kohteen osoitekentän
            // reaktiivisesti — myös jos ajo etenee näkymän ollessa auki.
            sessionManager.state.collect { drive ->
                val activeId = if (drive.phase == DrivePhase.NAVIGATING ||
                    drive.phase == DrivePhase.AT_STOP
                ) {
                    drive.currentStop?.id
                } else {
                    null
                }
                if (_state.value.activeDriveStopId != activeId) {
                    _state.value = _state.value.copy(activeDriveStopId = activeId)
                }
            }
        }
    }

    /**
     * Laskee aikatauluennusteen kuljettajan omalle järjestykselle aina kun
     * stoppilista muuttuu. Reittisuunnitelma tulee jaetusta välimuistista;
     * verkkoon mennään vain jos järjestys tai lähtöpaikka oikeasti muuttui.
     */
    private fun refreshSchedule(dayId: Long, stops: List<StopEntity>) {
        scheduleJob?.cancel()
        val remaining = stops.filter { it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
        if (remaining.isEmpty()) {
            _state.value = _state.value.copy(schedule = null)
            return
        }
        scheduleJob = viewModelScope.launch {
            delay(500) // koalisoi peräkkäiset lisäykset yhdeksi reittihauksi
            runCatching {
                val plan = routeRepository.routePlan(dayId, currentLocation(application), remaining, dateEpochDay)
                val stats = historyRepository.currentStats()
                val legs = routeRepository.learnedLegDurations(remaining, plan.legDurationsSec, stats.speedFactor)
                etaEngine.compute(
                    EtaInput(
                        nowEpochSec = nowSec(),
                        liveEtaToNextSec = null,
                        remainingStops = remaining.map { routeRepository.toPlanned(it, dateEpochDay) },
                        legPredictedSec = legs,
                        stats = stats,
                    )
                )
            }.onSuccess { schedule ->
                Diag.event("suunnittelu", "ennuste ${remaining.size} stoppia, valmis ${fi.tyomatka.ui.formatClock(schedule.finishEpochSec)}")
                // Onnistunut laskenta pyyhkii ennusteen oman virheilmoituksen (ilmoitus jäi näkyviin).
                val error = _state.value.error.takeUnless { it == scheduleError }
                scheduleError = null
                _state.value = _state.value.copy(schedule = schedule, error = error)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                // Ennusteen verkkovirhe on ohimenevä (ajon aikana yhteys katkeilee, vaikka navigointi jatkuu):
                // lyhyt ilmoitus, joka häviää itsestään, ei pysyvä virhe.
                scheduleError = errorText(e)
                Diag.event("suunnittelu", "ennuste virhe ${e.javaClass.simpleName}: ${e.message}")
                showNotice(errorText(e))
            }
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    fun onSearchChanged(text: String) {
        _state.value = _state.value.copy(searchText = text)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Paikallinen osoitemuisti heti ilman viivettä.
            refreshSavedSuggestions(text)
            if (text.length < 3) {
                _state.value = _state.value.copy(suggestions = emptyList())
                return@launch
            }
            // Kirjoitustauko: näin haku lähtee vasta tauolla eikä joka näppäilyllä, mikä säästää Digitransitin kiintiötä.
            delay(400)
            runCatching {
                placeSearch.autocomplete(text, lastLocation(application))
            }.onSuccess { suggestions ->
                // Hakusana ja tulosten määrä (osoitteet ja yritykset yhteensä).
                Diag.event("suunnittelu", "haku '$text' ${suggestions.size} tulosta")
                val savedIds = _state.value.savedSuggestions.mapNotNull { it.placeId }.toSet()
                _state.value = _state.value.copy(
                    suggestions = suggestions.filter { it.placeId !in savedIds }
                )
            }.onFailure(::reportError)
        }
    }

    fun addSuggestion(suggestion: PlaceSuggestion) {
        val dayId = _state.value.dayId ?: return
        Diag.event("suunnittelu", "stoppi lisätään ehdotuksesta (haku '${_state.value.searchText.trim()}')")
        viewModelScope.launch {
            // Yrityksen nimi tulee Overturesta kuten muiden lähteiden nimet.
            runCatching { routeRepository.addStop(dayId, placeSearch.placeDetails(suggestion.placeId)) }
                .onSuccess(::notifyIfDuplicate)
                .onFailure { error -> reportError(error) }
            clearSearch()
        }
    }

    /** Lisää stopin osoitemuistista — koordinaatit ovat tallessa, ei verkkokutsua. */
    fun addSavedAddress(saved: SavedAddressEntity) {
        Diag.event("suunnittelu", "stoppi lisätään osoitemuistista ${saved.label}")
        val dayId = _state.value.dayId ?: return
        viewModelScope.launch {
            val result = routeRepository.addStop(
                dayId,
                PlaceDetails(
                    placeId = saved.placeId.orEmpty(),
                    name = saved.label,
                    address = saved.address,
                    location = GeoPoint(saved.lat, saved.lng),
                ),
            )
            notifyIfDuplicate(result)
            clearSearch()
        }
    }

    /**
     * Kohdelistalta: kohde huoltostoppina tämän sivun päivän listan loppuun kuten osoitehaun valinta (ei kartan
     * `poiStopDayId`-sääntöä); Suunnittelu-sivulle "Lisätty stopiksi" tai tuplailmoitus.
     */
    fun addPoi(item: PoiItem) {
        val dayId = _state.value.dayId ?: return
        viewModelScope.launch {
            runCatching {
                val place = places.place(item, application.getString(PoiCardText.categoryNameRes(item.category)))
                routeRepository.addServiceStop(dayId, place, PoiServices.defaultServiceSec(item.category), ServiceStopPlacement.END)
            }.onSuccess { result ->
                Diag.event("kohdelista", "lisäys ${item.id} ${item.category.apiName} ${result::class.simpleName}")
                when (result) {
                    is AddStopResult.Added -> showNotice(application.getString(R.string.poi_added, result.stop.label))
                    is AddStopResult.Duplicate -> showNotice(application.getString(R.string.stop_duplicate, result.existing.label))
                }
            }.onFailure { error ->
                Diag.event("kohdelista", "lisäysvirhe ${error.javaClass.simpleName}")
                reportError(error)
            }
        }
    }

    /** Tiedostovalitsin päästi läpi taulukon, jota tuonti ei osaa lukea (.xls, .ods, rikkinäinen tiedosto). */
    fun reportUnsupportedImport() = showNotice(application.getString(R.string.import_unsupported))

    /** Lyhytaikainen ilmoitus: häviää 5 sekunnissa tai seuraavasta lisäyksestä. */
    private fun showNotice(message: String) {
        Diag.event("suunnittelu", "ilmoitus $message")
        noticeJob?.cancel()
        _state.value = _state.value.copy(error = message)
        noticeJob = viewModelScope.launch {
            delay(5_000)
            _state.value = _state.value.copy(error = null)
        }
    }

    /** Sama osoite on jo listalla — kerro käyttäjälle, ettei tuplaa syntynyt. */
    private fun notifyIfDuplicate(result: AddStopResult) {
        noticeJob?.cancel()
        if (result is AddStopResult.Duplicate) {
            showNotice(application.getString(R.string.stop_duplicate, result.existing.label))
        } else {
            _state.value = _state.value.copy(error = null)
        }
    }

    private suspend fun clearSearch() {
        _state.value = _state.value.copy(searchText = "", suggestions = emptyList())
        refreshSavedSuggestions("")
    }

    private suspend fun refreshSavedSuggestions(query: String) {
        runCatching { routeRepository.searchSavedAddresses(query) }
            .onSuccess { _state.value = _state.value.copy(savedSuggestions = it) }
    }

    /** Geokoodaa liitetyn osoitelistan (yksi osoite per rivi) ja lisää stopit. */
    fun addPastedList(text: String, onDone: () -> Unit) {
        val dayId = _state.value.dayId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(geocodingList = true)
            var added = 0
            var duplicates = 0
            var failed = 0
            // Haku vain Suomesta (Digitransit, varalla Suomeen rajattu haku). Rivit siivotaan kuten
            // tiedostosta tuotaessa (numerointi, otsikkorivi, tyhjät rivit).
            val here = lastLocation(application)
            ImportedText.fromText(text).forEach { line ->
                runCatching { placeSearch.geocodeText(line, here) }
                    .getOrNull()
                    ?.let {
                        when (routeRepository.addStop(dayId, it)) {
                            is AddStopResult.Added -> added++
                            is AddStopResult.Duplicate -> duplicates++
                        }
                    } ?: failed++
            }
            _state.value = _state.value.copy(geocodingList = false)
            Diag.event("suunnittelu", "liitetty lista: lisätty $added, tuplia $duplicates, epäonnistui $failed")
            // Tulos näkyviin: epäonnistunut geokoodaus jäisi muuten huomaamatta.
            showNotice(application.getString(R.string.paste_result, added, duplicates, failed))
            onDone()
        }
    }

    fun removeStop(stopId: Long) {
        Diag.event("suunnittelu", "stoppi poistetaan $stopId")
        // Stoppitarkkailija hoitaa loput: ajon aikana navigointi ja numerointi
        // päivittyvät heti, myös jos poistetaan stoppi jota kohti ajetaan.
        viewModelScope.launch {
            routeRepository.removeStop(stopId)
        }
    }

    fun renameStop(stopId: Long, label: String) {
        Diag.event("suunnittelu", "stoppi $stopId nimetään '$label'")
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { routeRepository.renameStop(stopId, trimmed) }
    }

    /** Vaihda stopin osoite hakutulokseen. [customLabel] null = paikan nimi käyttöön. */
    fun changeStopAddress(stop: StopEntity, suggestion: PlaceSuggestion, customLabel: String?) {
        Diag.event("suunnittelu", "stopin ${stop.id} osoite vaihdetaan ehdotuksesta")
        viewModelScope.launch {
            // Yksi virheraja koko polulle: myös itse muutoksen poikkeukset
            // (ei vain verkkohaun) päätyvät snackbariin eivätkä kaada sovellusta.
            runCatching { applyAddressChange(stop, placeSearch.placeDetails(suggestion.placeId), customLabel) }
                .onFailure { error -> reportError(error) }
            clearSearch()
        }
    }

    /** Vaihda stopin osoite osoitemuistin osumaan — koordinaatit tallessa, ei verkkokutsua. */
    fun changeStopAddressSaved(stop: StopEntity, saved: SavedAddressEntity, customLabel: String?) {
        Diag.event("suunnittelu", "stopin ${stop.id} osoite vaihdetaan osoitemuistista ${saved.label}")
        viewModelScope.launch {
            runCatching {
                applyAddressChange(
                    stop,
                    PlaceDetails(
                        placeId = saved.placeId.orEmpty(),
                        name = saved.label,
                        address = saved.address,
                        location = GeoPoint(saved.lat, saved.lng),
                    ),
                    customLabel,
                )
            }.onFailure(::reportError)
            clearSearch()
        }
    }

    private suspend fun applyAddressChange(stop: StopEntity, details: PlaceDetails, customLabel: String?) {
        // Kohdetta, jota Navigator parhaillaan opastaa, ei vaihdeta lennossa:
        // määränpää on jo asetettu (ja laskutettu) vanhoille koordinaateille.
        if (sessionManager.isDriving && sessionManager.state.value.currentStop?.id == stop.id) {
            showNotice(application.getString(R.string.edit_active_stop_blocked))
            return
        }
        // Aiemmin annettu oma nimi säilyy osoitteenvaihdossa: automaattinen
        // nimi tunnistetaan siitä, että osoite alkaa sillä ("Tuoksuherneentie
        // 18" ⊂ "Tuoksuherneentie 18, 06150 Porvoo"). Vain automaattinen nimi
        // korvataan uuden paikan nimellä.
        val hadAutoLabel = stop.address.trim().lowercase()
            .startsWith(stop.label.trim().lowercase())
        val label = customLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (hadAutoLabel) details.name.ifBlank { details.address } else stop.label
        // null = stoppi ehti kadota (poisto/tyhjennys kesken verkkohaun) —
        // vanhentunut muokkaus ohitetaan hiljaa.
        val result = routeRepository.changeStopAddress(stop.id, details, label) ?: return
        notifyIfDuplicate(result)
        if (result is AddStopResult.Added) {
            // Valittu osoite tulee osoite- tai yrityshausta — kerro Suunnittelu-sivulla
            // mihin osoitteeseen stoppi vahvistui.
            showNotice(application.getString(R.string.address_verified, details.address))
        }
    }

    /** Tyhjennä päivän koko stoppilista kerralla. Ajon aikana estetty. */
    fun clearAllStops() {
        Diag.event("suunnittelu", "tyhjennä lista")
        val dayId = _state.value.dayId ?: return
        if (sessionManager.isDriving) {
            _state.value = _state.value.copy(
                error = application.getString(R.string.clear_stops_blocked)
            )
            return
        }
        viewModelScope.launch {
            routeRepository.clearStops(dayId)
        }
    }

    fun setDeadline(stopId: Long, minutesOfDay: Int?) {
        Diag.event("suunnittelu", "stopin $stopId aikaraja $minutesOfDay")
        viewModelScope.launch {
            routeRepository.setDeadline(stopId, minutesOfDay)
        }
    }

    fun setServiceMinutes(stopId: Long, minutes: Int?) {
        Diag.event("suunnittelu", "stopin $stopId kesto $minutes min")
        viewModelScope.launch {
            routeRepository.setServiceSec(stopId, minutes?.let { it * 60L })
        }
    }

    /** Aikatauluennusteen viimeisin virheteksti; onnistunut laskenta poistaa sen näkyvistä. */
    private var scheduleError: String? = null

    private fun reportError(e: Throwable) {
        // Debouncen peruma haku ei ole virhe — ilman tätä snackbariin vuotaa
        // "StandaloneCoroutine was cancelled" aina kun käyttäjä kirjoittaa.
        if (e is CancellationException) throw e
        // Tuplailmoituksen ajastin ei saa pyyhkiä oikeaa virhettä alta.
        noticeJob?.cancel()
        Diag.event("suunnittelu", "virhe ${errorText(e)} (${e.javaClass.simpleName}: ${e.message})")
        _state.value = _state.value.copy(error = errorText(e))
    }

    /** Verkkovirheen raaka viesti ("Failed to connect to …") ei kelpaa kuljettajalle (laitetesti 20.10.). */
    private fun errorText(e: Throwable): String =
        application.getString(UserErrors.messageRes(e))

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
