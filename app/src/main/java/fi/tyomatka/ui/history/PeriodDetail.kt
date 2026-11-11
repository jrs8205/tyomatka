package fi.tyomatka.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.repo.CarPeriodFuel
import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.components.HeroNumber
import fi.tyomatka.ui.components.buttonsPerRow
import fi.tyomatka.ui.formatAmount
import fi.tyomatka.ui.formatConsumption
import fi.tyomatka.ui.formatCostPerKm
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatMoneyByCurrency
import fi.tyomatka.ui.formatPercent
import fi.tyomatka.ui.formatUnitPrice
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.forms.carPeriodNote
import fi.tyomatka.ui.history.forms.fillWord
import fi.tyomatka.ui.history.forms.fuelKindName

/** Jakson polttoaineruudut: jokainen laji omana parinaan (määrä ja kustannus), AdBlue omana ryhmänään. */
data class FuelTiles(val main: List<PeriodFuel>, val adBlue: PeriodFuel?)

fun periodFuelTiles(fuel: PeriodFuels?): FuelTiles {
    val kinds = fuel?.byKind.orEmpty()
    return FuelTiles(kinds.filter { it.kind != FuelKind.ADBLUE }, kinds.firstOrNull { it.kind == FuelKind.ADBLUE })
}

private val TilePadding = 14.dp
private val TileGap = 8.dp
private val HeaderIcon = 20.dp
private val HeaderGap = 6.dp

/**
 * Viikon, kuukauden tai vuoden sisältö: kilometrit isona, polttoaineet lajeittain kahtena ruutuna (määrä ja kulutus;
 * kustannus, keskihinta ja hinta kilometriltä), työaika ja työpäivät, AdBlue kaikkine tietoineen sekä autokohtaiset
 * kulutukset. Mikään nykyisen jaksokortin tieto ei puutu.
 */
@Composable
fun PeriodDetail(row: WorkPeriodRow, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tiles = periodFuelTiles(row.fuel)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TileGap)) {
        // Jaksolla voi olla pelkkiä tankkauksia: silloin ei nollia vaan "Ei työkirjausta", ja tankkaukset näkyvät.
        Tile {
            if (row.hasWork) {
                HeroNumber(
                    value = formatWhole(row.km),
                    unit = stringResource(R.string.period_km),
                    color = scheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.period_km_driven),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
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
            if (row.missingReadings) {
                WarningLine(stringResource(R.string.work_missing_readings), Modifier.padding(top = 8.dp))
            }
        }
        tiles.main.forEach { fuel -> FuelPair(fuel) }
        if (row.hasWork) {
            FactsTile(
                buildList {
                    add(stringResource(R.string.period_work_time) to formatDuration(row.durationSec) + if (row.ongoing) " +" else "")
                    add(stringResource(R.string.period_work_days) to row.workDays.toString())
                },
            )
        }
        tiles.adBlue?.let { adBlue -> AdBlueTile(adBlue, row.fuel?.adBluePercent) }
        if (row.carFuel.isNotEmpty()) CarsTile(row.carFuel)
    }
}

/** Lajin kaksi ruutua rinnakkain, tai allekkain, jos isot luvut eivät mahdu puolikkaisiin. */
@Composable
private fun FuelPair(fuel: PeriodFuel) {
    val scheme = MaterialTheme.colorScheme
    val currency = fuel.currency
    val amount = formatAmount(fuel.liters, fuel.kind)
    val cost = formatMoneyByCurrency(fuel.costs)
    val big = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
    val header = boundParts(
        fuelKindName(fuel.kind),
        stringResource(fillWord(fuel.kind).pick(R.string.fuel_stat_tanked, R.string.fuel_stat_charged, R.string.fuel_stat_filled)).lowercase(),
    )
    val consumption = fuel.consumption?.let { formatConsumption(it, fuel.kind) } ?: "–"
    val avg = fuel.avgPricePerLiter
    val avgPrice = if (avg != null && currency != null) formatUnitPrice(avg, currency, fuel.kind.unit) else "–"
    val costPerKm = formatCostPerKm(fuel.costPerKm, currency)
    val consumptionLabel = stringResource(R.string.fuel_stat_per_work_km)
    val costLabel = stringResource(R.string.fuel_stat_cost)
    val avgLabel = stringResource(R.string.fuel_stat_avg_price)
    val costPerKmLabel = stringResource(R.string.fuel_stat_cost_per_km)
    val amountTile: @Composable (Modifier) -> Unit = { modifier ->
        Tile(modifier) {
            TileHeader(
                if (fuel.kind == FuelKind.ELECTRIC) Icons.Rounded.EvStation else Icons.Rounded.LocalGasStation,
                scheme.tertiary,
                header,
            )
            Text(boundParts(amount), style = big, color = scheme.tertiary)
            LabeledValue(consumptionLabel, consumption)
        }
    }
    val costTile: @Composable (Modifier) -> Unit = { modifier ->
        Tile(modifier) {
            TileHeader(Icons.Rounded.Payments, scheme.onSurfaceVariant, costLabel)
            Text(boundParts(cost), style = big, color = scheme.onSurface)
            LabeledValue(avgLabel, avgPrice)
            LabeledValue(costPerKmLabel, costPerKm)
        }
    }
    val label = MaterialTheme.typography.bodyMedium
    val value = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
        // Rinnakkain vain, jos jokainen sana ja luku mahtuu puolikkaaseen kokonaisena; otsikossa myös ikonin tila.
        SideBySide(
            texts = listOf(
                Measured(header, label, extra = HeaderIcon + HeaderGap),
                Measured(costLabel, label, extra = HeaderIcon + HeaderGap),
                Measured(boundParts(amount), big),
                Measured(boundParts(cost), big),
                Measured(consumptionLabel, label),
                Measured(avgLabel, label),
                Measured(costPerKmLabel, label),
                Measured(boundParts(consumption), value),
                Measured(boundParts(avgPrice), value),
                Measured(boundParts(costPerKm), value),
            ),
            first = amountTile,
            second = costTile,
        )
        if (fuel.missingPrices) {
            Text(
                stringResource(R.string.fuel_missing_prices),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Ruudun teksti mitattavaksi: [extra] on samalla rivillä oleva muu tila (otsikon ikoni). */
private class Measured(val text: String, val style: TextStyle, val extra: Dp = 0.dp)

/**
 * Pisin kohta, jota rivitys ei voi jakaa: tavallisen välilyönnin kohdalta rivi saa vaihtua, sitovan (U+00A0) ei.
 * Tämän leveämpi tila katkaisisi sanan tai luvun.
 */
internal fun unbreakableParts(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

/**
 * Kaksi ruutua rinnakkain, jos jokainen ruutujen sana ja luku mahtuu puolikkaaseen kokonaisena (iso fontti ja pitkät
 * ruotsin sanat); muuten allekkain.
 */
@Composable
private fun SideBySide(
    texts: List<Measured>,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = with(density) {
            val widest = texts.maxOf { item ->
                unbreakableParts(item.text).maxOfOrNull { measurer.measure(it, item.style, maxLines = 1, softWrap = false).size.width }
                    ?.plus(item.extra.roundToPx()) ?: 0
            }
            val width = widest + (TilePadding * 2).roundToPx()
            buttonsPerRow(listOf(width, width), constraints.maxWidth, TileGap.roundToPx())
        }
        if (perRow == 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

/** AdBlue kaikkine tietoineen: määrä, kustannus, keskihinta, osuus dieselistä ja kulutus työkilometreiltä. */
@Composable
private fun AdBlueTile(fuel: PeriodFuel, percentOfDiesel: Double?) {
    val currency = fuel.currency
    val avg = fuel.avgPricePerLiter
    Tile {
        TileHeader(Icons.Rounded.WaterDrop, MaterialTheme.colorScheme.tertiary, fuelKindName(FuelKind.ADBLUE))
        FactsGrid(
            listOf(
                stringResource(fillWord(fuel.kind).pick(R.string.fuel_stat_tanked, R.string.fuel_stat_charged, R.string.fuel_stat_filled)) to
                    formatAmount(fuel.liters, fuel.kind),
                stringResource(R.string.fuel_stat_cost) to formatMoneyByCurrency(fuel.costs),
                stringResource(R.string.fuel_stat_avg_price) to
                    if (avg != null && currency != null) formatUnitPrice(avg, currency, fuel.kind.unit) else "–",
                stringResource(R.string.fuel_stat_percent_of_diesel) to (percentOfDiesel?.let(::formatPercent) ?: "–"),
                stringResource(R.string.fuel_stat_per_work_km) to (fuel.consumption?.let { formatConsumption(it, fuel.kind) } ?: "–"),
            ),
        )
        if (fuel.missingPrices) {
            Text(stringResource(R.string.fuel_missing_prices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactsTile(facts: List<Pair<String, String>>) {
    Tile { FactsGrid(facts) }
}

/**
 * Nimetyt luvut kahdessa sarakkeessa, tai allekkain, jos jokin luku tai nimen sana ei mahdu puolikkaaseen kokonaisena
 * (nimi saa rivittyä sanojen välistä).
 */
@Composable
private fun FactsGrid(facts: List<Pair<String, String>>) {
    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = facts.maxOfOrNull { (label, value) ->
            maxOf(
                unbreakableParts(label).maxOfOrNull { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false).size.width } ?: 0,
                measurer.measure(boundParts(value), valueStyle, maxLines = 1, softWrap = false).size.width,
            )
        } ?: 0
        val columns = with(density) { buttonsPerRow(listOf(widest, widest), constraints.maxWidth, TileGap.roundToPx()) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            facts.chunked(columns).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(boundParts(value), style = valueStyle)
                        }
                    }
                    if (pair.size < columns) Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}

/** Autot jaksolla: rekisterinumero ja laji, tankkausten määrä, kulutus ja hinta kilometriltä (AdBlue: osuus dieselistä). */
@Composable
private fun CarsTile(cars: List<CarPeriodFuel>) {
    Tile {
        Text(
            stringResource(carPeriodNote(cars.map { it.kind })),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        cars.forEachIndexed { index, car ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CarFuelLine(car)
        }
    }
}

/**
 * Auton rivi: rekisterinumero, laji ja tankkausmäärä vasemmalla, kulutus ja hinta kilometriltä oikealla. Oikea puoli
 * siirtyy omalle rivilleen, jos sen pisin sana ei mahdu nimen viereen (iso fontti, ruotsin "tankningsintervall").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarFuelLine(car: CarPeriodFuel) {
    val title = if (car.kind == FuelKind.DIESEL) boundParts(car.plate) else boundParts(car.plate, fuelKindName(car.kind))
    val percent = car.percentOfDiesel
    // Luvut sidotaan, mutta lause saa rivittyä sanojen välistä.
    val detail = when {
        car.kind == FuelKind.ADBLUE && percent != null ->
            stringResource(R.string.fuel_percent_of_diesel, boundParts(formatPercent(percent)))
        car.consumption == null ->
            stringResource(fillWord(car.kind).pick(R.string.fuel_no_interval, R.string.fuel_no_charge_interval, R.string.fuel_no_gas_interval))
        car.kind == FuelKind.ADBLUE -> "–"
        else -> boundParts(formatCostPerKm(car.costPerKm, car.currency))
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                pluralStringResource(
                    fillWord(car.kind).pick(R.plurals.fuel_fillup_count, R.plurals.fuel_charge_count, R.plurals.fuel_gas_count),
                    car.fillupCount,
                    car.fillupCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                boundParts(car.consumption?.let { formatConsumption(it, car.kind) } ?: "–"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** Pyöristetty ruutu `surfaceContainerLow`-pohjalla. */
@Composable
internal fun Tile(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TilePadding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun TileHeader(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HeaderGap)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(HeaderIcon))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(boundParts(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Varoitus ikonilla ja tekstillä virhevärillä: merkitys ei ole pelkän värin varassa. */
@Composable
internal fun WarningLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
    }
}
