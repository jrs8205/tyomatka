package fi.tyomatka.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import fi.tyomatka.R
import fi.tyomatka.core.api.sources
import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.SuggestionKind
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import fi.tyomatka.core.text.ImportedText
import fi.tyomatka.ui.Tap
import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.core.model.StopEta
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.core.plan.ServiceStops
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.repo.OpenMode
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.plan.nearby.NearbyPage
import fi.tyomatka.ui.plan.nearby.NearbySearchField
import fi.tyomatka.ui.plan.nearby.NearbyViewModel
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatMinutesOfDay
import kotlinx.coroutines.launch

/**
 * Suunnittelunäkymä: stoppien lisäys (osoitehaku / liitetty lista), aikarajat,
 * kestot ja päivän aikatauluennuste. Järjestys on aina kuljettajan oma —
 * se muokataan Kartta-sivun järjestelynäkymässä.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel = hiltViewModel(),
    templateViewModel: RouteTemplateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val templateState by templateViewModel.state.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var editStop by remember { mutableStateOf<StopEntity?>(null) }
    var deadlineDialogStop by remember { mutableStateOf<StopEntity?>(null) }
    var serviceDialogStop by remember { mutableStateOf<StopEntity?>(null) }
    var confirmClearStops by remember { mutableStateOf(false) }
    var showSaveTemplate by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var openTemplate by remember { mutableStateOf<RouteTemplateEntity?>(null) }
    var renameTemplate by remember { mutableStateOf<RouteTemplateEntity?>(null) }
    var deleteTemplate by remember { mutableStateOf<RouteTemplateEntity?>(null) }
    var discardTemplateName by remember { mutableStateOf<String?>(null) }

    val etasByStopId = state.schedule?.stopEtas?.associateBy { it.stop.id } ?: emptyMap()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = Tap { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_stop))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            val remainingIds = state.stops
                .filter { it.status != StopStatus.DONE && it.status != StopStatus.SKIPPED }
                .map { it.id }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                templateState.banner?.let { banner ->
                    item(key = "template-banner") {
                        TemplateChangedBanner(
                            banner = banner,
                            onSave = { showSaveTemplate = true },
                            onDiscard = { discardTemplateName = banner.templateName },
                            onLater = templateViewModel::later,
                        )
                    }
                }
                item {
                    SummaryCard(
                        state = state,
                        templateState = templateState,
                        onClearAll = { confirmClearStops = true },
                        onSaveTemplate = { showSaveTemplate = true },
                        onShowTemplates = { showTemplateSheet = true },
                    )
                }
                if (state.stops.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_stops),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        )
                    }
                }
                items(state.stops, key = { it.id }) { stop ->
                    val position = remainingIds.indexOf(stop.id)
                    StopRow(
                        stop = stop,
                        number = if (position >= 0) position + 1 else null,
                        eta = etasByStopId[stop.id],
                        onEditClick = { editStop = stop },
                        onDelete = { viewModel.removeStop(stop.id) },
                        onDeadlineClick = { deadlineDialogStop = stop },
                        onServiceClick = { serviceDialogStop = stop },
                    )
                }
                item { Spacer(Modifier.height(88.dp)) }
            }

            state.error?.let { error ->
                Snackbar(
                    dismissAction = {
                        TextButton(onClick = Tap(viewModel::dismissError)) { Text(stringResource(R.string.ok)) }
                    }
                ) { Text(error) }
            }
            templateState.notice?.let { notice ->
                Snackbar(
                    dismissAction = {
                        TextButton(onClick = Tap(templateViewModel::dismissNotice)) { Text(stringResource(R.string.ok)) }
                    }
                ) { Text(notice) }
            }
        }
    }

    if (showAddSheet) {
        AddStopOverlay(
            state = state,
            viewModel = viewModel,
            onDismiss = {
                showAddSheet = false
                viewModel.onSearchChanged("")
            },
        )
    }

    editStop?.let { stop ->
        EditStopOverlay(
            stop = stop,
            state = state,
            viewModel = viewModel,
            onDismiss = {
                editStop = null
                viewModel.onSearchChanged("")
            },
        )
    }

    deadlineDialogStop?.let { stop ->
        DeadlineDialog(
            stop = stop,
            onSet = { minutes ->
                viewModel.setDeadline(stop.id, minutes)
                deadlineDialogStop = null
            },
            onDismiss = { deadlineDialogStop = null },
        )
    }

    serviceDialogStop?.let { stop ->
        ServiceTimeDialog(
            stop = stop,
            onSet = { minutes ->
                viewModel.setServiceMinutes(stop.id, minutes)
                serviceDialogStop = null
            },
            onDismiss = { serviceDialogStop = null },
        )
    }

    if (confirmClearStops) {
        AlertDialog(
            onDismissRequest = { confirmClearStops = false },
            title = { Text(stringResource(R.string.clear_stops)) },
            text = { Text(pluralStringResource(R.plurals.clear_stops_confirm, state.stops.size, state.stops.size)) },
            confirmButton = {
                TextButton(
                    onClick = Tap {
                        viewModel.clearAllStops()
                        confirmClearStops = false
                    }
                ) {
                    Text(stringResource(R.string.clear_stops), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = Tap { confirmClearStops = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSaveTemplate) {
        SaveTemplateDialog(
            customerStopCount = state.stops.count { !ServiceStops.isServiceStop(it.placeId) },
            templates = templateState.templates,
            openedTemplate = templateState.openedTemplate,
            onSaveNew = { name ->
                templateViewModel.saveNew(name)
                showSaveTemplate = false
            },
            onOverwrite = { id ->
                templateViewModel.overwrite(id)
                showSaveTemplate = false
            },
            onDismiss = { showSaveTemplate = false },
        )
    }

    if (showTemplateSheet) {
        RouteTemplateSheet(
            templates = templateState.templates,
            onOpen = { template ->
                showTemplateSheet = false
                if (templateState.dayStopCount == 0) {
                    templateViewModel.open(template.id, OpenMode.REPLACE)
                } else {
                    openTemplate = template
                }
            },
            onRename = { renameTemplate = it },
            onDelete = { deleteTemplate = it },
            onDismiss = { showTemplateSheet = false },
        )
    }

    openTemplate?.let { template ->
        OpenTemplateDialog(
            template = template,
            dayStopCount = templateState.dayStopCount,
            onOpen = { mode ->
                templateViewModel.open(template.id, mode)
                openTemplate = null
            },
            onDismiss = { openTemplate = null },
        )
    }

    renameTemplate?.let { template ->
        RenameTemplateDialog(
            template = template,
            onRename = { name ->
                templateViewModel.rename(template.id, name)
                renameTemplate = null
            },
            onDismiss = { renameTemplate = null },
        )
    }

    deleteTemplate?.let { template ->
        DeleteTemplateDialog(
            template = template,
            onDelete = {
                templateViewModel.delete(template.id)
                deleteTemplate = null
            },
            onDismiss = { deleteTemplate = null },
        )
    }

    discardTemplateName?.let { name ->
        DiscardTemplateChangesDialog(
            templateName = name,
            onDiscard = {
                templateViewModel.discardChanges()
                discardTemplateName = null
            },
            onDismiss = { discardTemplateName = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(
    state: PlanUiState,
    templateState: TemplateUiState,
    onClearAll: () -> Unit,
    onSaveTemplate: () -> Unit,
    onShowTemplates: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.stop_count, state.stops.size, state.stops.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                if (state.stops.isNotEmpty()) {
                    IconButton(onClick = Tap(onClearAll)) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.clear_stops),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.stops.isNotEmpty()) {
                    AssistChip(
                        onClick = Tap(onSaveTemplate),
                        enabled = !templateState.driving,
                        label = { Text(stringResource(R.string.template_save)) },
                        leadingIcon = { Icon(Icons.Filled.Save, null, Modifier.width(16.dp)) },
                    )
                }
                AssistChip(
                    onClick = Tap(onShowTemplates),
                    enabled = !templateState.driving,
                    label = { Text(stringResource(R.string.template_list, templateState.templates.size)) },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.width(16.dp)) },
                )
            }
            if (templateState.driving) {
                Text(
                    stringResource(R.string.template_disabled_driving),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.schedule?.let { schedule ->
                Spacer(Modifier.height(8.dp))
                val duration = schedule.finishEpochSec - schedule.computedAtEpochSec
                Text(
                    stringResource(
                        R.string.day_summary,
                        formatClock(schedule.finishEpochSec),
                        formatDuration(duration),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                schedule.stopEtas.filter { it.deadlineStatus == DeadlineStatus.LATE }.forEach { late ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.deadline_late_warning,
                            late.stop.label,
                            formatClock(late.arrivalEpochSec),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopRow(
    stop: StopEntity,
    number: Int?,
    eta: StopEta?,
    onEditClick: () -> Unit,
    onDelete: () -> Unit,
    onDeadlineClick: () -> Unit,
    onServiceClick: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            // Nimi- ja osoiterivin napautus avaa stopin muokkauksen; chipit ja
            // poistonappi alarivillä säilyttävät omat toimintonsa.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = Tap(onEditClick)),
            ) {
                Text(
                    when {
                        number != null -> "$number."
                        stop.status == StopStatus.DONE -> "✓"
                        else -> "–"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stop.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stop.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                eta?.let {
                    Text(
                        formatClock(it.arrivalEpochSec),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (it.deadlineStatus) {
                            DeadlineStatus.LATE -> MaterialTheme.colorScheme.error
                            DeadlineStatus.TIGHT -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                AssistChip(
                    onClick = Tap(onDeadlineClick),
                    label = {
                        Text(
                            stop.deadlineMinutesOfDay?.let {
                                stringResource(R.string.deadline_at, formatMinutesOfDay(it))
                            } ?: stringResource(R.string.set_deadline)
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.width(16.dp)) },
                )
                AssistChip(
                    onClick = Tap(onServiceClick),
                    label = {
                        Text(stop.serviceSec?.let { "${it / 60} min" } ?: stringResource(R.string.set_service_time))
                    },
                    leadingIcon = { Icon(Icons.Filled.Timer, null, Modifier.width(16.dp)) },
                )
                }
                // Poisto reunassa erillään chipeistä, ettei siihen osu vahingossa.
                IconButton(onClick = Tap(onDelete)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Koko näytön osoitehaku karttasovellusten tapaan: hakupalkki puhelimen
 * yläreunassa, osumat heti sen alla — mikään ei jää näppäimistön alle.
 * Sivut Hae osoite, Kohteet ja Liitä lista vierekkäin pyyhkäistävinä.
 */
@Composable
private fun AddStopOverlay(
    state: PlanUiState,
    viewModel: PlanViewModel,
    onDismiss: () -> Unit,
) {
    var pastedText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val pagerState = rememberPagerState { ADD_STOP_PAGES }
    val scope = rememberCoroutineScope()
    // FullScreenDialog ei luo omaa ViewModel-omistajaa: ViewModel elää Suunnittelu-kohteen mukana, joten ikkunan
    // sulkeminen peruu sijainnin luvun ja latauksen, ja seuraava avaus lukee sijainnin uudelleen.
    val nearbyViewModel: NearbyViewModel = hiltViewModel()
    val nearbyState by nearbyViewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { nearbyViewModel.close() } }

    FullScreenDialog(onDismissRequest = onDismiss, dismissOnBackPress = false) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        // Osoitekenttä saa fokuksen vain ikkunan avautuessa; Kohteet-sivu näyttää listan ensin, joten muilla sivuilla
        // näppäimistö piilotetaan, eikä edellisen sivun kenttä jää kohdistetuksi.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // Takaisin-ele siirtää aina edelliselle sivulle eikä koskaan sulje ikkunaa: liitetty
        // lista tai haku ei katoa vahingossa. Ikkuna suljetaan nuolesta tai valinnalla.
        BackHandler {
            previousAddStopPage(pagerState.currentPage)?.let { page -> scope.launch { pagerState.animateScrollToPage(page) } }
        }
        LaunchedEffect(pagerState.currentPage) {
            if (pagerState.currentPage != PAGE_ADDRESS) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
        // Lähtöpiste luetaan, kun Kohteet-sivulle on pysähdytty ensimmäisen kerran (ei ohi kulkiessa välilehden
        // napautuksella sivulta 1 sivulle 3); sivun vaihto ei peru latausta.
        LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage == PAGE_NEARBY) nearbyViewModel.open()
        }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                // Sama korkeus kaikilla sivuilla: välilehdet eivät hyppää kesken pyyhkäisyn.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp)
                        .heightIn(min = TOP_BAR_MIN_HEIGHT),
                ) {
                    IconButton(onClick = Tap(onDismiss)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                    if (pagerState.currentPage == PAGE_ADDRESS) {
                        OutlinedTextField(
                            value = state.searchText,
                            onValueChange = viewModel::onSearchChanged,
                            label = { Text(stringResource(R.string.search_address)) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            // Hakukentät päistään pyöristettyjä; lomakekentät pysyvät 12 dp:n kulmissa.
                            shape = CircleShape,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    } else if (pagerState.currentPage == PAGE_NEARBY) {
                        NearbySearchField(nearbyViewModel.queryText, nearbyViewModel::setQuery, Modifier.weight(1f))
                    } else {
                        Text(
                            stringResource(R.string.paste_list),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                AddStopTabs(
                    pagerState = pagerState,
                    titles = listOf(
                        stringResource(R.string.add_stop_tab_address),
                        stringResource(R.string.nearby_tab),
                        stringResource(R.string.paste_list),
                    ),
                    onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                )
                PageDots(count = ADD_STOP_PAGES, current = pagerState.currentPage)
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        PAGE_ADDRESS -> AddressResults(state, viewModel, onDismiss)
                        PAGE_NEARBY -> NearbyPage(nearbyState, nearbyViewModel) { item ->
                            viewModel.addPoi(item)
                            onDismiss()
                        }
                        else -> PasteListPage(state, viewModel, pastedText, { pastedText = it }, onDismiss)
                    }
                }
                // Osoitehaun virhe näkyy hakunäkymän sisällä; Suunnittelu-sivun Snackbar jäisi tämän alle.
                state.error?.let { error ->
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        dismissAction = {
                            TextButton(onClick = Tap(viewModel::dismissError)) { Text(stringResource(R.string.ok)) }
                        },
                    ) { Text(error) }
                }
            }
        }
    }
}

private const val ADD_STOP_PAGES = 3
private val TOP_BAR_MIN_HEIGHT = 64.dp
private const val PAGE_ADDRESS = 0
private const val PAGE_NEARBY = 1

/** Hae osoite -sivu: osoitemuistin ja haun osumat sekä lähdemaininta. */
@Composable
private fun AddressResults(state: PlanUiState, viewModel: PlanViewModel, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        // Uudet osumat aina listan alkuun — säilynyt vieritys voi
        // muuten piilottaa ensimmäisen tuloksen hakupalkin alle.
        LaunchedEffect(state.savedSuggestions, state.suggestions) {
            listState.scrollToItem(0)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(state.savedSuggestions, key = { "saved-${it.id}" }) { saved ->
                ListItem(
                    headlineContent = { Text(saved.label) },
                    supportingContent = { Text(saved.address) },
                    leadingContent = { Icon(Icons.Filled.History, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Sulje haku heti valinnan jälkeen, jotta uusi
                        // stoppi näkyy Suunnittelu-listalla saman tien.
                        .clickable(onClick = Tap {
                            viewModel.addSavedAddress(saved)
                            onDismiss()
                        }),
                )
            }
            items(sectioned(state.suggestions), key = { it.key() }) { row ->
                when (row) {
                    is SearchRow.Header -> SearchSectionHeader(row.kind)
                    is SearchRow.Item -> SuggestionRow(row.suggestion) {
                        viewModel.addSuggestion(row.suggestion)
                        onDismiss()
                    }
                }
            }
        }
        // Lähdemaininta pysyy näkyvissä listan alla, vaikka tuloksia vieritetään.
        state.suggestions.sources()?.let { SearchSources(it) }
    }
}

/** Liitä lista -sivu: osoitteet riveittäin kentästä tai tiedostosta. */
@Composable
private fun PasteListPage(
    state: PlanUiState,
    viewModel: PlanViewModel,
    pastedText: String,
    onPastedText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(
            value = pastedText,
            onValueChange = onPastedText,
            label = { Text(stringResource(R.string.paste_list_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
        Spacer(Modifier.height(8.dp))
        val context = LocalContext.current
        // Tiedostosta: txt/csv riveinä, xlsx ensimmäinen taulukko; lisätään kentän perään.
        val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val lines = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (!ImportedText.isSupported(name, bytes)) {
                    viewModel.reportUnsupportedImport()
                    return@rememberLauncherForActivityResult
                }
                ImportedText.lines(name, bytes)
            }.getOrElse {
                // Rikkinäinen tiedosto (esim. katkennut zip) ei saa kadota hiljaa.
                viewModel.reportUnsupportedImport()
                emptyList()
            }
            if (lines.isNotEmpty()) {
                val separator = System.lineSeparator()
                onPastedText(
                    listOf(pastedText.trim(), lines.joinToString(separator))
                        .filter { it.isNotBlank() }
                        .joinToString(separator),
                )
            }
        }
        OutlinedButton(
            onClick = Tap {
                fileLauncher.launch(
                    arrayOf(
                        "text/plain",
                        "text/csv",
                        "text/comma-separated-values",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/octet-stream",
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.paste_import_file))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = Tap {
                viewModel.addPastedList(pastedText) { onDismiss() }
            },
            enabled = pastedText.isNotBlank() && !state.geocodingList,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.geocode_list))
        }
    }
}

/**
 * Stopin muokkaus karttasovellusten tapaan ilman erillistä tallennusnappia:
 * osoite vaihdetaan valitsemalla ehdotuksista (osoitemuisti + osoite- ja yrityshaku), mikä
 * tallentuu heti, sulkee näkymän ja näyttää vahvistuksen Suunnittelu-sivulla.
 * Vain osoitehaun tuntemia osoitteita voi valita — olematon katunumero ei
 * koskaan päädy stopiksi. Nimenmuutos tallentuu näkymää suljettaessa.
 */
@Composable
private fun EditStopOverlay(
    stop: StopEntity,
    state: PlanUiState,
    viewModel: PlanViewModel,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(stop.label) }
    var addressText by remember { mutableStateOf(stop.address) }
    // Käsin kirjoitettu nimi säilyy osoitteenvaihdossa; koskematon nimi
    // korvautuu uuden paikan nimellä.
    val customLabel = label.takeIf { it.isNotBlank() && it != stop.label }
    val addressEdited = addressText.isNotBlank() && addressText != stop.address
    // Ajossa olevan kohteen osoitekenttä lukitaan jo tässä, ettei turhia
    // hakuja lähde muutoksesta, jonka tallennus joka tapauksessa estyy.
    val addressLocked = state.activeDriveStopId == stop.id

    // Sulkeminen mistä tahansa polusta tallentaa nimenmuutoksen — myös kun
    // osoitevalinta epäonnistuu (tupla/estetty/verkkovirhe), nimi ei katoa.
    fun close() {
        customLabel?.let { viewModel.renameStop(stop.id, it) }
        onDismiss()
    }

    FullScreenDialog(onDismissRequest = ::close) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                ) {
                    IconButton(onClick = Tap(::close)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                    Text(
                        stringResource(R.string.edit_stop),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.stop_name)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = {
                            addressText = it
                            viewModel.onSearchChanged(it)
                        },
                        label = { Text(stringResource(R.string.stop_new_address)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        shape = CircleShape,
                        enabled = !addressLocked,
                        supportingText = if (addressLocked) {
                            { Text(stringResource(R.string.edit_active_stop_blocked)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (addressEdited && !addressLocked) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.savedSuggestions, key = { "saved-${it.id}" }) { saved ->
                            ListItem(
                                headlineContent = { Text(saved.label) },
                                supportingContent = { Text(saved.address) },
                                leadingContent = { Icon(Icons.Filled.History, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = Tap {
                                        viewModel.changeStopAddressSaved(stop, saved, customLabel)
                                        close()
                                    }),
                            )
                        }
                        items(sectioned(state.suggestions), key = { it.key() }) { row ->
                            when (row) {
                                is SearchRow.Header -> SearchSectionHeader(row.kind)
                                is SearchRow.Item -> SuggestionRow(row.suggestion) {
                                    viewModel.changeStopAddress(stop, row.suggestion, customLabel)
                                    close()
                                }
                            }
                        }
                    }
                    state.suggestions.sources()?.let { SearchSources(it) }
                }
            }
        }
    }
}

private fun SearchRow.key(): String = when (this) {
    is SearchRow.Header -> "header-$kind"
    is SearchRow.Item -> suggestion.placeId
}

/** Hakulistan osion otsikko: "Yritykset" tai "Osoitteet" (yrityshaku 28.10.). */
@Composable
private fun SearchSectionHeader(kind: SuggestionKind) {
    Text(
        stringResource(if (kind == SuggestionKind.BUSINESS) R.string.search_section_business else R.string.search_section_address),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Haun ehdotus: nimi ja osoite; yritykseltä, jolla ei ole osoitetta, pelkkä nimi. */
@Composable
private fun SuggestionRow(suggestion: PlaceSuggestion, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(suggestion.primaryText) },
        supportingContent = suggestion.secondaryText.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        leadingContent = { Icon(Icons.Filled.Add, null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = Tap(onClick)),
    )
}

/** Osoite- ja yrityshaun lähdemaininta hakutulosten alla, kuten palveluiden ehdot vaativat. */
@Composable
private fun SearchSources(sources: String) {
    Text(
        sources,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineDialog(
    stop: StopEntity,
    onSet: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = (stop.deadlineMinutesOfDay ?: 900) / 60,
        initialMinute = (stop.deadlineMinutesOfDay ?: 900) % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_deadline)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = timeState) } },
        confirmButton = {
            TextButton(onClick = Tap { onSet(timeState.hour * 60 + timeState.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Column {
                if (stop.deadlineMinutesOfDay != null) {
                    TextButton(onClick = Tap { onSet(null) }) { Text(stringResource(R.string.clear_deadline)) }
                }
                TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ServiceTimeDialog(
    stop: StopEntity,
    onSet: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(stop.serviceSec?.let { (it / 60).toString() } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_service_time)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isBlank() || text.toIntOrNull() != null, onClick = Tap { onSet(text.toIntOrNull()) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
