package fi.tyomatka.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.R
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.ui.Tap
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import fi.tyomatka.data.repo.ServiceStopPlacement
import fi.tyomatka.ui.FullScreenDialog
import fi.tyomatka.ui.poi.PoiButton
import fi.tyomatka.ui.poi.PoiCard
import fi.tyomatka.ui.poi.PoiCategorySheet
import fi.tyomatka.ui.poi.ReportSheet
import fi.tyomatka.ui.poi.PoiViewModel
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import fi.tyomatka.ui.components.mapCardColors
import fi.tyomatka.ui.components.AppButtonDefaults
import fi.tyomatka.ui.components.ConnectedChoiceGroup
import fi.tyomatka.ui.boundParts

/** Etäisyys listan reunasta (px), jonka sisällä raahaus alkaa vierittää listaa. */
private const val AUTOSCROLL_ZONE_PX = 160f

/** Vieritysaskel per kehys automaattivierityksessä (px). */
private const val AUTOSCROLL_STEP_PX = 22f

/**
 * Koko reitin yleiskatsaus: reittiviiva, numeroidut stoppimarkerit ja
 * kokonaismatka/-kesto. Järjestys-nappi avaa koko näytön raahausnäkymän.
 * Kartta näkyy aina — myös ilman stoppeja oma sijainti ja keskitysnappi
 * ovat käytettävissä.
 */
@Composable
fun OverviewScreen(viewModel: OverviewViewModel = hiltViewModel(), poiViewModel: PoiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val poiState by poiViewModel.state.collectAsStateWithLifecycle()
    var showReorder by remember { mutableStateOf(false) }
    var showPoiMenu by remember { mutableStateOf(false) }
    var showPoiFix by remember { mutableStateOf(false) }

    // Ennen sijainnin hakua: vertailu haetaan vain, kun kartta on näkyvissä.
    DisposableEffect(viewModel) {
        viewModel.setMapVisible(true)
        onDispose { viewModel.setMapVisible(false) }
    }
    // Sivulle tultaessa haetaan tuore sijainti — ilman stoppeja kamera
    // keskittyy siihen.
    LaunchedEffect(Unit) { viewModel.refreshOrigin() }
    // Reittiviiva "Vain reitin varrella" -suodattimelle.
    val drawnRoute = state.comparison.drawnRoute(state.routePoints)
    LaunchedEffect(drawnRoute) { poiViewModel.setRoute(drawnRoute) }

    Box(Modifier.fillMaxSize()) {
        OverviewMap(
            stops = state.stops,
            origin = state.origin,
            routePoints = state.routePoints,
            drawnRoute = drawnRoute,
            otherRoute = state.comparison.otherRoute,
            modifier = Modifier.fillMaxSize(),
            onLocationGranted = viewModel::refreshOrigin,
            pois = poiState.markers,
            onPoiClick = poiViewModel::select,
            onViewport = poiViewModel::onViewport,
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                // Mittakaavajana on kartan vasemmassa ja käännetyn kartan kompassi oikeassa yläkulmassa;
                // kortit niiden alle (kortti osui kompassin alakulmaan).
                .padding(start = 12.dp, end = 12.dp, top = 60.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.stops.isEmpty()) {
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = mapCardColors()) {
                    Text(
                        stringResource(
                            if (state.hasHandledStops) R.string.all_stops_done_map else R.string.no_stops
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (state.comparison.available) {
                RouteChoiceCard(state.comparison, viewModel::setRouteChoice)
                Spacer(Modifier.padding(2.dp))
            }
            if (state.totalDistanceM > 0) {
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = mapCardColors()) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Vertailussa matka ja aika näkyvät jo valinnassa.
                        if (!state.comparison.available) {
                            Text(
                                stringResource(
                                    R.string.route_total,
                                    formatKm(state.totalDistanceM),
                                    formatDuration(state.totalDurationSec),
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        state.comparison.finishEpochSec(state.finishEpochSec)?.let { finish ->
                            Text(
                                stringResource(R.string.finish_estimate, formatClock(finish)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (state.loadingRoute) {
                Spacer(Modifier.padding(4.dp))
                CircularProgressIndicator()
            }
            state.error?.let { error ->
                Spacer(Modifier.padding(4.dp))
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = mapCardColors()) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.route_fetch_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = Tap(viewModel::retryRoute)) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            // Kohteiden tila: liian kaukana, vanha välimuisti ilman verkkoa, palvelu ei saatavilla tai ikkuna katkaistu.
            if (poiState.zoomedOut && poiState.settings.categories.isNotEmpty()) {
                HintCard(stringResource(R.string.poi_zoom_in))
            }
            poiState.cacheAgeDays?.takeIf { poiState.offline }?.let { days ->
                HintCard(if (days < 1) stringResource(R.string.poi_stale_today) else pluralStringResource(R.plurals.poi_stale, days, days))
            }
            if (poiState.offline && poiState.cacheAgeDays == null && !poiState.zoomedOut && poiState.settings.categories.isNotEmpty()) {
                HintCard(stringResource(R.string.poi_offline))
            }
            if (poiState.unavailable) {
                HintCard(stringResource(R.string.poi_unavailable))
            }
            if (poiState.truncated && !poiState.zoomedOut) {
                HintCard(stringResource(R.string.poi_truncated))
            }
        }

        PoiButton(
            activeCount = poiState.settings.categories.size,
            onClick = { showPoiMenu = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = RecenterBottomPadding + 56.dp),
        )

        poiState.notice?.let { notice ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                dismissAction = {
                    TextButton(colors = AppButtonDefaults.snackbarActionColors(), onClick = Tap(poiViewModel::dismissNotice)) { Text(stringResource(R.string.ok), fontWeight = FontWeight.Bold) }
                },
            ) { Text(notice) }
        }

        if (state.stops.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = Tap { showReorder = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Filled.SwapVert, null) },
                text = { Text(stringResource(R.string.reorder_stops)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    if (showReorder) {
        ReorderOverlay(
            stops = state.stops,
            etaByStopId = state.rows.withIndex().associate { (index, row) ->
                row.stop.id to state.comparison.arrivalEpochSec(index, row.arrivalEpochSec)
            },
            onApply = viewModel::reorder,
            onDismiss = { showReorder = false },
        )
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

    // Kohdekortti: Kartta-välilehdeltä kohde lisätään päivän listan loppuun.
    poiState.selection?.let { selection ->
        // Ilmoita muutos korvaa kortin, kunnes se suljetaan; kortti palaa päivittyneenä.
        if (showPoiFix) {
            ReportSheet(
                selection = selection,
                notice = poiState.notice,
                onReport = poiViewModel::report,
                onDismiss = { showPoiFix = false },
            )
        } else {
            PoiCard(
                selection = selection,
                primaryText = stringResource(R.string.poi_add_stop),
                onPrimary = { poiViewModel.addSelectedAsStop(ServiceStopPlacement.END) },
                onFix = { showPoiFix = true },
                onDismiss = { poiViewModel.select(null) },
            )
        }
    }
}

/** Lyhyt huomautus kartan yläreunan korttien joukossa. */
@Composable
private fun HintCard(text: String) {
    Spacer(Modifier.padding(4.dp))
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = mapCardColors()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Koko näytön järjestelynäkymä: stoppia raahataan kahvasta, lista vierittyy
 * itsestään kun raahaus osuu reunaan (ykkösen saa vedettyä vaikka viimeiseksi),
 * ja irrotus tallentaa järjestyksen, sulkee näkymän ja palauttaa kartalle —
 * siirron tuloksen näkee heti reitistä.
 */
@Composable
private fun ReorderOverlay(
    stops: List<StopEntity>,
    etaByStopId: Map<Long, Long?>,
    onApply: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Paikallinen kopio raahauksen ajaksi; kanta päivittyy vasta irrotettaessa.
    val order = remember { mutableStateListOf<StopEntity>().apply { addAll(stops) } }
    // ARRIVED-stoppi (jolla ollaan parhaillaan) pysyy listan kärjessä eikä liiku.
    val pinnedCount = remember { stops.count { it.status == StopStatus.ARRIVED } }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var draggedId by remember { mutableStateOf<Long?>(null) }
    /** Sormen korkeus listan koordinaateissa raahauksen aikana. */
    var pointerY by remember { mutableStateOf(0f) }
    var moved by remember { mutableStateOf(false) }
    var autoScrollSpeed by remember { mutableStateOf(0f) }
    /** Elettä edeltänyt järjestys — palautetaan jos järjestelmä peruu eleen. */
    var dragSnapshot by remember { mutableStateOf<List<StopEntity>?>(null) }

    fun applySwapForPointer() {
        val id = draggedId ?: return
        val from = order.indexOfFirst { it.id == id }
        if (from < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo.map { VisibleRow(it.index, it.offset, it.size) }
        val to = reorderTarget(visible, from, pointerY, pinnedCount, order.lastIndex) ?: return
        // Vierityskohta kiinni indeksiin ennen siirtoa: muuten LazyColumn seuraa
        // ylimmän rivin avainta, hyppää rivin verran ja kumoaa automaattivierityksen.
        if (swapMovesFirstVisible(listState.firstVisibleItemIndex, from, to)) {
            listState.requestScrollToItem(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
        order.add(to, order.removeAt(from))
        moved = true
    }

    fun endDrag(apply: Boolean) {
        autoScrollSpeed = 0f
        val didMove = moved
        draggedId = null
        moved = false
        if (apply && didMove) {
            // Värinä kuittaa onnistuneen siirron ennen kuin näkymä sulkeutuu.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onApply(order.filter { it.status != StopStatus.ARRIVED }.map { it.id })
            onDismiss()
        } else if (!apply && didMove) {
            // Peruttu ele: palauta elettä edeltänyt järjestys, ettei seuraava
            // onnistunut raahaus kirjoita myös perutun eleen muutoksia kantaan.
            dragSnapshot?.let { snapshot ->
                order.clear()
                order.addAll(snapshot)
            }
        }
        dragSnapshot = null
    }

    // Automaattivieritys: lista rullaa niin kauan kuin sormi on reunavyöhykkeellä,
    // ja raahattu stoppi vaihtaa paikkaa sormen alla olevan rivin kanssa.
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            listState.scrollBy(autoScrollSpeed)
            applySwapForPointer()
            delay(16)
        }
    }

    FullScreenDialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    // Nollaa kehyksen sisällä (ks. FullScreenDialog); varmuudeksi
                    // siltä varalta, että dialogi joskus piirtyy palkkien alle.
                    .systemBarsPadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                ) {
                    IconButton(onClick = Tap(onDismiss)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                    Text(
                        stringResource(R.string.reorder_stops),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    stringResource(R.string.reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(order, key = { _, stop -> stop.id }) { index, stop ->
                        val dragging = draggedId == stop.id
                        // Varmistus: jos LazyColumn hävittää raahatun rivin kesken eleen,
                        // kahvan ele ei enää pääty eikä sormen irrotusta havaita. Peru
                        // raahaus, ettei näkymä jää värisemään.
                        DisposableEffect(stop.id) {
                            onDispose { if (draggedId == stop.id) endDrag(apply = false) }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == stop.id }
                                        translationY =
                                            if (info != null) pointerY - info.offset - info.size / 2f
                                            else 0f
                                        shadowElevation = 12f
                                    }
                                }
                                .background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stop.label,
                                    style = MaterialTheme.typography.bodyLarge,
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
                            etaByStopId[stop.id]?.let { arrival ->
                                Text(
                                    formatClock(arrival),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (stop.status == StopStatus.ARRIVED) {
                                Text(
                                    stringResource(R.string.at_stop),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            } else {
                                // Kosketusalue 48 dp Boxissa — Icon itse skaalaisi kuvakkeen 48 dp:hen.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(48.dp).pointerInput(stop.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                val info = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == stop.id }
                                                pointerY =
                                                    (info?.offset ?: 0) + (info?.size ?: 0) / 2f
                                                dragSnapshot = order.toList()
                                                draggedId = stop.id
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                pointerY += amount.y
                                                applySwapForPointer()
                                                val viewportEnd =
                                                    listState.layoutInfo.viewportEndOffset
                                                autoScrollSpeed = when {
                                                    pointerY < AUTOSCROLL_ZONE_PX ->
                                                        -AUTOSCROLL_STEP_PX
                                                    pointerY > viewportEnd - AUTOSCROLL_ZONE_PX ->
                                                        AUTOSCROLL_STEP_PX
                                                    else -> 0f
                                                }
                                            },
                                            onDragEnd = { endDrag(apply = true) },
                                            onDragCancel = { endDrag(apply = false) },
                                        )
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = stringResource(R.string.reorder_drag_handle),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Nopein ja lyhin reitti: napautus vaihtaa sinisen viivan heti, ja sama valinta koskee seuraavaa ajoa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteChoiceCard(comparison: RouteComparison, onChoice: (RouteChoice) -> Unit) {
    val options = listOf(RouteChoice.NORMAL to R.string.route_choice_normal, RouteChoice.SHORTEST to R.string.route_choice_shortest)
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), colors = mapCardColors()) {
        // Valinta muodosta ja väristä; matka ja aika toisella rivillä. Ahtaassa tilassa vaihtoehdot allekkain.
        ConnectedChoiceGroup(
            options = options.map { it.first },
            selected = comparison.choice,
            label = { option -> stringResource(options.first { it.first == option }.second) },
            onSelect = onChoice,
            supportingText = { option ->
                val preview = comparison.previews.getValue(option)
                boundParts(formatKm(preview.distanceM), formatDuration(preview.durationSec))
            },
            modifier = Modifier.padding(8.dp),
        )
    }
}
