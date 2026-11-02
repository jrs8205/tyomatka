package fi.tyomatka.ui.history

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.tyomatka.R
import fi.tyomatka.diag.Diag
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.photo.FillupPhotoRepository
import fi.tyomatka.data.photo.PhotoProcessor
import fi.tyomatka.data.photo.PhotoStore
import fi.tyomatka.data.repo.CarRepository
import fi.tyomatka.data.repo.RouteTemplateRepository
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.normalizePlate
import fi.tyomatka.data.repo.FuelInterval
import fi.tyomatka.data.repo.FuelRepository
import fi.tyomatka.data.repo.GapSuggestion
import fi.tyomatka.data.repo.NextCarChoice
import fi.tyomatka.data.repo.NextCarStore
import fi.tyomatka.data.repo.ReportRepository
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.data.repo.WorkLogRepository
import fi.tyomatka.data.repo.WorkHistoryDay
import fi.tyomatka.data.repo.workHistoryDays
import fi.tyomatka.data.repo.latestPlateReading
import fi.tyomatka.data.repo.latestPlateReadings
import fi.tyomatka.data.repo.latestWorkSegment
import fi.tyomatka.data.repo.startPrefill
import fi.tyomatka.data.repo.fillupEpochSec
import fi.tyomatka.data.repo.fuelIntervals
import fi.tyomatka.data.repo.knownPlates
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.reportLocale
import fi.tyomatka.ui.SharedPhotos
import fi.tyomatka.ui.UiEvents
import fi.tyomatka.ui.UserErrors
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.history.forms.PhotoActions
import fi.tyomatka.ui.history.forms.PhotoEdit
import fi.tyomatka.ui.history.export.exportDoneMessage
import fi.tyomatka.ui.history.forms.fillWord
import fi.tyomatka.work.WorkReminderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** Tankkausdialogin tulos (uusi tai muokattu). */
data class FuelEntry(
    val id: Long?,
    val epochDay: Long,
    val plate: String,
    val odometerKm: Long,
    val liters: Double,
    val totalCost: Double,
    val pricePerLiter: Double,
    val kind: FuelKind,
    val fullTank: Boolean,
    val currency: String,
    /** Kirjauksen lisätiedot normalisoituna. */
    val note: String = "",
)

data class WorkUiState(
    val period: WorkPeriod = WorkPeriod.DAY,
    val rows: List<WorkPeriodRow> = emptyList(),
    /** Viennin sallinta ([canExport]): päiviä tai autoprofiileja. */
    val hasAny: Boolean = false,
    /** Kuluvan päivän segmentit Tänään-korttia ja sen dialogeja varten. */
    val todaySegments: List<WorkSegmentEntity> = emptyList(),
    /** Tämän päivän lisätiedot (aloitus- ja lopetuslomakkeen esitäyttö). */
    val todayNote: String = "",
    /** Segmentit päivittäin laajennettua päiväkorttia ja sen korjausta varten. */
    val segmentsByDate: Map<Long, List<WorkSegmentEntity>> = emptyMap(),
    /** Päivien lisätiedot päivämäärän mukaan (vain ei-tyhjät): korjauslomakkeen esitäyttö. */
    val notesByDate: Map<Long, String> = emptyMap(),
    /** Aloitusdialogin esitäytön lukema ([startPrefill]): oletusauton oma viimeisin lukema. */
    val prefillKm: Long? = null,
    /**
     * Oletusauto ([startPrefill]: Autot-näkymän valinta tai johdettu oletus): aloitusdialogin esitäyttö,
     * Tänään-kortin nappi ja rekisterinumero, tankkauslomake ja Autot-näkymän "Seuraava ajo".
     */
    val defaultPlate: String? = null,
    /** Tunnetut rekkarit ehdotusnapeille, viimeisin käyttö ensin. */
    val knownPlates: List<String> = emptyList(),
    /** Rekisterinumero → auton viimeisin lukema ([latestPlateReadings]): lomakkeen lukema seuraa auton vaihtoa. */
    val latestReadings: Map<String, Long> = emptyMap(),
    /** Tankkaukset uusin ensin (dialogin lukemarajat). */
    val fillups: List<FuelFillupEntity> = emptyList(),
    /** Kirjaukset on luettu kannasta (ensimmäinen päästö): muokattavan kirjauksen puuttuminen on silloin lopullista. */
    val fillupsLoaded: Boolean = false,
    /** Tankkauksen id → sen päättämä väli ("kulutus edellisestä"). */
    val intervalsByFillup: Map<Long, FuelInterval> = emptyMap(),
    /** Havaittu kirjaamaton väli, odottaa käyttäjän vahvistusta. */
    val gapSuggestion: GapSuggestion? = null,
    /** Muistutusilmoituksen "Kirjaa nyt" pyytää aloitusdialogin auki. */
    val openStartDialog: Boolean = false,
    val message: String? = null,
    /** Autoprofiilit rekisterinumeron mukaan (tankkauslomakkeen lajin oletus). */
    val cars: Map<String, CarEntity> = emptyMap(),
    /** Kirjausten kuvat lisäysjärjestyksessä kirjauksen id:n mukaan. */
    val photosByFillup: Map<Long, List<FuelPhotoEntity>> = emptyMap(),
    /** Tankkauslomakkeen tallennuksen vaihe; null, kun tallennus ei ole kesken eikä kuittaamatta. */
    val fillupSave: FillupSave? = null,
)

/**
 * Työmatka-sivu: päivä-, viikko-, kuukausi- ja vuosiyhteenvedot kilometreistä
 * ja ajoajasta sekä HTML-raportin vienti ja tuonti.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val application: Application,
    private val workLogRepository: WorkLogRepository,
    private val fuelRepository: FuelRepository,
    private val routeRepository: RouteRepository,
    private val workReminderManager: WorkReminderManager,
    private val uiEvents: UiEvents,
    private val carRepository: CarRepository,
    private val routeTemplateRepository: RouteTemplateRepository,
    private val photoRepository: FillupPhotoRepository,
    private val photoStore: PhotoStore,
    private val photoProcessor: PhotoProcessor,
    private val reportRepository: ReportRepository,
    private val sharedPhotoInbox: SharedPhotos,
    private val nextCarStore: NextCarStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkUiState())
    val state: StateFlow<WorkUiState> = _state.asStateFlow()

    /** Lomakkeen kuvatoiminnot: pakkaus odottavaksi, kameran tiedosto FileProviderin kautta ja hylkäys. */
    val photoActions: PhotoActions = object : PhotoActions {
        override fun storedFile(fileName: String): File = photoStore.file(fileName)
        override suspend fun prepare(source: Uri): File = photoProcessor.prepare(source)
        override fun newCapture(): Pair<File, Uri> {
            val file = photoStore.newCapture()
            return file to FileProvider.getUriForFile(application, "${application.packageName}.kamera", file)
        }
        override fun discard(files: List<File>) = files.forEach(photoStore::discard)
    }

    /** Sovellukseen jaetut kuvat odottavat kirjauksen lomaketta. */
    val sharedPhotos: StateFlow<List<File>> = sharedPhotoInbox.files

    /** Lomake otti jaetut kuvat. */
    fun takeSharedPhotos(): List<File> = sharedPhotoInbox.consume()

    /** Päivät tuoreimmasta vanhimpaan — myös tankkauspäivät ilman työkirjausta. */
    private var days: List<WorkHistoryDay> = emptyList()
    /** Tallennettujen reittien määrä viennin sallintaan: pohjat kulkevat koko varmuuskopiossa. */
    private var templateCount = 0

    /** Kaikki tankkaukset rivien laskentaan (auton polttoaine); tila päivittyy vasta rivien jälkeen. */
    private var allFillups: List<FuelFillupEntity> = emptyList()

    /** Kaikki työjaksot oletusautoa varten, kun autoprofiilit muuttuvat. */
    private var allSegments: List<WorkSegmentEntity> = emptyList()

    /** Autot-näkymän valinta seuraavaan ajoon; oletusauto lasketaan uudelleen, kun se tai työjaksot muuttuvat. */
    private var nextCar: NextCarChoice? = null

    private var intervalsByFillup: Map<Long, FuelInterval> = emptyMap()

    private val zone: ZoneId = ZoneId.systemDefault()

    private var todayDayId: Long? = null
    private var todayEpochDay: Long? = null

    private data class Snapshot(
        val days: List<WorkHistoryDay>,
        val segmentsByDate: Map<Long, List<WorkSegmentEntity>>,
        val segments: List<WorkSegmentEntity>,
        val knownPlates: List<String>,
        val latestReadings: Map<String, Long>,
        val fillups: List<FuelFillupEntity>,
        val intervalsByFillup: Map<Long, FuelInterval>,
    )

    init {
        viewModelScope.launch {
            routeTemplateRepository.observeTemplates().collect { templates ->
                templateCount = templates.size
                _state.value = _state.value.copy(hasAny = canExport(days, _state.value.cars.values, templateCount))
            }
        }
        viewModelScope.launch {
            carRepository.observeAll().collect { cars ->
                _state.value = _state.value.copy(
                    cars = cars.associateBy { it.plate },
                    hasAny = canExport(days, cars, templateCount),
                ).withPrefill()
                // Auton polttoaine jakaa usean polttoaineen jakson kilometrit lajeittain.
                _state.value = _state.value.copy(rows = buildRows(_state.value.period))
            }
        }
        viewModelScope.launch {
            // Vuorokauden vaihtuessa Tänään-kortti siirtyy uuteen päivään lennossa
            // ja työt voi aloittaa alusta, vaikka sovellus olisi ollut auki yön yli.
            routeRepository.observeTodayDay().collectLatest { day ->
                todayDayId = day.id
                todayEpochDay = day.dateEpochDay
                // Tämän päivän avoin jakso ratkaisee, pitääkö valinta, joten oletus seuraa päivää.
                _state.value = _state.value.copy(todaySegments = emptyList(), todayNote = day.note).withPrefill()
                workLogRepository.observeForDay(day.id).collect { segments ->
                    _state.value = _state.value.copy(todaySegments = segments).withPrefill()
                }
            }
        }
        viewModelScope.launch {
            combine(
                workLogRepository.observeDays(),
                workLogRepository.observeAll(),
                fuelRepository.observeAll(),
            ) { days, segments, fillups ->
                val historyDays = workHistoryDays(days, segments, fillups, zone)
                val byDate = historyDays.filter { it.segments.isNotEmpty() }.associate { it.date to it.segments }
                Snapshot(
                    days = historyDays,
                    segmentsByDate = byDate,
                    segments = segments,
                    knownPlates = knownPlates(segments, fillups),
                    latestReadings = latestPlateReadings(segments, fillups, zone),
                    fillups = fillups,
                    intervalsByFillup = fuelIntervals(fillups).associateBy { it.fillupId },
                )
            }.collect { snapshot ->
                days = snapshot.days
                allSegments = snapshot.segments
                allFillups = snapshot.fillups
                intervalsByFillup = snapshot.intervalsByFillup
                _state.value = _state.value.copy(
                    hasAny = canExport(snapshot.days, _state.value.cars.values, templateCount),
                    rows = buildRows(_state.value.period),
                    segmentsByDate = snapshot.segmentsByDate,
                    notesByDate = snapshot.days.filter { it.note.isNotBlank() }.associate { it.date to it.note },
                    // Päivän teksti seuraa kantaa: Aloita työt -lomakkeen teksti näkyy Lopeta työt -lomakkeessa.
                    todayNote = todayNoteFrom(snapshot.days, todayEpochDay, _state.value.todayNote),
                    knownPlates = snapshot.knownPlates,
                    latestReadings = snapshot.latestReadings,
                    fillups = snapshot.fillups,
                    fillupsLoaded = true,
                    intervalsByFillup = snapshot.intervalsByFillup,
                ).withPrefill()
            }
        }
        viewModelScope.launch {
            photoRepository.observeAll().collect { photos ->
                _state.value = _state.value.copy(photosByFillup = photos.groupBy { it.fillupId })
            }
        }
        viewModelScope.launch {
            nextCarStore.choice.collect { choice ->
                nextCar = choice
                _state.value = _state.value.withPrefill()
            }
        }
        viewModelScope.launch {
            // Jaettu kuva, jota ei voitu lukea: kuljettaja saa tietää, ettei kuvaa tullut lomakkeelle.
            sharedPhotoInbox.failed.collect { failed ->
                if (failed) {
                    sharedPhotoInbox.consumeFailure()
                    _state.value = _state.value.copy(message = application.getString(R.string.photo_shared_failed))
                }
            }
        }
        viewModelScope.launch {
            uiEvents.openWorkStart.collect { requested ->
                _state.value = _state.value.copy(openStartDialog = requested)
            }
        }
    }

    /** Ruutu avasi aloitusdialogin muistutuksen pyynnöstä. */
    fun consumeOpenStartRequest() {
        uiEvents.consumeOpenWorkStart()
        // "Kirjaa nyt" -toimintonappi ei sulje ilmoitusta itsestään.
        workReminderManager.cancelReminder()
    }

    fun setPeriod(period: WorkPeriod) {
        _state.value = _state.value.copy(period = period, rows = buildRows(period))
    }

    private fun buildRows(period: WorkPeriod): List<WorkPeriodRow> =
        workPeriodRows(days, period, intervalsByFillup, fuelOf = { carFuel(it, _state.value.cars, allFillups) }) { week, year ->
            application.getString(R.string.week_label, week, year)
        }

    /**
     * Oletusauto ja aloituksen lukema ([startPrefill]) tilan autoista, tämän päivän avoimesta jaksosta ja valinnasta;
     * lasketaan uudelleen, kun jokin niistä tai työjaksot muuttuvat.
     */
    private fun WorkUiState.withPrefill(): WorkUiState {
        val openToday = todaySegments.firstOrNull { it.endEpochSec == null }
        val prefill = startPrefill(nextCar, allSegments, allFillups, cars.values.toList(), openToday, zone)
        return copy(defaultPlate = prefill.plate, prefillKm = prefill.km)
    }

    /** Auton viimeisin tunnettu lukema Vaihda auto -lomakkeen esitäyttöön, kun uusi auto valittiin Autot-näkymästä. */
    fun latestReadingKm(plate: String): Long? = latestPlateReading(plate, allSegments, allFillups, zone)

    // ---- Työajan ja kilometrien kirjaus (Tänään-kortti) ----

    /** [fuel]: vapaaehtoinen polttoaine autolle, jonka polttoainetta ei tiedetä (Autot-näkymän suora tieto). */
    fun startWork(odometerKm: Long?, licensePlate: String?, fuel: FuelKind? = null, note: String = "") {
        Diag.event("historia", "aloita työt km=$odometerKm auto=$licensePlate")
        val dayId = todayDayId ?: return
        viewModelScope.launch {
            // Välintunnistus ENNEN tallennusta — muuten uusi segmentti olisi
            // itse "viimeisin lukema" eikä väliä havaittaisi.
            val suggestion = detectGap(odometerKm, licensePlate)
            workLogRepository.startSegment(dayId, odometerKm, licensePlate, nowSec())
            workLogRepository.setDayNote(dayId, note)
            saveFuel(licensePlate, fuel)
            if (suggestion != null) {
                _state.value = _state.value.copy(gapSuggestion = suggestion)
            }
        }
    }

    fun switchCar(endOdometerKm: Long?, startOdometerKm: Long?, newLicensePlate: String?, fuel: FuelKind? = null) {
        Diag.event("historia", "vaihda auto km=$endOdometerKm/$startOdometerKm auto=$newLicensePlate")
        val dayId = todayDayId ?: return
        viewModelScope.launch {
            workLogRepository.switchCar(dayId, endOdometerKm, startOdometerKm, newLicensePlate, nowSec())
            saveFuel(newLicensePlate, fuel)
        }
    }

    private suspend fun saveFuel(plate: String?, fuel: FuelKind?) {
        if (fuel == null) return
        plate?.let(::normalizePlate)?.let { carRepository.setFuel(it, fuel) }
    }

    /** Autot-näkymän lomake: polttoaine suoraan käyttäjältä. */
    fun saveCar(plate: String, fuel: FuelKind?) {
        Diag.event("historia", "auto tallennetaan $plate $fuel")
        viewModelScope.launch { carRepository.setFuel(plate, fuel) }
    }

    /**
     * Autot-näkymän Valitse, kun työpäivä ei ole käynnissä: auto seuraavaan ajoon. Valinta säilyy
     * pysyvästi, ja ilmoitus näkyy Autot-näkymässä, joka on auki; myös tallennuksen epäonnistuminen kerrotaan siinä.
     */
    fun chooseNextCar(plate: String) {
        viewModelScope.launch {
            val message = try {
                nextCarStore.choose(plate, nowSec(), latestWorkSegment(allSegments)?.id)
                application.getString(R.string.car_chosen_next, plate)
            } catch (e: IOException) {
                application.getString(R.string.car_choose_failed)
            }
            _state.value = _state.value.copy(message = message)
        }
    }

    /**
     * Autot-näkymän poisto autolle, jolla ei ole työjaksoja eikä kirjauksia. Jos autolle ehti tulla kirjauksia
     * (varmuuskopion tuonti lomakkeen ollessa auki), profiili säilyy ja käyttäjä saa ilmoituksen. Poistetun auton
     * valinta tyhjennetään, ettei se palaa, jos sama rekisterinumero lisätään uudelleen.
     */
    fun deleteCar(plate: String) {
        Diag.event("historia", "auto poistetaan $plate")
        viewModelScope.launch {
            if (!carRepository.delete(plate)) {
                _state.value = _state.value.copy(message = application.getString(R.string.car_not_deleted, plate))
                return@launch
            }
            if (nextCar?.plate == plate) {
                try {
                    nextCarStore.clear()
                } catch (e: IOException) {
                    // Poisto onnistui, ja poistetun auton valinta ohitetaan joka tapauksessa; viesti vain hämmentäisi.
                }
            }
        }
    }

    fun endWork(odometerKm: Long?, note: String = "") {
        Diag.event("historia", "lopeta työt km=$odometerKm")
        val dayId = todayDayId ?: return
        viewModelScope.launch {
            val suggestion = detectGap(odometerKm, _state.value.todaySegments.lastOrNull()?.licensePlate)
            workLogRepository.endSegment(dayId, odometerKm, nowSec())
            workLogRepository.setDayNote(dayId, note)
            if (suggestion != null) {
                _state.value = _state.value.copy(gapSuggestion = suggestion)
            }
        }
    }

    /** Kirjaamattoman välin tunnistus uudelle lukemalle (null jos ei väliä). */
    private suspend fun detectGap(odometerKm: Long?, licensePlate: String?): GapSuggestion? {
        val km = odometerKm ?: return null
        val today = todayEpochDay ?: return null
        return workLogRepository.gapSuggestion(km, today, licensePlate)
    }

    /** Käyttäjä vahvisti takautuvan kirjauksen (mahdollisesti muokatuin määrin). */
    fun confirmGap(perDayKm: Map<Long, Long>) {
        Diag.event("historia", "välin kilometrit vahvistetaan ${perDayKm.size} päivälle")
        val suggestion = _state.value.gapSuggestion ?: return
        _state.value = _state.value.copy(gapSuggestion = null)
        viewModelScope.launch {
            val added = workLogRepository.recordGap(perDayKm, suggestion.previousKm, suggestion.licensePlate)
            _state.value = _state.value.copy(
                message = application.getString(R.string.work_gap_recorded, added)
            )
        }
    }

    fun dismissGap() {
        _state.value = _state.value.copy(gapSuggestion = null)
    }

    fun updateSegment(
        segmentId: Long,
        startKm: Long?,
        endKm: Long?,
        startEpochSec: Long,
        endEpochSec: Long?,
        licensePlate: String?,
    ) {
        Diag.event("historia", "työjakso $segmentId korjataan km $startKm-$endKm")
        viewModelScope.launch {
            workLogRepository.updateSegment(
                segmentId, startKm, endKm, startEpochSec, endEpochSec, licensePlate
            )
        }
    }

    /** Korjauslomakkeen päivän lisätiedot; lukemat tallennetaan erikseen [updateSegment]-kutsuilla. */
    fun saveDayNote(epochDay: Long, note: String) {
        Diag.event("historia", "päivän $epochDay lisätiedot (${note.length} merkkiä)")
        viewModelScope.launch { workLogRepository.setDayNoteByDate(epochDay, note) }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // ---- Tankkaukset ----

    /**
     * Kirjaus ja sen kuvamuutokset: kuvat siirtyvät kirjaukselle vasta tallennettaessa. Vaihe kulkee tilassa
     * ([WorkUiState.fillupSave]): lomake sulkeutuu vasta [FillupSave.Done]-vaiheessa, ja jos kirjaus ei tallentunut,
     * lomake jää auki syötteineen. Poikkeus ei pääse korutiinista kaatamaan sovellusta.
     */
    fun saveFillup(entry: FuelEntry, photos: PhotoEdit) {
        Diag.event("historia", "tankkaus tallennetaan id=${entry.id} ${entry.kind} ${entry.liters} km ${entry.odometerKm} kuvia +${photos.added.size} -${photos.removedIds.size}")
        // Myös Done-vaihe hylkää: lomake on vielä auki kehyksen ajan, ja toinen tallennus tekisi kirjauksesta tuplan.
        if (_state.value.fillupSave.busy) return
        _state.value = _state.value.copy(fillupSave = FillupSave.Saving)
        viewModelScope.launch {
            val id = try {
                saveFillupEntry(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diag.event("historia", "tankkauksen tallennus epäonnistui ${e.javaClass.simpleName}: ${e.message}")
                _state.value = _state.value.copy(fillupSave = fillupSaveFailed(entry.kind))
                return@launch
            }
            val message = try {
                // Poistettujen uusien kuvien tiedostot odottivat kumoamista; onnistunut tallennus päättää sen. Hylkäys
                // tutkii tiedoston polun (voi heittää), joten se on saman virheenkäsittelyn piirissä kuin kuvien siirto.
                photos.discardOnSave.forEach(photoStore::discard)
                if (photos.changed) {
                    photoSaveNotice(photoRepository.apply(id, photos.removedIds, photos.added, nowSec()), photos.removedIds.size)
                        ?.let { application.resources.getQuantityString(it.pluralRes, it.count, it.count) }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diag.event("historia", "tankkauksen kuvien tallennus epäonnistui ${e.javaClass.simpleName}: ${e.message}")
                // Kirjaus on jo tallessa; kuvista kerrotaan Työmatka-sivulla, koska uusi yritys tekisi kirjauksesta tuplan.
                application.getString(R.string.photo_changes_failed)
            }
            _state.value = _state.value.copy(fillupSave = FillupSave.Done, message = message ?: _state.value.message)
        }
    }

    private suspend fun saveFillupEntry(entry: FuelEntry): Long {
        val existing = entry.id?.let { id -> _state.value.fillups.firstOrNull { it.id == id } }
        val entity = FuelFillupEntity(
            id = existing?.id ?: 0,
            epochSec = fillupEpochSec(entry.epochDay, existing?.epochSec, nowSec(), zone),
            licensePlate = entry.plate,
            odometerKm = entry.odometerKm,
            liters = entry.liters,
            totalCost = entry.totalCost,
            pricePerLiter = entry.pricePerLiter,
            backupId = existing?.backupId ?: java.util.UUID.randomUUID().toString(),
            kind = entry.kind,
            fullTank = entry.fullTank,
            currency = entry.currency,
            note = entry.note,
        )
        return if (existing == null) fuelRepository.add(entity) else entity.id.also { fuelRepository.update(entity) }
    }

    /** Lomake sulkeutui tai otti tallennuksen vaiheen vastaan; kesken olevaa tallennusta ei kuitata ([consumed]). */
    fun consumeFillupSave() {
        _state.value = _state.value.copy(fillupSave = _state.value.fillupSave.consumed())
    }

    fun deleteFillup(id: Long) {
        Diag.event("historia", "tankkaus poistetaan $id")
        viewModelScope.launch {
            val word = fillWord(_state.value.fillups.firstOrNull { it.id == id }?.kind)
            fuelRepository.delete(id)
            _state.value = _state.value.copy(
                message = application.getString(word.pick(R.string.fuel_deleted, R.string.fuel_deleted_charge, R.string.fuel_deleted_gas))
            )
        }
    }

    // ---- Vienti ja tuonti ----

    /** Raportti valitulta jaksolta sovelluksen kielellä: suomi ja ruotsi sellaisenaan, muut kielet englanniksi. */
    fun exportTo(resolver: ContentResolver, uri: Uri, period: ExportPeriod) {
        Diag.event("historia", "vienti $period")
        // Pienoiskuvien teko vie suuressa viennissä hetken: käyttäjä näkee, että vienti on käynnissä.
        _state.value = _state.value.copy(message = application.getString(R.string.work_export_started))
        viewModelScope.launch {
            runCatching {
                // Kuvat tekevät raportista suuren: kirjoitus suoraan tiedostoon, ei merkkijonon kautta.
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use {
                        workLogRepository.exportHtml(System.currentTimeMillis() / 1000, it, period, reportLocale(appLocale()))
                    }
                        ?: throw java.io.IOException("Tiedostoa ei voitu avata kirjoitettavaksi.")
                }
            }.onSuccess {
                Diag.event("historia", "vienti valmis $period")
                val (text, range) = exportDoneMessage(period)
                _state.value = _state.value.copy(
                    message = if (range == null) application.getString(text) else application.getString(text, range)
                )
            }.onFailure(::reportError)
        }
    }

    /** Viennin paneelin yhteenveto valitulle jaksolle. */
    suspend fun exportSummary(period: ExportPeriod): ExportSummary = workLogRepository.exportSummary(period)

    fun importFrom(resolver: ContentResolver, uri: Uri) {
        Diag.event("historia", "tuonti alkaa")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = resolver.openInputStream(uri)?.use { workLogRepository.importFromHtml(it) }
                        ?: throw java.io.IOException("Tiedostoa ei voitu avata.")
                    // Palautetut karttakohteiden korjaukset ilmoituksiksi kuten käynnistyksessä.
                    if (result.poiEdits > 0) runCatching { reportRepository.migrateLocalEdits(); reportRepository.flush() }
                    result
                }
            }.onSuccess { result ->
                val notice = importNotice(result)
                val counts = application.getString(notice.textRes, application.getString(notice.wordRes), *notice.counts.toTypedArray())
                val period = notice.period?.let { " " + application.getString(R.string.work_import_period, it) } ?: ""
                val templates = if (notice.templates > 0) " " + application.getString(R.string.work_import_templates, notice.templates) else ""
                val poi = if (notice.poiEdits > 0) " " + application.getString(R.string.work_import_poi, notice.poiEdits) else ""
                Diag.event("historia", "tuonti valmis: $counts$period$templates$poi")
                _state.value = _state.value.copy(message = counts + period + templates + poi)
            }.onFailure(::reportError)
        }
    }

    private fun reportError(e: Throwable) {
        if (e is CancellationException) throw e
        Diag.event("historia", "virhe ${e.javaClass.simpleName}: ${e.message}")
        _state.value = _state.value.copy(message = application.getString(UserErrors.messageRes(e, online = false)))
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

}

/**
 * Tänään-kortin lisätieto kannan päiväriveistä: tämän päivän rivin teksti, tai nykyinen, kun päivää tai riviä ei
 * vielä ole. Pelkkä avauksen ja vuorokauden vaihdon seuranta jätti tekstin vanhaksi.
 */
internal fun todayNoteFrom(days: List<WorkHistoryDay>, todayEpochDay: Long?, current: String): String {
    val today = todayEpochDay ?: return current
    return days.firstOrNull { it.date == today }?.note ?: current
}
