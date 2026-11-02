package fi.tyomatka.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.formatDuration
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.history.forms.FillWord
import java.time.LocalDate

/** Tänään-kortti: päivän tila autoineen, kilometrit ja mittarit sekä työpäivän napit. */
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
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleMedium)
                Text(shortDay(LocalDate.now().toEpochDay()), style = MaterialTheme.typography.bodyMedium)
            }
            // Tila ja auton rekisterinumero samalla rivillä; rivi vaihtuu vain " · "-erottimen kohdalta.
            val statusText = when {
                startedAt == null -> stringResource(R.string.today_not_started)
                endedAt != null -> stringResource(R.string.today_ended, formatClock(endedAt), formatDuration(status.workedSec))
                else -> stringResource(R.string.today_ongoing, formatClock(startedAt), formatDuration(status.workedSec))
            }
            Text(todayStatusLine(statusText, summary.statusPlate), style = MaterialTheme.typography.bodyLarge)
            if (startedAt != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DistanceIcon(Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${formatWhole(summary.km)} km",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val readings = summary.readings
                if (readings.size <= 1) {
                    // Yhden auton rekisterinumero on tilarivillä, joten tässä ovat vain lukemat.
                    val first = readings.firstOrNull()?.startKm
                    val last = readings.firstOrNull()?.endKm
                    val odometer = when {
                        first != null && last != null ->
                            stringResource(R.string.today_odometer_range, formatWhole(first), formatWhole(last))
                        first != null -> stringResource(R.string.today_odometer_start, formatWhole(first))
                        else -> null
                    }
                    if (odometer != null) {
                        // Sama koko kuin tilarivillä, jotta nuoli ja lukemat erottuvat myös päivänvalossa.
                        Text(odometer, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    // Autonvaihdon jälkeen lukemat autoittain omilla riveillään, jotta eri autojen mittarit eivät sekoitu.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        readings.forEach { car ->
                            val plate = car.plate ?: stringResource(R.string.today_car_without_plate)
                            val start = car.startKm
                            val end = car.endKm
                            Text(
                                when {
                                    start != null && end != null ->
                                        stringResource(R.string.today_car_odometer_range, plate, formatWhole(start), formatWhole(end))
                                    start != null -> stringResource(R.string.today_car_odometer_start, plate, formatWhole(start))
                                    else -> plate
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                if (summary.missingReadings) {
                    // Virheväri ei täytä AAA-kontrastia sinisellä pohjalla; ikoni + lihavointi kantaa merkityksen.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.work_missing_readings),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            // Napit rivittyvät kapealla näytöllä ja suurella fontilla; Lopeta työt on oma täysleveä rivinsä.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    onClick = Tap(onAddFuel),
                    label = stringResource(word.pick(R.string.fuel_add_short, R.string.charge_add_short, R.string.gas_add_short)),
                    icon = if (word == FillWord.CHARGE) Icons.Filled.EvStation else Icons.Filled.LocalGasStation,
                )
                when {
                    startedAt == null -> Button(onClick = Tap(onStartWork)) { Text(stringResource(R.string.work_start)) }
                    status.ongoing -> SecondaryButton(onClick = Tap(onSwitchCar), label = stringResource(R.string.work_switch_car))
                    else -> SecondaryButton(onClick = Tap(onStartWork), label = stringResource(R.string.work_continue))
                }
            }
            if (status.ongoing) {
                Button(onClick = Tap(onEndWork), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.work_end))
                }
            }
        }
    }
}

/** Kortin toissijainen nappi: ääriviiva ja teksti kortin tekstivärillä, jotta se erottuu värikkäältä pohjalta. */
@Composable
private fun SecondaryButton(onClick: () -> Unit, label: String, icon: ImageVector? = null) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.5.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label)
    }
}
