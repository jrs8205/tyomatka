package fi.tyomatka.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.components.AdaptiveButtonRow
import fi.tyomatka.ui.components.AppButtonDefaults
import fi.tyomatka.ui.components.HeroNumber
import fi.tyomatka.ui.components.OdometerSpan
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.forms.FillWord
import fi.tyomatka.ui.theme.OnTodayFilledDark
import fi.tyomatka.ui.theme.TodayFilledDark
import java.time.LocalDate

/**
 * Tänään-kortti: päivän tila autoineen, kilometrit isona, mittarit janana ja työpäivän napit. Kortin pohja on
 * `primaryContainer`, joten kaikki sisältö on `onPrimaryContainer`-väristä (virheväri ei täytä siinä AAA:ta; puuttuvat
 * lukemat kertoo varoitusrivi ikonilla ja lihavoinnilla).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayCard(
    summary: TodaySummary,
    onStartWork: () -> Unit,
    onSwitchCar: () -> Unit,
    onEndWork: () -> Unit,
    onAddFuel: () -> Unit,
    /** Oletusauton käyttövoiman mukaan "Tankkaus", "Lataus" tai "Täyttö"; tuntematon on tankkaus. */
    word: FillWord = FillWord.FILL,
    modifier: Modifier = Modifier,
) {
    val status = summary.status
    val startedAt = status.startedAtEpochSec
    val endedAt = status.endedAtEpochSec
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = onCard),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(shortDay(LocalDate.now().toEpochDay()), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            // Tila ja auton rekisterinumero samalla rivillä; rivi vaihtuu vain " · "-erottimen kohdalta.
            val statusText = when {
                startedAt == null -> stringResource(R.string.today_not_started)
                endedAt != null -> stringResource(R.string.today_ended, formatClock(endedAt), formatDuration(status.workedSec))
                else -> stringResource(R.string.today_ongoing, formatClock(startedAt), formatDuration(status.workedSec))
            }
            Text(todayStatusLine(statusText, summary.statusPlate), style = MaterialTheme.typography.bodyLarge)
            if (startedAt != null) {
                HeroNumber(
                    value = formatWhole(summary.km),
                    unit = stringResource(R.string.period_km),
                    color = onCard,
                    maxSize = 52.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                TodayReadings(summary.readings)
                if (summary.missingReadings) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.work_missing_readings),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            // Napit rinnakkain vain, jos tekstit mahtuvat kokonaisina; muuten allekkain. Lopeta työt on oma täysleveä rivinsä.
            val fuelLabel = stringResource(word.pick(R.string.fuel_add_short, R.string.charge_add_short, R.string.gas_add_short))
            val secondLabel = stringResource(
                when {
                    startedAt == null -> R.string.work_start
                    status.ongoing -> R.string.work_switch_car
                    else -> R.string.work_continue
                }
            )
            AdaptiveButtonRow(
                labels = listOf(fuelLabel, secondLabel),
                hasIcon = listOf(true, false),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 6.dp),
            ) { index, buttonModifier ->
                when {
                    index == 0 -> SecondaryButton(
                        onClick = Tap(onAddFuel),
                        label = fuelLabel,
                        icon = if (word == FillWord.CHARGE) Icons.Rounded.EvStation else Icons.Rounded.LocalGasStation,
                        modifier = buttonModifier,
                    )
                    startedAt == null -> PrimaryButton(onClick = Tap(onStartWork), label = secondLabel, modifier = buttonModifier)
                    status.ongoing -> SecondaryButton(onClick = Tap(onSwitchCar), label = secondLabel, modifier = buttonModifier)
                    else -> SecondaryButton(onClick = Tap(onStartWork), label = secondLabel, modifier = buttonModifier)
                }
            }
            if (status.ongoing) {
                PrimaryButton(onClick = Tap(onEndWork), label = stringResource(R.string.work_end), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Mittarit janana: yhden auton päivänä pelkät lukemat, autonvaihdon jälkeen jokainen auto omalla rivillään
 * rekisterinumeroineen, jotta eri autojen mittarit eivät sekoitu. Auto ilman yhtään lukemaa näyttää pelkän nimen;
 * puute näkyy kortin varoitusrivillä.
 */
@Composable
private fun TodayReadings(readings: List<CarReadings>) {
    val style = MaterialTheme.typography.bodyLarge
    if (readings.size <= 1) {
        val car = readings.firstOrNull() ?: return
        if (car.startKm != null || car.endKm != null) {
            OdometerSpan(car.startKm, car.spanEndKm, ended = !car.open, onColoredSurface = true, style = style, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        readings.forEach { car ->
            val plate = car.plate ?: stringResource(R.string.today_car_without_plate)
            if (car.startKm != null || car.endKm != null) {
                OdometerSpan(
                    car.startKm,
                    car.spanEndKm,
                    ended = !car.open,
                    label = plate,
                    onColoredSurface = true,
                    style = style,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(plate, style = style, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Kortin päätoiminto: vaaleassa teemassa pääväri, tummassa valkoinen, koska tummansininen kortti erottaa sen parhaiten
 * (molemmissa teksti ≥ 7:1).
 */
@Composable
private fun PrimaryButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val colors: ButtonColors = if (dark) {
        ButtonDefaults.buttonColors(containerColor = TodayFilledDark, contentColor = OnTodayFilledDark)
    } else {
        AppButtonDefaults.filledColors()
    }
    Button(onClick = onClick, colors = colors, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Kortin toissijainen nappi: 2 dp:n ääriviiva ja teksti kortin tekstivärillä, jotta se erottuu värikkäältä pohjalta. */
@Composable
private fun SecondaryButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}
