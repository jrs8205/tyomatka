package fi.tyomatka.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.ui.PART_SEPARATOR
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.formatAmount
import fi.tyomatka.ui.formatDayRange
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatMoneyByCurrency
import fi.tyomatka.ui.formatWhole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val GroupCorner = 24.dp

/**
 * Valitun jakson tila: valinta ([PeriodChoice]) ja pager. Valinta on lähde: nuoli ja muiden jaksojen rivi valitsevat
 * heti, ja pager animoi perään; pyyhkäisy valitsee, kun pager asettuu. Rivien muuttuessa valinta seuraa
 * [followSelection]in mukaan, ja pager siirtyy valittuun ilman animaatiota.
 */
@Stable
class PeriodPagerState internal constructor(keys: List<String>, private val scope: CoroutineScope) {
    var choice by mutableStateOf(PeriodChoice(keys))
        private set

    /** Sivu on rivin indeksi ([PeriodChoice.keys]); uusin on oikealla (`reverseLayout`). */
    val pager: PagerState = PagerState(currentPage = 0) { choice.keys.size }

    /** Nuolirivin sivu: pyyhkäisyn ja animaation aikana se, jolle pager on asettumassa. */
    val shownPage: Int get() = if (pager.isScrollInProgress) pager.targetPage else choice.page

    /**
     * Nuoli tai muiden jaksojen rivi: valinta heti, pager animoi perään. [before] ajetaan ennen animaatiota (sivun
     * vieritys nuoliriviin, jotta pager on näkyvissä animoidessaan).
     */
    fun select(key: String, before: (suspend () -> Unit)? = null) {
        // Valinta odottaa pageria: aiemman pyyhkäisyn asettuminen vierityksen tai animaation aikana ei kumoa sitä.
        choice = choice.request(key)
        selectJob?.cancel()
        selectJob = scope.launch {
            // Kosketus voi keskeyttää vierityksen; pager siirtyy silti valittuun, silloin ilman animaatiota. Sivu luetaan
            // vasta vierityksen jälkeen, koska rivit voivat muuttua sen aikana.
            val scrolled = try {
                before?.invoke()
                true
            } catch (e: CancellationException) {
                ensureActive()
                false
            }
            try {
                if (scrolled) pager.animateScrollToPage(choice.page) else pager.requestScrollToPage(choice.page)
                choice = choice.requestDone(key)
            } catch (e: CancellationException) {
                choice = choice.requestDone(key)
                // Uusi pyyhkäisy keskeytti animaation ja on voinut jo asettua: valinta seuraa sitä.
                if (!pager.isScrollInProgress) choice = choice.settled(pager.settledPage)
                throw e
            }
        }
    }

    private var selectJob: Job? = null

    internal fun rowsChanged(keys: List<String>) {
        val next = choice.rowsChanged(keys)
        if (next == choice) return
        choice = next
        if (pager.currentPage != next.page) pager.requestScrollToPage(next.page)
    }

    internal fun settled(page: Int) {
        choice = choice.settled(page)
    }
}

/**
 * Tavallinen `remember` (ei tallennettava): välilehdelle palatessa, jakson vaihtuessa ja päivän vaihtuessa valittuna on
 * uusin. Sivulla ollessa valinta säilyy, myös käännössä (aktiviteetti käsittelee asennon muutoksen itse).
 */
@Composable
fun rememberPeriodPagerState(keys: List<String>, period: WorkPeriod, todayEpochDay: Long): PeriodPagerState {
    val scope = rememberCoroutineScope()
    val state = remember(period, todayEpochDay) { PeriodPagerState(keys, scope) }
    // Ennen asettelua samassa ruudussa: pager ja lista eivät näytä hetkeäkään vanhaa valintaa uusilla riveillä.
    SideEffect { state.rowsChanged(keys) }
    LaunchedEffect(state) {
        snapshotFlow { state.pager.isScrollInProgress to state.pager.settledPage }
            .collect { (scrolling, page) -> if (!scrolling) state.settled(page) }
    }
    return state
}

/**
 * Valittu jakso ja muut jaksot LazyColumnin alkioina: nuolirivi ja pager yhtenä alkiona ([itemKey]), sen alla otsikko
 * ja muut jaksot uusimmasta vanhimpaan omina alkioinaan, joten pitkä päivälista ei koostu kerralla.
 * [onShowSelected] vierittää sivun nuoliriviin, kun jakso valitaan listasta.
 */
fun LazyListScope.periodPagerItems(
    rows: List<WorkPeriodRow>,
    period: WorkPeriod,
    todayEpochDay: Long,
    state: PeriodPagerState,
    itemKey: Any,
    onShowSelected: suspend () -> Unit,
    /** Tankkauksen kuvien määrä päivärivin kuvamerkkiin. */
    photoCount: (Long) -> Int = { 0 },
    page: @Composable (WorkPeriodRow) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = itemKey) {
        val rowsByKey = remember(rows) { rows.associateBy { it.key } }
        val keys = state.choice.keys
        val shownKey = keys.getOrNull(state.shownPage)
        val shown = shownKey?.let(rowsByKey::get) ?: rows.first()
        val selected = state.choice.selected?.let(rowsByKey::get) ?: shown
        val showOngoing = period != WorkPeriod.DAY
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PeriodStepper(
                title = shown.label,
                subtitle = periodSubtitle(shown, period, todayEpochDay, showOngoing),
                announcement = listOf(selected.label, periodSubtitle(selected, period, todayEpochDay, showOngoing))
                    .filter { it.isNotEmpty() }.joinToString(", "),
                period = period,
                older = olderKey(keys, shown.key),
                newer = newerKey(keys, shown.key),
                onSelect = { state.select(it) },
            )
            HorizontalPager(
                state = state.pager,
                reverseLayout = true,
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.Top,
            ) { index ->
                keys.getOrNull(index)?.let(rowsByKey::get)?.let { page(it) }
            }
        }
    }
    val others = otherPeriodRows(rows, state.choice.selected)
    if (others.isEmpty()) return
    item(key = "$itemKey-others") {
        Text(
            stringResource(othersTitle(period)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        )
    }
    itemsIndexed(others, key = { _, row -> row.key }) { index, row ->
        OtherPeriodRow(
            row = row,
            period = period,
            subtitle = periodSubtitle(row, period, todayEpochDay, showOngoing = true),
            markers = rowMarkers(row, period, photoCount),
            first = index == 0,
            last = index == others.lastIndex,
            onClick = { state.select(row.key, before = onShowSelected) },
        )
    }
}

/** Alarivin osat: viikon päivät, päivän etäisyys tästä päivästä ([daysAgo]) ja näytetäänkö "kesken" ([showOngoing]). */
internal data class SubtitleParts(val range: LongRange?, val daysAgo: Int?, val ongoing: Boolean)

internal fun subtitleParts(row: WorkPeriodRow, period: WorkPeriod, todayEpochDay: Long, showOngoing: Boolean) = SubtitleParts(
    range = if (period == WorkPeriod.WEEK) periodDayRange(row.key) else null,
    daysAgo = if (period == WorkPeriod.DAY) row.date?.let { daysAgo(it, todayEpochDay) } else null,
    ongoing = showOngoing && row.ongoing,
)

private const val RANGE_SEPARATOR = " – "

/**
 * Päivämääräväli näytölle: kumpikin pää pysyy koossa, mutta rivi saa vaihtua viivan kohdalta ("28 Dec 2025 – 3 Jan
 * 2026", "29.12.2025–4.1.2026"). [boundParts] sitoisi viivankin, joten väli sidotaan päittäin.
 */
internal fun boundRange(range: String): String {
    val separator = if (RANGE_SEPARATOR in range) RANGE_SEPARATOR else "–"
    return range.split(separator).joinToString(separator) { boundParts(it) }
}

/**
 * Jakson alarivi: viikolla päivämääräväli, viimeisen viikon päivillä "Tänään", "Eilen" tai "3 päivää sitten" ja
 * keskeneräisellä jaksolla "kesken" ([showOngoing]; päivän sisältö näyttää keskeneräisyyden itse).
 */
@Composable
private fun periodSubtitle(row: WorkPeriodRow, period: WorkPeriod, todayEpochDay: Long, showOngoing: Boolean): String {
    val parts = subtitleParts(row, period, todayEpochDay, showOngoing)
    return listOfNotNull(
        parts.range?.let { boundRange(formatDayRange(it.first, it.last)) },
        parts.daysAgo?.let { days ->
            boundParts(
                when (days) {
                    0 -> stringResource(R.string.today_title)
                    1 -> stringResource(R.string.period_yesterday)
                    else -> pluralStringResource(R.plurals.period_days_ago, days, days)
                },
            )
        },
        if (parts.ongoing) boundParts(stringResource(R.string.work_in_progress)) else null,
    ).joinToString(PART_SEPARATOR)
}

/**
 * Nuolirivi: vanhempi vasemmalla, uudempi oikealla. Puuttuvan suunnan nappi on näkymätön, mutta paikka säilyy. Otsikko
 * seuraa pyyhkäisyä; ruudunlukija kertoo [announcement]in eli valitun jakson vasta, kun pager asettuu.
 */
@Composable
private fun PeriodStepper(
    title: String,
    subtitle: String,
    announcement: String,
    period: WorkPeriod,
    older: String?,
    newer: String?,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton(Icons.Rounded.ChevronLeft, stringResource(stepDescription(period, older = true)), older, onSelect)
        Column(
            Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Nimi rivittyy välilyönnin kohdalta ("Marraskuu" / "2026"), ei kesken sanan.
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        StepButton(Icons.Rounded.ChevronRight, stringResource(stepDescription(period, older = false)), newer, onSelect)
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, target: String?, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = Tap { target?.let(onSelect) },
        enabled = target != null,
        colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.surfaceContainerHigh, contentColor = scheme.onSurface),
        modifier = Modifier
            .size(48.dp)
            .then(if (target == null) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * Muiden jaksojen rivi yhteisessä ryhmässä: nimi ja alarivi vasemmalla, oikealla kilometrit (päivällä myös työaika) ja
 * polttoaineen määrä ja hinta. Napautus valitsee jakson.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherPeriodRow(
    row: WorkPeriodRow,
    period: WorkPeriod,
    subtitle: String,
    markers: RowMarkers,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val top = if (first) GroupCorner else 0.dp
    val bottom = if (last) GroupCorner else 0.dp
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = scheme.surfaceContainerLow,
    ) {
        Column {
            if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.period_show), role = Role.Button, onClick = Tap(onClick))
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Nimi ja luvut rinnakkain, tai luvut nimen alle, jos ne eivät mahdu (iso fontti); nuoli pysyy reunassa.
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.padding(end = 12.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (subtitle.isNotEmpty()) {
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        if (row.missingReadings) WarningLine(stringResource(R.string.work_missing_readings_short))
                        if (markers.any) {
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (markers.photos > 0) PhotoMarker(markers.photos)
                                if (markers.note) NoteMarker()
                            }
                        }
                    }
                    // Täyttää rivin loppuosan (tai oman rivinsä, jos rivittyi), joten luvut ovat aina oikeassa reunassa.
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        if (row.hasWork) {
                            Text(
                                boundParts("${formatWhole(row.km)} ${stringResource(R.string.period_km)}"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                                textAlign = TextAlign.End,
                            )
                            if (period == WorkPeriod.DAY && !row.ongoing) {
                                Text(
                                    boundParts(formatDuration(row.durationSec)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.work_no_entry),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                        listFuelLines(row.fuel).forEach { fuel -> FuelLine(fuel) }
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Määrä lajin värillä ja hinta: "38,0 l · 68,21 €". */
@Composable
private fun FuelLine(fuel: PeriodFuel) {
    val scheme = MaterialTheme.colorScheme
    val cost = formatMoneyByCurrency(fuel.costs)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = scheme.tertiary)) { append(boundParts(formatAmount(fuel.liters, fuel.kind))) }
            if (cost.isNotEmpty()) {
                append(PART_SEPARATOR)
                append(boundParts(cost))
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
}

private fun stepDescription(period: WorkPeriod, older: Boolean): Int = when (period) {
    WorkPeriod.DAY -> if (older) R.string.period_prev_day else R.string.period_next_day
    WorkPeriod.WEEK -> if (older) R.string.period_prev_week else R.string.period_next_week
    WorkPeriod.MONTH -> if (older) R.string.period_prev_month else R.string.period_next_month
    WorkPeriod.YEAR -> if (older) R.string.period_prev_year else R.string.period_next_year
}

private fun othersTitle(period: WorkPeriod): Int = when (period) {
    WorkPeriod.DAY -> R.string.period_others_day
    WorkPeriod.WEEK -> R.string.period_others_week
    WorkPeriod.MONTH -> R.string.period_others_month
    WorkPeriod.YEAR -> R.string.period_others_year
}
