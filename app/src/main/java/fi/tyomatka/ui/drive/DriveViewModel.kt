package fi.tyomatka.ui.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.AppSettingsStore
import fi.tyomatka.data.repo.RouteChoiceStore
import fi.tyomatka.diag.Diag
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.CarRepository
import fi.tyomatka.data.repo.FuelRepository
import fi.tyomatka.data.repo.GapSuggestion
import fi.tyomatka.data.repo.NextCarStore
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.data.repo.StartPrefill
import fi.tyomatka.data.repo.WorkLogRepository
import fi.tyomatka.data.repo.knownPlates
import fi.tyomatka.data.repo.latestPlateReadings
import fi.tyomatka.data.repo.startPrefill
import fi.tyomatka.data.repo.suggestionPlates
import com.stadiamaps.ferrostar.core.NavigationViewModel
import fi.tyomatka.drive.DriveSessionManager
import fi.tyomatka.drive.DriveState
import fi.tyomatka.nav.NavTarget
import fi.tyomatka.nav.ferrostar.FerrostarNavigationEngine
import fi.tyomatka.nav.ferrostar.LowVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val sessionManager: DriveSessionManager,
    private val routeRepository: RouteRepository,
    private val workLogRepository: WorkLogRepository,
    fuelRepository: FuelRepository,
    carRepository: CarRepository,
    nextCarStore: NextCarStore,
    private val engine: FerrostarNavigationEngine,
    private val routeChoiceStore: RouteChoiceStore,
    appSettings: AppSettingsStore,
) : ViewModel() {

    val hideBottomBar: StateFlow<Boolean> = appSettings.settings.map { it.hideBottomBarDriving }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val driveState: StateFlow<DriveState> = sessionManager.state

    /**
     * Seuraavan ajon reittivalinta (valitaan Kartta-sivulla, lyhin vain valintapäivän); aloitettu ajo käyttää valintaa,
     * joka oli voimassa aloitettaessa.
     */
    val routeChoice: StateFlow<RouteChoice> =
        routeChoiceStore.choice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteChoice.NORMAL)

    /** Ajokartan näkymätila Ferrostarilta (sama singleton kuin ajosession moottori). */
    val navigationViewModel: NavigationViewModel get() = engine

    /** Kohde, jonka nasta piirretään ajokartalle. */
    val destination: StateFlow<NavTarget?> get() = engine.destination

    /** Kielitunniste, jolle puhelimesta puuttuu puheääni. */
    val missingVoice: StateFlow<String?> get() = engine.missingVoice

    /** Media-ääni on ajon alussa hiljainen. */
    val lowVolume: StateFlow<LowVolume?> get() = engine.lowVolume

    fun raiseVolume() = engine.raiseVolume()

    private val _todayDayId = MutableStateFlow<Long?>(null)
    val todayDayId: StateFlow<Long?> = _todayDayId.asStateFlow()

    private val _todayStopCount = MutableStateFlow(0)
    val todayStopCount: StateFlow<Int> = _todayStopCount.asStateFlow()

    /** Hoitamattomien stoppien määrä — FINISHED-ruutu väistyy kun tälle
     *  päivälle on taas ajettavaa (uusi käynti tai uusi vuorokausi). */
    private val _todayRemainingCount = MutableStateFlow(0)
    val todayRemainingCount: StateFlow<Int> = _todayRemainingCount.asStateFlow()

    /** Tämän päivän työsegmentit — ajonaloituksen kirjaustarkistus. */
    private val _todayWorkSegments = MutableStateFlow<List<WorkSegmentEntity>>(emptyList())
    val todayWorkSegments: StateFlow<List<WorkSegmentEntity>> = _todayWorkSegments.asStateFlow()

    private val plateSources = combine(workLogRepository.observeAll(), fuelRepository.observeAll(), carRepository.observeAll()) {
            segments, fillups, cars -> Triple(segments, fillups, cars)
        }

    /** Sama vyöhyke esitäytölle ja lukemille autoittain, jotta päivän raja menee molemmissa samoin. */
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Aloitusdialogin esitäyttö samalla säännöllä kuin Työmatka-sivulla ([startPrefill]): Autot-näkymän valinta tai
     * johdettu oletusauto ja saman auton viimeisin lukema.
     */
    val startPrefill: StateFlow<StartPrefill> =
        combine(plateSources, nextCarStore.choice, _todayWorkSegments) { (segments, fillups, cars), choice, today ->
            startPrefill(choice, segments, fillups, cars, today.firstOrNull { it.endEpochSec == null }, zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartPrefill(plate = null, km = null))

    /** Rekisterinumeronapit: käytetyt ja pelkät profiilit kuten Työmatka-sivulla ([suggestionPlates]). */
    val knownPlates: StateFlow<List<String>> = plateSources
        .map { (segments, fillups, cars) -> suggestionPlates(knownPlates(segments, fillups), cars) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Rekisterinumero → auton viimeisin lukema ([latestPlateReadings]): dialogin lukema seuraa auton vaihtoa. */
    val latestReadings: StateFlow<Map<String, Long>> = plateSources
        .map { (segments, fillups, _) -> latestPlateReadings(segments, fillups, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _gapSuggestion = MutableStateFlow<GapSuggestion?>(null)
    val gapSuggestion: StateFlow<GapSuggestion?> = _gapSuggestion.asStateFlow()

    private var todayEpochDay: Long? = null

    init {
        viewModelScope.launch {
            // Vuorokauden vaihtuessa siirrytään uuteen päivään lennossa.
            routeRepository.observeTodayDay().collectLatest { day ->
                _todayDayId.value = day.id
                todayEpochDay = day.dateEpochDay
                _todayStopCount.value = 0
                _todayRemainingCount.value = 0
                routeRepository.observeStops(day.id).collect { stops ->
                    _todayStopCount.value = stops.size
                    _todayRemainingCount.value = stops.count {
                        it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED
                    }
                }
            }
        }
        viewModelScope.launch {
            routeRepository.observeTodayDay().collectLatest { day ->
                _todayWorkSegments.value = emptyList()
                workLogRepository.observeForDay(day.id).collect {
                    _todayWorkSegments.value = it
                }
            }
        }
    }

    /** Työpäivän aloitus ajonaloituksen yhteydessä (+ välintunnistus). */
    fun startWork(odometerKm: Long?, licensePlate: String?) {
        Diag.event("ajo", "aloita työt ajosta km=$odometerKm auto=$licensePlate")
        val dayId = _todayDayId.value ?: return
        viewModelScope.launch {
            val today = todayEpochDay
            val suggestion = if (odometerKm != null && today != null) {
                workLogRepository.gapSuggestion(odometerKm, today, licensePlate)
            } else {
                null
            }
            workLogRepository.startSegment(
                dayId, odometerKm, licensePlate, System.currentTimeMillis() / 1000
            )
            _gapSuggestion.value = suggestion
        }
    }

    fun confirmGap(perDayKm: Map<Long, Long>) {
        Diag.event("ajo", "välin kilometrit vahvistetaan ${perDayKm.size} päivälle")
        val suggestion = _gapSuggestion.value ?: return
        _gapSuggestion.value = null
        viewModelScope.launch {
            workLogRepository.recordGap(perDayKm, suggestion.previousKm, suggestion.licensePlate)
        }
    }

    fun dismissGap() {
        _gapSuggestion.value = null
    }

    fun markArrived() = sessionManager.markArrived()

    fun departCurrentStop() = sessionManager.departCurrentStop()

    fun skipCurrentStop() = sessionManager.skipCurrentStop()

    fun removeStop(stopId: Long) = sessionManager.removeStop(stopId)

    fun stopDriving() = sessionManager.stopDriving()
}
