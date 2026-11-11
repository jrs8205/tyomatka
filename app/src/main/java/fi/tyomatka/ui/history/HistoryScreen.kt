package fi.tyomatka.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.R
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.Tap
import fi.tyomatka.data.report.ExportChoice
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.FuelInterval
import fi.tyomatka.data.repo.defaultFuelPlate
import fi.tyomatka.data.repo.suggestionPlates
import fi.tyomatka.data.report.ExportPeriod
import fi.tyomatka.data.report.ExportSummary
import fi.tyomatka.data.report.exportFileName
import fi.tyomatka.ui.history.export.ExportForm
import fi.tyomatka.ui.history.export.ExportFormSaver
import fi.tyomatka.ui.history.export.ExportSheet
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.history.forms.CarsScreen
import fi.tyomatka.ui.history.forms.FuelFormScreen
import fi.tyomatka.ui.history.forms.carListRows
import fi.tyomatka.ui.history.forms.defaultFillWord
import fi.tyomatka.ui.history.forms.fillWord
import fi.tyomatka.ui.history.forms.fuelKindName
import fi.tyomatka.ui.history.forms.SwitchCarFormScreen
import fi.tyomatka.ui.history.forms.WorkEndFormScreen
import fi.tyomatka.ui.history.forms.WorkFixFormScreen
import fi.tyomatka.ui.history.forms.WorkStartFormScreen
import fi.tyomatka.ui.history.forms.rememberNowEpochSec
import fi.tyomatka.ui.formatConsumption
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatAmount
import fi.tyomatka.ui.formatMoney
import fi.tyomatka.ui.formatMoneyByCurrency
import fi.tyomatka.ui.formatUnitPrice
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.settings.SettingsScreen
import fi.tyomatka.ui.components.ConnectedChoiceGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** LazyColumnin alkio, jossa valitun jakson nuolirivi ja pager ovat: Tänään-kortin ja jaksovalitsimen jälkeen. */
private const val PERIOD_ITEM_INDEX = 2

/**
 * Työmatka-sivu: kilometrit ja ajoaika päivä-, viikko-, kuukausi- ja
 * vuositasolla, HTML-raportin vienti/tuonti sekä työpäivän kirjaus
 * (Tänään-kortti: aloitus, autonvaihto, lopetus ja tankkaus; lukemien korjaus päiväkortista).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var workDialog by remember { mutableStateOf<WorkDialog?>(null) }
    var fixSegments by remember { mutableStateOf<List<WorkSegmentEntity>?>(null) }
    var fixDate by remember { mutableStateOf<Long?>(null) }
    var expandedDate by remember { mutableStateOf<Long?>(null) }
    // Avoin tankkauslomake palautuu prosessin lopetuksen yli (kamera auki); sen syötteet tallentaa lomake itse.
    var fuelDialog by rememberSaveable(stateSaver = FuelDialogTargetSaver) { mutableStateOf<FuelDialogTarget?>(null) }
    var carsOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // Viennin paneeli ja sen valinta palautuvat prosessin yli; valinta luetaan myös tiedostonvalitsimen palatessa.
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var exportForm by rememberSaveable(stateSaver = ExportFormSaver) { mutableStateOf(ExportForm()) }
    // Vientipäivä talteen Vie-napin hetkellä: keskiyön yli kestävä tiedostonvalinta ei vaihda jaksoa.
    var exportDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val carRows = remember(state.cars, state.knownPlates, state.fillups) {
        carListRows(state.cars, state.knownPlates, state.fillups)
    }
    val knownFuelPlates = remember(carRows) { carRows.filter { it.fuel != null }.map { it.car.plate }.toSet() }
    // Rekisterinumeronapeissa myös autot, joilla ei vielä ole kirjauksia (esittelyn auto).
    val plates = remember(state.knownPlates, state.cars) { suggestionPlates(state.knownPlates, state.cars.values) }
    // Jaettu kuva avaa uuden kirjauksen, ellei lomake ole jo auki (silloin kuva liittyy siihen).
    val sharedPhotos by viewModel.sharedPhotos.collectAsStateWithLifecycle()
    LaunchedEffect(sharedPhotos) {
        if (sharedPhotos.isNotEmpty() && fuelDialog == null) fuelDialog = FuelDialogTarget.New()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val period = exportForm.period(LocalDate.ofEpochDay(exportDay))
        if (uri != null && period != null) viewModel.exportTo(context.contentResolver, uri, period)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFrom(context.contentResolver, it) } }

    val periods = listOf(
        WorkPeriod.DAY to R.string.period_day,
        WorkPeriod.WEEK to R.string.period_week,
        WorkPeriod.MONTH to R.string.period_month,
        WorkPeriod.YEAR to R.string.period_year,
    )
    val openWorkSegment = state.todaySegments.firstOrNull { it.endEpochSec == null }
    // Oletusauton käyttövoima profiilista tai sen kirjauksista: "Lataus", "Täyttö" tai "Tankkaus". Sama sana Tänään-kortin
    // napille ja tyhjän listan tekstille.
    val todayWord = remember(openWorkSegment, state.defaultPlate, state.fillups, state.cars) {
        defaultFillWord(openWorkSegment, state.defaultPlate, state.fillups, state.cars)
    }
    val now = rememberNowEpochSec()
    val todayEpochDay = remember(now) { Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() }
    val periodKeys = remember(state.rows) { state.rows.map { it.key } }
    val periodPager = rememberPeriodPagerState(periodKeys, state.period, todayEpochDay)
    val listState = rememberLazyListState()

    // Muistutusilmoituksen "Kirjaa nyt" avaa aloitusdialogin.
    LaunchedEffect(state.openStartDialog) {
        if (state.openStartDialog) {
            workDialog = WorkDialog.Start
            viewModel.consumeOpenStartRequest()
        }
    }

    // Vienti- ja tuontiraportti häviää itsestään; sivulta poistuminen kuittaa sen. Autot-näkymä on oma ikkunansa
    // tämän sivun päällä ja näyttää ilmoituksen itse, kun se on auki.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message, carsOpen, settingsOpen) {
        val message = state.message ?: return@LaunchedEffect
        if (carsOpen || settingsOpen) return@LaunchedEffect
        snackbarHostState.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.dismissMessage()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.dismissMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_history)) },
                actions = {
                    IconButton(onClick = Tap { carsOpen = true }) {
                        Icon(Icons.Filled.Garage, contentDescription = stringResource(R.string.cars_title))
                    }
                    IconButton(
                        onClick = Tap {
                            // Koko varmuuskopio tulee asetuksista: raportin vienti alkaa aina jaksovalinnoista.
                            if (exportForm.choice == ExportChoice.FULL_BACKUP) exportForm = ExportForm()
                            exportOpen = true
                        },
                        enabled = state.hasAny,
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.work_export))
                    }
                    IconButton(onClick = Tap { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                state = listState,
            ) {
                // Alkioiden järjestys: Tänään-kortti, jaksovalitsin ja valittu jakso (PERIOD_ITEM_INDEX), jonka alla muut
                // jaksot. Ryhmän rivit ovat kiinni toisissaan, joten välit ovat alkioiden omia.
                item(key = "today") {
                    TodayCard(
                        summary = todaySummary(state.todaySegments, now, nextPlate = state.defaultPlate),
                        onStartWork = { workDialog = WorkDialog.Start },
                        onSwitchCar = { workDialog = WorkDialog.SwitchCar() },
                        onEndWork = { workDialog = WorkDialog.End },
                        onAddFuel = { fuelDialog = FuelDialogTarget.New() },
                        word = todayWord,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "periods") {
                    ConnectedChoiceGroup(
                        options = periods.map { it.first },
                        selected = state.period,
                        label = { period -> stringResource(periods.first { it.first == period }.second) },
                        onSelect = viewModel::setPeriod,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(
                                todayWord.pick(R.string.work_history_empty, R.string.work_history_empty_charge, R.string.work_history_empty_gas)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        )
                    }
                }
                periodPagerItems(
                    rows = state.rows,
                    period = state.period,
                    todayEpochDay = todayEpochDay,
                    state = periodPager,
                    itemKey = "period",
                    onShowSelected = { listState.animateScrollToItem(PERIOD_ITEM_INDEX) },
                ) { row ->
                    if (row.date == null) {
                        PeriodDetail(row)
                    } else {
                        DayCard(
                            row = row,
                            expanded = row.date == expandedDate,
                            segments = state.segmentsByDate[row.date].orEmpty(),
                            intervalsByFillup = state.intervalsByFillup,
                            photosByFillup = state.photosByFillup,
                            onEditFillup = { fuelDialog = FuelDialogTarget.Edit(it.id) },
                            onAddFuel = { fuelDialog = FuelDialogTarget.New(epochDay = it) },
                            onToggle = {
                                expandedDate = if (expandedDate == row.date) null else row.date
                            },
                            onFix = {
                                fixDate = row.date
                                fixSegments = state.segmentsByDate[row.date]
                            },
                        )
                    }
                }
                item(key = "end") { Spacer(Modifier.height(16.dp)) }
            }

        }
    }

    when (val dialog = workDialog) {
        WorkDialog.Start -> WorkStartFormScreen(
            title = stringResource(
                if (state.todaySegments.isEmpty()) R.string.work_start else R.string.work_continue
            ),
            prefillKm = state.prefillKm,
            prefillPlate = state.defaultPlate,
            knownPlates = plates,
            knownFuelPlates = knownFuelPlates,
            latestReadings = state.latestReadings,
            onStart = { km, plate, fuel, note ->
                viewModel.startWork(km, plate, fuel, note)
                workDialog = null
            },
            onDismiss = { workDialog = null },
            prefillNote = state.todayNote,
        )
        is WorkDialog.SwitchCar -> SwitchCarFormScreen(
            minEndKm = openWorkSegment?.odometerStartKm,
            currentPlate = openWorkSegment?.licensePlate,
            knownPlates = plates,
            knownFuelPlates = knownFuelPlates,
            latestReadings = state.latestReadings,
            onConfirm = { endKm, startKm, plate, fuel ->
                viewModel.switchCar(endKm, startKm, plate, fuel)
                workDialog = null
            },
            onDismiss = { workDialog = null },
            prefillPlate = dialog.prefillPlate,
            prefillStartKm = dialog.prefillStartKm,
        )
        WorkDialog.End -> WorkEndFormScreen(
            segments = state.todaySegments,
            minKm = openWorkSegment?.odometerStartKm,
            plate = openWorkSegment?.licensePlate,
            onConfirm = { km, note ->
                viewModel.endWork(km, note)
                workDialog = null
            },
            onDismiss = { workDialog = null },
            note = state.todayNote,
        )
        null -> Unit
    }

    if (exportOpen) {
        val today = LocalDate.now()
        val period = exportForm.period(today)
        // Määrät lasketaan valitulle jaksolle; edellisen jakson luvut eivät kelpaa uudelle valinnalle.
        val summary by produceState<Pair<ExportPeriod, ExportSummary>?>(null, period) {
            if (period == null) return@produceState
            try {
                value = period to viewModel.exportSummary(period)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ilman yhteenvetoa Vie pysyy pois käytöstä; virhe näkyy, jos vienti silti epäonnistuu.
            }
        }
        ExportSheet(
            form = exportForm,
            onFormChange = { exportForm = it },
            summary = summary?.takeIf { it.first == period }?.second,
            today = today,
            onDismiss = { exportOpen = false },
            onExport = {
                exportOpen = false
                exportDay = today.toEpochDay()
                period?.let { exportLauncher.launch(exportFileName(it, today)) }
            },
        )
    }

    if (settingsOpen) {
        SettingsScreen(
            onClose = { settingsOpen = false },
            onImport = {
                settingsOpen = false
                importLauncher.launch(arrayOf("text/html", "text/plain"))
            },
            onExportBackup = {
                settingsOpen = false
                exportForm = ExportForm(ExportChoice.FULL_BACKUP)
                exportOpen = true
            },
        )
    }

    if (carsOpen) {
        CarsScreen(
            rows = carRows,
            message = state.message,
            ongoing = openWorkSegment != null,
            openPlate = openWorkSegment?.licensePlate,
            nextPlate = state.defaultPlate,
            onMessageShown = viewModel::dismissMessage,
            onSave = viewModel::saveCar,
            onDelete = viewModel::deleteCar,
            // Autot-näkymä sulkeutuu, ja Vaihda auto -lomake avautuu valittu auto ja sen viimeisin lukema esitäytettynä;
            // mikään ei tallennu ennen lomakkeen vahvistusta.
            onSwitchCar = { plate ->
                carsOpen = false
                workDialog = WorkDialog.SwitchCar(prefillPlate = plate, prefillStartKm = viewModel.latestReadingKm(plate))
            },
            onChooseNext = viewModel::chooseNextCar,
            onDismiss = { carsOpen = false },
        )
    }

    // Lomake sulkeutuu vasta, kun kirjaus on tallennettu; epäonnistunut tallennus jättää sen auki virheineen.
    LaunchedEffect(state.fillupSave) {
        if (state.fillupSave == FillupSave.Done) {
            fuelDialog = null
            viewModel.consumeFillupSave()
        }
    }
    fuelDialog?.let { target ->
        val closeFuelDialog = {
            fuelDialog = null
            viewModel.consumeFillupSave()
        }
        when (val step = fuelDialogStep(target, state.fillups, state.fillupsLoaded)) {
            // Palautuksessa muokattava kirjaus latautuu vasta hetken päästä. Odotus peittää sivun: muuten Tänään-kortin
            // nappi avaisi uuden lomakkeen samaan kohtaan, ja se kuluttaisi muokkauksen tallenteen (syötteet, kuvat ja
            // kameran käynnistimen avaimen), jolloin tallennus tekisi tuplakirjauksen.
            FuelDialogStep.Wait -> FillupLoadingDialog(onDismiss = closeFuelDialog)
            FuelDialogStep.Reset -> LaunchedEffect(Unit) { closeFuelDialog() }
            // Kohteen avain (kirjaus ja avauksen tunniste) pitää lomakkeiden tallenteet erillään (rememberSaveable-avain on
            // koostumuksen sijainnin hash, eivätkä lomakkeen inputs vaikuta palautukseen): uusi avaus ei kuluta aiemman
            // avauksen tallennetta, ja palautettu avaus löytää omansa.
            is FuelDialogStep.Open -> key(target) {
                val existing = step.existing
                FuelFormScreen(
                    existing = existing,
                    defaultPlate = defaultFuelPlate(openWorkSegment, state.defaultPlate, state.fillups),
                    initialEpochDay = step.epochDay,
                    knownPlates = plates,
                    fillups = state.fillups,
                    cars = state.cars,
                    storedPhotos = existing?.let { state.photosByFillup[it.id] }.orEmpty(),
                    photoActions = viewModel.photoActions,
                    incomingPhotos = sharedPhotos,
                    takeIncomingPhotos = viewModel::takeSharedPhotos,
                    save = state.fillupSave,
                    onSave = viewModel::saveFillup,
                    onDelete = {
                        existing?.let { viewModel.deleteFillup(it.id) }
                        closeFuelDialog()
                    },
                    onDismiss = closeFuelDialog,
                )
            }
        }
    }

    fixSegments?.let { segments ->
        WorkFixFormScreen(
            segments = segments,
            knownPlates = plates,
            onSave = { edits, note ->
                edits.forEach { (id, fix) ->
                    viewModel.updateSegment(
                        id, fix.startKm, fix.endKm, fix.startEpochSec, fix.endEpochSec, fix.licensePlate
                    )
                }
                fixDate?.let { viewModel.saveDayNote(it, note) }
                fixSegments = null
            },
            onDismiss = { fixSegments = null },
            note = fixDate?.let { state.notesByDate[it] }.orEmpty(),
        )
    }

    state.gapSuggestion?.let { suggestion ->
        GapDialog(
            suggestion = suggestion,
            onConfirm = viewModel::confirmGap,
            onDismiss = viewModel::dismissGap,
        )
    }
}

/**
 * Päivän sisältö jaksonäkymässä, kunnes päivänäkymä uudistuu: kilometrit ja työaika, tankkaukset (napautus muokkaa),
 * tankkaus jälkikäteen ja napautuksella laajeneva ajojen lista.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    row: WorkPeriodRow,
    expanded: Boolean,
    segments: List<WorkSegmentEntity>,
    intervalsByFillup: Map<Long, FuelInterval>,
    photosByFillup: Map<Long, List<FuelPhotoEntity>>,
    onEditFillup: (FuelFillupEntity) -> Unit,
    /** Tankkaus jälkikäteen kortin päivälle (epochDay). */
    onAddFuel: (Long) -> Unit,
    onToggle: () -> Unit,
    onFix: () -> Unit,
) {
    val date = row.date ?: return
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = Tap(onToggle)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Päivä ja kilometrit rivittyvät suurella fontilla omille riveilleen; kynä pysyy oikeassa reunassa.
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, style = MaterialTheme.typography.titleMedium)
                    if (row.hasWork) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DistanceIcon(Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${formatWhole(row.km)} km",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (row.hasWork) {
                    // Kynä kertoo, että päivän kirjauksia voi muokata; siirto tasaa sen rivien kynien kanssa.
                    IconButton(onClick = Tap(onFix), modifier = Modifier.offset(x = 12.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.work_fix), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                when {
                    !row.hasWork -> stringResource(R.string.work_no_entry)
                    row.ongoing -> stringResource(R.string.work_in_progress)
                    else -> stringResource(R.string.work_drive_time, formatDuration(row.durationSec))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Lisätiedot-merkki kuvamerkin tapaan; teksti itse näkyy vasta korjauslomakkeessa.
            if (row.hasNote) NoteMarker(onClick = onFix)
            // Yhteenveto vain lajeille, joilla on päivänä useampi tankkaus: muuten rivi toistaisi tankkausrivin.
            row.fuel?.byKind?.filter { fuel -> row.fillups.count { it.kind == fuel.kind } > 1 }?.forEach { fuel ->
                Text(
                    stringResource(
                        R.string.fuel_tanked_kind,
                        fuelKindName(fuel.kind),
                        formatAmount(fuel.liters, fuel.kind),
                        formatMoneyByCurrency(fuel.costs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (row.fillups.isNotEmpty()) HorizontalDivider(Modifier.padding(top = 8.dp))
            row.fillups.forEach { fillup ->
                FillupRow(fillup, intervalsByFillup[fillup.id], photosByFillup[fillup.id]?.size ?: 0, hasNote = fillup.note.isNotBlank()) { onEditFillup(fillup) }
            }
            // Eilen tekemättä jäänyt tankkaus kirjataan jälkikäteen päiväkortista; tekstinappi
            // tankkaustietojen kohdalla, ei toista tankkausikonia km-luvun perään.
            TextButton(onClick = Tap { onAddFuel(date) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.fuel_add_for_day))
            }
            if (row.missingReadings) {
                AssistChip(
                    onClick = Tap(onFix),
                    label = { Text(stringResource(R.string.work_missing_readings_short)) },
                    leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
        if (expanded && segments.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                segments.forEach { seg -> SegmentDetailRow(seg) }
                TextButton(onClick = Tap(onFix)) {
                    Text(stringResource(R.string.work_fix))
                }
            }
        }
    }
}

/**
 * Matkan merkki päivän kilometrien edessä (vain päivätasolla, jaksokorteissa yksikkö on luvun alla).
 * Teksti kertoo luvun, joten ikoni on koriste.
 */
@Composable
internal fun DistanceIcon(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = modifier)
}

/**
 * Yksi tankkaus päiväkortissa; napautus avaa muokkauksen (myös poisto). Määrä ja hinta lajin yksikössä, laji nimellä
 * (paitsi diesel), vajaa merkittynä ja kuvien määrä, kun kirjauksella on kuvia.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillupRow(fillup: FuelFillupEntity, interval: FuelInterval?, photoCount: Int, hasNote: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(fillWord(fillup.kind).pick(R.string.fuel_edit, R.string.fuel_edit_charge, R.string.fuel_edit_gas)),
                onClick = Tap(onClick),
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            if (fillup.kind == FuelKind.ELECTRIC) Icons.Filled.EvStation else Icons.Filled.LocalGasStation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Yksikköhinta siirtyy omalle rivilleen, jos määrä ja hinta eivät mahdu sen kanssa samalle riville.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${formatAmount(fillup.liters, fillup.kind)} · ${formatMoney(fillup.totalCost, fillup.currency)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    formatUnitPrice(fillup.pricePerLiter, fillup.currency, fillup.kind.unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val details = listOfNotNull(
                fuelKindName(fillup.kind).takeIf { fillup.kind != FuelKind.DIESEL },
                fillup.licensePlate,
                "${formatWhole(fillup.odometerKm)}\u00A0km",
                stringResource(R.string.fuel_partial).takeIf { !fillup.fullTank },
            )
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            interval?.let {
                Text(
                    stringResource(R.string.fuel_since_prev, formatConsumption(it.consumption, it.kind)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Kuitti tai muu kuva todisteena; teksti kertoo määrän, joten kuvake on koriste.
            if (photoCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pluralStringResource(R.plurals.fuel_photo_count, photoCount, photoCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Kirjauksen lisätiedot: merkki riittää, koko rivi avaa lomakkeen, jossa teksti näkyy.
            if (hasNote) NoteMarker(onClick = null)
        }
        // Kynä kertoo, että tankkausta voi muokata; koko rivi on napautettava.
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/** Yksi autosegmentti laajennetussa päiväkortissa: kellonajat ja lukemat. */
@Composable
private fun SegmentDetailRow(seg: WorkSegmentEntity) {
    val retro = seg.endEpochSec != null && seg.startEpochSec == seg.endEpochSec
    val clock = if (retro) {
        stringResource(R.string.work_retro_entry)
    } else {
        "${formatClock(seg.startEpochSec)}–${seg.endEpochSec?.let(::formatClock) ?: "…"}"
    }
    val times = seg.licensePlate?.let { "$it · $clock" } ?: clock
    val start = seg.odometerStartKm
    val end = seg.odometerEndKm
    val invalid = start != null && end != null && end < start
    val missing = start == null || (seg.endEpochSec != null && end == null)
    val readings = "${start?.let(::formatWhole) ?: "–"} → ${end?.let(::formatWhole) ?: "–"}"
    val kms = if (start != null && end != null && !invalid) {
        "$readings · ${formatWhole(end - start)} km"
    } else readings
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(times, style = MaterialTheme.typography.bodySmall)
        Text(
            kms,
            style = MaterialTheme.typography.bodySmall,
            color = if (invalid || missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Muokattavan kirjauksen latauksen odotus koko näytöllä, ettei sivua voi käyttää sillä välin; takaisin-ele peruu. */
@Composable
private fun FillupLoadingDialog(onDismiss: () -> Unit) {
    FullScreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Työpäiväkirjauksen dialogit; autonvaihto kantaa Autot-näkymästä valitun auton ja sen viimeisimmän lukeman esitäytöksi.
 */
private sealed interface WorkDialog {
    data object Start : WorkDialog
    data class SwitchCar(val prefillPlate: String? = null, val prefillStartKm: Long? = null) : WorkDialog
    data object End : WorkDialog
}

