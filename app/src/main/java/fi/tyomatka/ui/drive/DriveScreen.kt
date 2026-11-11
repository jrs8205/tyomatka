package fi.tyomatka.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tyomatka.R
import fi.tyomatka.core.nav.RouteChoice
import fi.tyomatka.ui.Tap
import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.core.model.StopEta
import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveState
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatKm
import fi.tyomatka.ui.history.GapDialog
import fi.tyomatka.ui.history.PlateField
import fi.tyomatka.ui.history.StartReadingField
import fi.tyomatka.ui.history.forms.WorkStartForm
import kotlinx.coroutines.flow.StateFlow
import fi.tyomatka.BuildConfig
import fi.tyomatka.ui.components.AdaptiveButtonRow
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color

/** Bottom sheetin kurkistuskorkeus ennen mittausta — sama arvo nostaa kartan sisällön paneelin yläpuolelle. */
internal val SHEET_PEEK_HEIGHT = 160.dp

/** Ajon alapaneelin toimintonappien korkeus (Perillä, Ohita, Merkitse käydyksi). */
private val ActionHeight = 56.dp

/**
 * Kurkistuskorkeus mitatusta kahvasta ja kurkistussisällöstä, jotta Perillä ja Ohita eivät jää paneelin alle
 *. Ennen ensimmäistä mittausta oletus, ettei paneeli painu kasaan.
 */
internal fun sheetPeekHeight(handle: Dp, content: Dp): Dp = if (content <= 0.dp) SHEET_PEEK_HEIGHT else handle + content

/**
 * Ajo-välilehti: aloitus- ja päivä valmis -näkymät. Ajon aikana välilehti on tyhjä, koska ajonäkymä
 * ([DriveLayer]: kartta ja alapaneeli) elää aktiviteetin tasolla välilehtien päällä ja säilyy välilehtivaihdossa.
 * [onStartSimulatedDrive] on debug-version pöytätesti: sijainti kulkee reittiä pitkin.
 */
@Composable
fun DriveScreen(
    onStartDrive: (Long) -> Unit,
    onStartSimulatedDrive: (Long) -> Unit = {},
    viewModel: DriveViewModel = hiltViewModel(),
) {
    val state by viewModel.driveState.collectAsStateWithLifecycle()
    val dayId by viewModel.todayDayId.collectAsStateWithLifecycle()
    val stopCount by viewModel.todayStopCount.collectAsStateWithLifecycle()
    val routeChoice by viewModel.routeChoice.collectAsStateWithLifecycle()
    val remainingCount by viewModel.todayRemainingCount.collectAsStateWithLifecycle()
    val workSegments by viewModel.todayWorkSegments.collectAsStateWithLifecycle()
    val startPrefill by viewModel.startPrefill.collectAsStateWithLifecycle()
    val knownPlates by viewModel.knownPlates.collectAsStateWithLifecycle()
    val latestReadings by viewModel.latestReadings.collectAsStateWithLifecycle()
    val gapSuggestion by viewModel.gapSuggestion.collectAsStateWithLifecycle()
    var showWorkStart by remember { mutableStateOf(false) }

    // FINISHED koskee vain sitä päivää jona se ajettiin loppuun: uuden
    // vuorokauden alkaessa tai kun samalle päivälle lisätään uusi käynti,
    // näytetään taas aloitusruutu käynnistysnappeineen.
    val finishedStale = state.phase == DrivePhase.FINISHED &&
        (state.dayId != dayId || remainingCount > 0)

    when {
        state.phase == DrivePhase.IDLE || finishedStale -> IdleContent(
            stopCount = stopCount,
            routeChoice = routeChoice,
            onStart = {
                // Työpäivän kirjaus unohtunut? Kysytään aloituskilometrit
                // ennen ajoa — tai ajetaan kirjaamatta.
                if (workSegments.isEmpty()) {
                    showWorkStart = true
                } else {
                    dayId?.let(onStartDrive)
                }
            },
            onStartSimulated = { dayId?.let(onStartSimulatedDrive) },
        )

        state.phase == DrivePhase.FINISHED -> FinishedContent(state)

        // Ajon aikana näkymän piirtää DriveLayer välilehden päällä.
        else -> Box(Modifier.fillMaxSize())
    }

    if (showWorkStart) {
        WorkStartBeforeDriveDialog(
            prefillKm = startPrefill.km,
            prefillPlate = startPrefill.plate,
            knownPlates = knownPlates,
            latestReadings = latestReadings,
            onStartAndDrive = { km, plate ->
                viewModel.startWork(km, plate)
                showWorkStart = false
                dayId?.let(onStartDrive)
            },
            onDriveOnly = {
                showWorkStart = false
                dayId?.let(onStartDrive)
            },
            onDismiss = { showWorkStart = false },
        )
    }

    gapSuggestion?.let { suggestion ->
        GapDialog(
            suggestion = suggestion,
            onConfirm = viewModel::confirmGap,
            onDismiss = viewModel::dismissGap,
        )
    }
}

/**
 * Työpäivä aloittamatta ajoa käynnistettäessä: kirjaa tai aja kirjaamatta. Lomakkeen tila on sama kuin Työmatka-sivun
 * aloituslomakkeella ([WorkStartForm]): auton vaihto napista vaihtaa koskemattoman esitäytön ([latestReadings]).
 */
@Composable
private fun WorkStartBeforeDriveDialog(
    prefillKm: Long?,
    prefillPlate: String?,
    knownPlates: List<String>,
    latestReadings: Map<String, Long>,
    onStartAndDrive: (km: Long?, plate: String?) -> Unit,
    onDriveOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    var form by remember(prefillKm, prefillPlate) {
        mutableStateOf(WorkStartForm(km = prefillKm?.toString() ?: "", plate = prefillPlate ?: "", prefillKm = prefillKm))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.work_start_before_drive)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (form.prefillKm != null) {
                    Text(
                        stringResource(R.string.work_prefill_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                StartReadingField(form.km, { form = form.withKm(it) }, form.prefillKm)
                Spacer(Modifier.height(8.dp))
                PlateField(form.plate, { form = form.withPlate(it, latestReadings) }, stringResource(R.string.work_plate), knownPlates)
            }
        },
        confirmButton = {
            TextButton(colors = AppButtonDefaults.textColors(), enabled = form.canSave, onClick = Tap { onStartAndDrive(form.kmValue, form.plate) }) {
                Text(stringResource(R.string.work_start_and_drive))
            }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDriveOnly)) {
                Text(stringResource(R.string.work_drive_without))
            }
        },
    )
}

@Composable
private fun IdleContent(
    stopCount: Int,
    routeChoice: RouteChoice,
    onStart: () -> Unit,
    onStartSimulated: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                pluralStringResource(R.plurals.stop_count, stopCount, stopCount),
                style = MaterialTheme.typography.titleLarge,
            )
            ShortestRouteNotice(routeChoice)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = Tap(onStart),
                enabled = stopCount > 0,
                colors = AppButtonDefaults.filledColors(),
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.start_driving), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (BuildConfig.DEBUG) {
                // Pöytätesti ilman autoa: vain debug-versiossa.
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = Tap(onStartSimulated),
                    enabled = stopCount > 0,
                    colors = AppButtonDefaults.outlinedColors(),
                    border = AppButtonDefaults.border(enabled = stopCount > 0),
                ) {
                    Text("Simuloi ajo")
                }
            }
            if (stopCount == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_stops),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Reitti valitaan Kartta-sivulla, jossa vaikutus näkyy kartalla. Ajo-sivu kertoo valinnasta vain, kun lyhin on voimassa
 * (valintapäivän); nopein on oletus eikä tarvitse riviä.
 */
@Composable
private fun ShortestRouteNotice(choice: RouteChoice) {
    if (choice != RouteChoice.SHORTEST) return
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.route_choice_shortest_active),
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.route_choice_shortest_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FinishedContent(state: DriveState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.day_finished), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.stops_progress, state.doneCount, state.totalCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bottom sheetin sisältö ([DriveLayer]). Kurkistusosassa nykyinen stoppi (aina numero 1),
 * toiminnot ja seuraava stoppi; ylös vetämällä eteneminen, koko jäljellä
 * oleva lista poistoineen ja ajon lopetus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriveSheet(state: DriveState, viewModel: DriveViewModel, onPeekContentHeight: (Dp) -> Unit = {}) {
    val schedule = state.schedule
    val density = LocalDensity.current
    val guard = remember { StopActionGuard() }
    // Tilan vaihtuminen (Perillä → Valmis, Ohita → seuraava stoppi) estää napautuksen sekunnin ajan.
    LaunchedEffect(state.phase, state.currentStop?.id) { guard.stateChanged(SystemClock.uptimeMillis()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // Kurkistusosa: virhe, nykyinen stoppi ja toiminnot. Mitattu korkeus on paneelin kurkistuskorkeus.
        Column(Modifier.onSizeChanged { onPeekContentHeight(with(density) { it.height.toDp() }) }) {
            state.error?.let {
                // Enintään kaksi riviä: pitkä kiintiöviesti ei saa kasvattaa kurkistusosaa
                // niin, että kartalta loppuu tila ajon aikana.
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Nykyinen stoppi + toiminnot
            state.currentStop?.let { current ->
                val currentEta = schedule?.stopEtas?.firstOrNull()
                FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.phase == DrivePhase.AT_STOP) {
                            stringResource(R.string.at_stop)
                        } else {
                            stringResource(R.string.next_stop)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Jäljellä oleva matka stopille Nav SDK:n live-tiedosta.
                    if (state.phase == DrivePhase.NAVIGATING) {
                        state.liveDistanceM?.let { meters ->
                            Text(
                                stringResource(R.string.distance_remaining, formatKm(meters.toLong())),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "1. ${current.label}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    currentEta?.let {
                        Text(
                            formatClock(it.arrivalEpochSec),
                            style = MaterialTheme.typography.titleMedium,
                            color = deadlineColor(it.deadlineStatus),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val actionStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                if (state.phase == DrivePhase.AT_STOP) {
                    // Viimeisellä stopilla ei ole "seuraavaa" — sama kuittaus
                    // päättää päivän, ja tekstin pitää kertoa se.
                    // remaining sisältää nykyisen stopin; == 1 (ei <= 1), jottei
                    // teksti väläytä päivän päättämistä ennen kuin tarkkailija
                    // on ehtinyt täyttää listan prosessin palautuessa henkiin.
                    val isLastStop = state.remaining.size == 1
                    Button(
                        onClick = guarded(guard) { viewModel.departCurrentStop() },
                        colors = AppButtonDefaults.filledColors(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = ActionHeight),
                    ) {
                        Text(
                            stringResource(
                                if (isLastStop) R.string.arrived_finish_day
                                else R.string.arrived_mark_done
                            ),
                            style = actionStyle,
                        )
                    }
                } else {
                    val arriveUnlocked = isArriveUnlocked(state.liveDistanceM, state.fallbackDistanceM)
                    val arriveLabel = stringResource(R.string.at_stop)
                    val skipLabel = stringResource(R.string.skip_stop)
                    // Rinnakkain Perillä täyttää rivin ja Ohita on tekstinsä levyinen; jos tekstit eivät mahdu
                    // kokonaisina, napit allekkain, jottei "Framme" tai "Hoppa över" katkea kesken sanan.
                    AdaptiveButtonRow(
                        labels = listOf(arriveLabel, skipLabel),
                        // Lukon tila mitataan aina, jottei rivi vaihdu allekkaisesta rinnakkaiseksi lukon avautuessa.
                        hasIcon = listOf(true, false),
                        style = actionStyle,
                        gap = 8.dp,
                        fillIndex = 0,
                    ) { index, buttonModifier ->
                        if (index == 0) {
                            // Lukittuna ääriviivallinen, lukko ja teksti täydellä kontrastilla; auki täytetty primary.
                            Button(
                                onClick = guarded(guard) { viewModel.markArrived() },
                                enabled = arriveUnlocked,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = Color.Transparent,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                border = if (arriveUnlocked) null else AppButtonDefaults.border(enabled = true),
                                modifier = buttonModifier.heightIn(min = ActionHeight),
                            ) {
                                if (!arriveUnlocked) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = stringResource(R.string.at_stop_locked),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(arriveLabel, style = actionStyle)
                            }
                        } else {
                            OutlinedButton(
                                onClick = guarded(guard) { viewModel.skipCurrentStop() },
                                colors = AppButtonDefaults.outlinedColors(),
                                border = AppButtonDefaults.border(enabled = true),
                                modifier = buttonModifier.heightIn(min = ActionHeight),
                            ) {
                                Text(skipLabel, style = actionStyle)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- Pyyhkäisemällä ylös esiin tuleva osa ----

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.stops_progress, state.doneCount + 1, state.totalCount),
                style = MaterialTheme.typography.labelLarge,
            )
            schedule?.let {
                Text(
                    stringResource(R.string.finish_estimate, formatClock(it.finishEpochSec)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.totalCount > 0) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.doneCount.toFloat() / state.totalCount },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Kaikki tulevat stopit (numerosta 2 eteenpäin) ETA-aikoineen.
        schedule?.let { snap ->
            if (snap.stopEtas.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.remaining_stops),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    itemsIndexed(snap.stopEtas.drop(1), key = { _, eta -> eta.stop.id }) { index, eta ->
                        RemainingStopRow(
                            number = index + 2,
                            eta = eta,
                            onRemove = { viewModel.removeStop(eta.stop.id) },
                        )
                    }
                }
            }
        }

        var showStopDialog by remember { mutableStateOf(false) }
        // Reunallinen nappi virhevärillä muiden nappien tyyliin (pelkkä teksti ei erottunut).
        OutlinedButton(
            onClick = Tap { showStopDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.stop_driving))
        }
        Spacer(Modifier.height(8.dp))
        if (showStopDialog) {
            AlertDialog(
                onDismissRequest = { showStopDialog = false },
                title = { Text(stringResource(R.string.stop_confirm_title)) },
                text = { Text(stringResource(R.string.stop_confirm_text)) },
                confirmButton = {
                    TextButton(
                        onClick = Tap {
                            showStopDialog = false
                            viewModel.stopDriving()
                        }
                    ) {
                        Text(
                            stringResource(R.string.stop_driving),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = Tap { showStopDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/** Napautus värinällä vain, kun [guard] hyväksyy sen; ohitettu tuplanapautus ei värise eikä tee mitään. */
private fun guarded(guard: StopActionGuard, action: () -> Unit): () -> Unit = {
    if (guard.accept(SystemClock.uptimeMillis())) {
        Tap.now()
        action()
    }
}

@Composable
private fun RemainingStopRow(number: Int, eta: StopEta, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            "$number. ${eta.stop.label}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatClock(eta.arrivalEpochSec),
            style = MaterialTheme.typography.bodyMedium,
            color = deadlineColor(eta.deadlineStatus),
        )
        IconButton(onClick = Tap(onRemove)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.remove_stop),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.width(18.dp),
            )
        }
    }
}

@Composable
private fun deadlineColor(status: DeadlineStatus) = when (status) {
    DeadlineStatus.LATE -> MaterialTheme.colorScheme.error
    DeadlineStatus.TIGHT -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}
