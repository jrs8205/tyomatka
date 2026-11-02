package fi.tyomatka.ui.drive

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.R
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.data.repo.ServiceStopPlacement
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveState
import fi.tyomatka.nav.ferrostar.LowVolume
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.appLocale
import fi.tyomatka.ui.poi.PoiButton
import fi.tyomatka.ui.poi.PoiCard
import fi.tyomatka.ui.poi.PoiCardText
import fi.tyomatka.ui.poi.PoiCategorySheet
import fi.tyomatka.ui.poi.ReportSheet
import fi.tyomatka.ui.poi.PoiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Ajonäkymä ajosession ajaksi: ajokartta koko näytöllä ja alapaneeli, jonka kurkistusosassa ovat nykyinen kohde ja
 * toiminnot. Kerros elää aktiviteetin tasolla välilehtien päällä, joten kartta, reitti ja kamera säilyvät
 * välilehtivaihdossa; piilossa ja verhon alla kartta lepää ([driveMapSleeping]). Karttakohteet näkyvät seurannan tauolla,
 * ja kohteen voi lisätä seuraavaksi pysähdykseksi varmistuksen jälkeen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveLayer(
    state: DriveState,
    viewModel: DriveViewModel,
    dimmed: StateFlow<Boolean>,
    visible: Boolean,
    modifier: Modifier = Modifier,
    poiViewModel: PoiViewModel = hiltViewModel(),
) {
    val isDimmed by dimmed.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val poiState by poiViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    MissingVoiceNotice(viewModel.missingVoice, snackbarHostState)
    LowVolumeNotice(viewModel.lowVolume, viewModel::raiseVolume, snackbarHostState)
    PoiNotice(poiState.notice, poiViewModel::dismissNotice, snackbarHostState)
    // "Vain reitin varrella": ajettavan legin jäljellä olevat askeleet. Lista kootaan vain askeleen tai reitin
    // vaihtuessa (avain), ei joka sijainnilla, ja taustasäikeessä.
    LaunchedEffect(viewModel.navigationViewModel, poiViewModel) {
        viewModel.navigationViewModel.navigationUiState
            .map { state -> state.remainingSteps?.map { it.geometry } }
            .distinctUntilChanged { old, new -> driveRouteKey(old) == driveRouteKey(new) }
            .map { driveRoutePoints(it) }
            .flowOn(Dispatchers.Default)
            .collect { poiViewModel.setRoute(it) }
    }
    val density = LocalDensity.current
    var handleHeight by remember { mutableStateOf(0.dp) }
    var peekContentHeight by remember { mutableStateOf(0.dp) }
    val peekHeight = sheetPeekHeight(handleHeight, peekContentHeight)
    var tracking by remember { mutableStateOf(true) }
    var showPoiMenu by remember { mutableStateOf(false) }
    var confirmNext by remember { mutableStateOf(false) }
    var showPoiFix by remember { mutableStateOf(false) }
    BottomSheetScaffold(
        modifier = modifier,
        // Kurkistusosassa vain nykyinen kohde ja toiminnot — kartalle jää mahdollisimman paljon tilaa, loput tiedot
        // pyyhkäistään esiin. Korkeus mitataan kahvasta ja sisällöstä, jotta Perillä ja Ohita eivät jää paneelin
        // alle isommalla fontilla.
        sheetPeekHeight = peekHeight,
        sheetDragHandle = {
            Box(Modifier.onSizeChanged { handleHeight = with(density) { it.height.toDp() } }) {
                BottomSheetDefaults.DragHandle()
            }
        },
        sheetContent = { DriveSheet(state, viewModel, onPeekContentHeight = { peekContentHeight = it }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            // Kartta täyttää koko näytön ja jatkuu paneelin alle.
            DriveMap(
                viewModel = viewModel.navigationViewModel,
                destination = destination,
                sheetPeekHeight = peekHeight,
                sleeping = driveMapSleeping(visible = visible, dimmed = isDimmed),
                modifier = Modifier.fillMaxSize(),
                pois = poiState.markers,
                onPoiClick = poiViewModel::select,
                onViewport = poiViewModel::onViewport,
                onTrackingChanged = { tracking = it },
                recenterRequests = poiViewModel.recenterRequests,
            )
            // Kohteet-nappi Ferrostarin keskitysnapin yläpuolella.
            PoiButton(
                activeCount = poiState.settings.categories.size,
                onClick = { showPoiMenu = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = peekHeight + BottomRowHeight + 64.dp),
            )
            if (!tracking) {
                val hint = when {
                    poiState.zoomedOut && poiState.settings.categories.isNotEmpty() -> stringResource(R.string.poi_zoom_in)
                    poiState.unavailable -> stringResource(R.string.poi_unavailable)
                    poiState.offline && poiState.cacheAgeDays != null ->
                        poiState.cacheAgeDays?.let { days ->
                            if (days < 1) stringResource(R.string.poi_stale_today) else pluralStringResource(R.plurals.poi_stale, days, days)
                        }
                    poiState.offline && poiState.settings.categories.isNotEmpty() -> stringResource(R.string.poi_offline)
                    poiState.truncated -> stringResource(R.string.poi_truncated)
                    else -> null
                }
                hint?.let {
                    Card(
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = peekHeight + BottomRowHeight + 8.dp),
                    ) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }

    if (showPoiMenu) {
        PoiCategorySheet(
            settings = poiState.settings,
            hasRoute = poiState.hasRoute,
            onToggle = poiViewModel::toggleCategory,
            onHeavyOnly = poiViewModel::setHeavyOnly,
            onRouteOnly = poiViewModel::setRouteOnly,
            onDismiss = { showPoiMenu = false },
        )
    }

    // Kortti nousee alapaneelin päälle ja jättää käännösotsikon näkyviin; pääkomento vaatii selväkielisen varmistuksen.
    poiState.selection?.let { selection ->
        // Ilmoita muutos korvaa kortin, kunnes se suljetaan; kortti palaa päivittyneenä.
        if (showPoiFix) {
            ReportSheet(
                selection = selection,
                notice = poiState.notice,
                onReport = poiViewModel::report,
                onDismiss = { showPoiFix = false },
                maxHeightFraction = 0.6f,
            )
        } else {
            PoiCard(
                selection = selection,
                primaryText = stringResource(R.string.poi_add_next),
                onPrimary = { confirmNext = true },
                onFix = { showPoiFix = true },
                onDismiss = { poiViewModel.select(null) },
                maxHeightFraction = 0.6f,
            )
        }
        if (confirmNext) {
            val poiName = PoiServices.displayName(selection.item) ?: stringResource(PoiCardText.categoryNameRes(selection.item.category))
            // Nykyinen kohde: ajossa se, jota kohti ajetaan; stopilla seisottaessa seuraava tuleva.
            val current = when (state.phase) {
                DrivePhase.NAVIGATING -> state.currentStop?.label
                DrivePhase.AT_STOP -> state.remaining.getOrNull(1)?.label
                else -> null
            }
            AlertDialog(
                onDismissRequest = { confirmNext = false },
                text = {
                    Text(
                        if (current != null) stringResource(R.string.poi_add_next_confirm, poiName, current)
                        else stringResource(R.string.poi_add_next_confirm_last, poiName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = Tap {
                            confirmNext = false
                            poiViewModel.addSelectedAsStop(ServiceStopPlacement.NEXT)
                        },
                    ) { Text(stringResource(R.string.poi_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = Tap { confirmNext = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

/** Kartta lepää, kun välilehti ei ole näkyvissä tai verho on päällä. */
fun driveMapSleeping(visible: Boolean, dimmed: Boolean): Boolean = !visible || dimmed

/** Kohteiden ilmoitukset (lisätty, tupla, virhe) samaan ilmoituspaikkaan kuin muut ajon ilmoitukset. */
@Composable
private fun PoiNotice(notice: String?, onDismiss: () -> Unit, hostState: SnackbarHostState) {
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        hostState.showSnackbar(message = notice, withDismissAction = true)
        onDismiss()
    }
}

/** Media-ääni on ajon alussa hiljainen: kerrotaan kerran sessiota kohti, ja Nosta nostaa tason täysille. */
@Composable
private fun LowVolumeNotice(lowVolume: StateFlow<LowVolume?>, onRaise: () -> Unit, hostState: SnackbarHostState) {
    val low by lowVolume.collectAsStateWithLifecycle()
    var shownFor by rememberSaveable { mutableStateOf(-1) }
    val message = low?.let { stringResource(R.string.volume_low, it.level, it.max) }
    val action = stringResource(R.string.volume_raise)
    LaunchedEffect(low) {
        val current = low ?: return@LaunchedEffect
        if (current.session == shownFor || message == null) return@LaunchedEffect
        shownFor = current.session
        val result = hostState.showSnackbar(message = message, actionLabel = action, withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) onRaise()
    }
}

/** Puhelimesta puuttuu puheääni ohjeiden kielelle: kerrotaan kerran kieltä kohden, ja Asenna avaa puheäänten asennuksen. */
@Composable
private fun MissingVoiceNotice(missingVoice: StateFlow<String?>, hostState: SnackbarHostState) {
    val missing by missingVoice.collectAsStateWithLifecycle()
    var shownFor by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val message = missing?.let { stringResource(R.string.voice_missing, Locale.forLanguageTag(it).getDisplayLanguage(appLocale())) }
    val action = stringResource(R.string.voice_install)
    LaunchedEffect(missing) {
        val tag = missing ?: return@LaunchedEffect
        if (tag == shownFor || message == null) return@LaunchedEffect
        shownFor = tag
        val result = hostState.showSnackbar(message = message, actionLabel = action, withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) {
            runCatching {
                context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
