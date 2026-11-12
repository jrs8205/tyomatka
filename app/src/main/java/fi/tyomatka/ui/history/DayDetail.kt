package fi.tyomatka.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.FuelInterval
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.components.AppButtonDefaults
import fi.tyomatka.ui.components.HeroNumber
import fi.tyomatka.ui.components.OdometerSpan
import fi.tyomatka.ui.components.OdometerState
import fi.tyomatka.ui.components.odometerState
import fi.tyomatka.ui.formatAmount
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatConsumption
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatMoney
import fi.tyomatka.ui.formatMoneyByCurrency
import fi.tyomatka.ui.formatUnitPrice
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.forms.FillWord
import fi.tyomatka.ui.history.forms.fillWord
import fi.tyomatka.ui.history.forms.fuelKindName
import fi.tyomatka.ui.components.ChipHeight

private val GroupCorner = 24.dp

/** Päivän lajiyhteenvedot ("Diesel 52,0 l · 93,08 €") vain lajeille, joilla on päivänä useampi tankkaus. */
fun kindSummaries(fillups: List<FuelFillupEntity>, fuel: PeriodFuels?): List<PeriodFuel> =
    fuel?.byKind.orEmpty().filter { summary -> fillups.count { it.kind == summary.kind } > 1 }

/** Päivän etäisyys tästä päivästä nuolirivin alarivillä: 0–6 päivää, muuten null (päivämäärä on jo otsikossa). */
fun daysAgo(date: Long, todayEpochDay: Long): Int? = (todayEpochDay - date).takeIf { it in 0..6 }?.toInt()

/** Päivän chipit: lukemien puute, Korjaa lukemia (vain kun päivällä on työtä) ja Lisätiedot (kun päivällä on teksti). */
enum class DayAction { MISSING_READINGS, FIX, NOTE }

fun dayActions(row: WorkPeriodRow): List<DayAction> = buildList {
    if (row.missingReadings) add(DayAction.MISSING_READINGS)
    if (row.hasWork) add(DayAction.FIX)
    if (row.hasNote) add(DayAction.NOTE)
}

/** Ajorivin tulkinta: takautuva kirjaus, kilometrit (vain kelvollisista lukemista) ja puute samoin kuin mittarijanassa. */
data class SegmentDisplay(val retro: Boolean, val km: Long?, val problem: Boolean)

fun segmentDisplay(seg: WorkSegmentEntity): SegmentDisplay {
    val ended = seg.endEpochSec != null
    val start = seg.odometerStartKm
    val end = seg.odometerEndKm
    val state = odometerState(start, end, ended)
    return SegmentDisplay(
        retro = ended && seg.startEpochSec == seg.endEpochSec,
        km = if (state == OdometerState.COMPLETE && start != null && end != null) end - start else null,
        problem = state == OdometerState.MISSING || state == OdometerState.INVALID,
    )
}

/** Tankkausten otsikon sana: lataukset tai täytöt, jos kaikki ovat sitä lajia, muuten tankkaukset. */
fun fillupsTitleWord(fillups: List<FuelFillupEntity>): FillWord =
    fillups.map { fillWord(it.kind) }.distinct().singleOrNull() ?: FillWord.FILL

/** Päivän lisäysnapin teksti käyttövoiman mukaan: tankkaus, lataus tai täyttö. */
fun dayAddFuelRes(word: FillWord): Int =
    word.pick(R.string.fuel_add_for_day, R.string.charge_add_for_day, R.string.gas_add_for_day)

/**
 * Valitun päivän sisältö: kilometrit isona (tai "Ei työkirjausta") ja korjauksen chipit, päivän tankkaukset
 * (napautus muokkaa), tankkaus jälkikäteen sekä päivän ajot aina näkyvissä mittarijanoineen.
 */
@Composable
fun DayDetail(
    row: WorkPeriodRow,
    segments: List<WorkSegmentEntity>,
    intervalsByFillup: Map<Long, FuelInterval>,
    photosByFillup: Map<Long, List<FuelPhotoEntity>>,
    onEditFillup: (FuelFillupEntity) -> Unit,
    /** Tankkaus jälkikäteen päivälle (epochDay). */
    onAddFuel: (Long) -> Unit,
    /** Uuden kirjauksen sana: sama kuin Tänään-kortin napissa. */
    addWord: FillWord,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = row.date ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DayHero(row, onFix)
        if (row.fillups.isNotEmpty()) {
            SectionTitle(stringResource(fillupsTitleWord(row.fillups).pick(R.string.day_fillups_title, R.string.day_charges_title, R.string.day_gas_title)))
            Group {
                row.fillups.forEachIndexed { index, fillup ->
                    if (index > 0) GroupDivider()
                    FillupRow(
                        fillup,
                        intervalsByFillup[fillup.id],
                        photosByFillup[fillup.id]?.size ?: 0,
                        hasNote = fillup.note.isNotBlank(),
                    ) { onEditFillup(fillup) }
                }
                kindSummaries(row.fillups, row.fuel).forEach { fuel ->
                    GroupDivider()
                    Text(
                        stringResource(
                            R.string.fuel_tanked_kind,
                            fuelKindName(fuel.kind),
                            boundParts(formatAmount(fuel.liters, fuel.kind)),
                            boundParts(formatMoneyByCurrency(fuel.costs)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Button(
            onClick = Tap { onAddFuel(date) },
            colors = AppButtonDefaults.tonalColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(dayAddFuelRes(addWord)), textAlign = TextAlign.Center)
        }
        if (segments.isNotEmpty()) {
            SectionTitle(stringResource(R.string.day_drives_title))
            Group {
                segments.forEachIndexed { index, seg ->
                    if (index > 0) GroupDivider()
                    SegmentRow(seg)
                }
            }
        }
    }
}

/**
 * Pääluku ja sen alla työaika tai "kesken"; ilman työkirjausta "Ei työkirjausta". Chipit: lukemien puute (avaa
 * korjauksen), Korjaa lukemia (vain kun päivällä on työtä) ja Lisätiedot, jos päivällä on teksti.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHero(row: WorkPeriodRow, onFix: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Tile {
        if (row.hasWork) {
            HeroNumber(
                value = formatWhole(row.km),
                unit = stringResource(R.string.period_km),
                color = scheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (row.ongoing) stringResource(R.string.work_in_progress) else stringResource(R.string.work_drive_time, boundParts(formatDuration(row.durationSec))),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                stringResource(R.string.work_no_entry),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        val actions = dayActions(row)
        if (actions.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                actions.forEach { action ->
                    when (action) {
                        DayAction.MISSING_READINGS ->
                            DayChip(stringResource(R.string.work_missing_readings_short), Icons.Rounded.Warning, scheme.error, onFix)
                        DayAction.FIX -> DayChip(stringResource(R.string.work_fix), Icons.Outlined.Edit, scheme.primary, onFix)
                        DayAction.NOTE -> DayChip(
                            stringResource(R.string.note_marker),
                            Icons.Outlined.Notes,
                            scheme.onSurface,
                            onFix,
                            border = scheme.outline,
                            clickLabel = stringResource(R.string.note_marker_open),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ääriviivallinen toimintochip: teksti, ikoni ja reuna samalla värillä (ellei [border] ole erikseen). [clickLabel] on
 * ruudunlukijan toiminnon nimi, kun pelkkä teksti ei kerro sitä ("Avaa päivän lisätiedot").
 */
@Composable
private fun DayChip(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    border: Color = color,
    clickLabel: String? = null,
) {
    val tap = Tap(onClick)
    AssistChip(
        onClick = tap,
        modifier = Modifier
            .heightIn(min = ChipHeight)
            .then(if (clickLabel != null) Modifier.semantics { onClick(label = clickLabel) { tap(); true } } else Modifier),
        label = { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color, leadingIconContentColor = color),
        border = BorderStroke(1.5.dp, border),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
    )
}

/** Rivit yhdessä pyöristetyssä ryhmässä erottimin. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GroupCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Rivin alussa ikoni pyöreällä pohjalla. */
@Composable
private fun LeadIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Yksi tankkaus: määrä ja summa, yksikköhinta, laji (paitsi diesel), rekisterinumero, mittarilukema ja vajaa,
 * kulutus edellisestä täydestä, kuvien määrä ja Lisätiedot-merkki. Koko rivi avaa muokkauksen (myös poisto).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillupRow(fillup: FuelFillupEntity, interval: FuelInterval?, photoCount: Int, hasNote: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(fillWord(fillup.kind).pick(R.string.fuel_edit, R.string.fuel_edit_charge, R.string.fuel_edit_gas)),
                onClick = Tap(onClick),
            )
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadIcon(
            when (fillup.kind) {
                FuelKind.ELECTRIC -> Icons.Rounded.EvStation
                FuelKind.ADBLUE -> Icons.Rounded.WaterDrop
                else -> Icons.Rounded.LocalGasStation
            },
            scheme.tertiary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                boundParts(formatAmount(fillup.liters, fillup.kind), formatMoney(fillup.totalCost, fillup.currency)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                boundParts(
                    formatUnitPrice(fillup.pricePerLiter, fillup.currency, fillup.kind.unit),
                    fuelKindName(fillup.kind).takeIf { fillup.kind != FuelKind.DIESEL },
                    fillup.licensePlate,
                    "${formatWhole(fillup.odometerKm)} km",
                    stringResource(R.string.fuel_partial).takeIf { !fillup.fullTank },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            interval?.let {
                Text(
                    stringResource(R.string.fuel_since_prev, boundParts(formatConsumption(it.consumption, it.kind))),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
            if (photoCount > 0 || hasNote) {
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (photoCount > 0) PhotoMarker(photoCount)
                    // Kirjauksen lisätiedot: merkki riittää, koko rivi avaa lomakkeen, jossa teksti näkyy.
                    if (hasNote) NoteMarker()
                }
            }
        }
        // Kynä kertoo, että tankkausta voi muokata; koko rivi on napautettava.
        Icon(
            Icons.Outlined.Edit,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.padding(top = 8.dp).size(24.dp),
        )
    }
}

/**
 * Päivän työjakso: kellonajat tai takautuva kirjaus ja rekisterinumero, mittarijana ja kilometrit. Puuttuva tai
 * virheellinen lukema näkyy janassa virhevärillä ja sanalla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentRow(seg: WorkSegmentEntity) {
    val scheme = MaterialTheme.colorScheme
    val (retro, km, problem) = segmentDisplay(seg)
    // Kellonajat pysyvät koossa; "Takautuva kirjaus" saa rivittyä sanojen välistä.
    val title = if (retro) {
        stringResource(R.string.work_retro_entry).replaceFirstChar { it.uppercaseChar() }
    } else {
        boundParts("${formatClock(seg.startEpochSec)}–${seg.endEpochSec?.let(::formatClock) ?: "…"}")
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadIcon(if (retro) Icons.Rounded.History else Icons.Rounded.Schedule, if (problem) scheme.error else scheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                seg.licensePlate?.let { Text(boundParts(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            OdometerSpan(seg.odometerStartKm, seg.odometerEndKm, ended = seg.endEpochSec != null, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            km?.let {
                Text(
                    boundParts("${formatWhole(it)} ${stringResource(R.string.period_km)}"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }
        }
    }
}
