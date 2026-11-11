package fi.tyomatka.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.data.repo.GapSuggestion
import fi.tyomatka.data.repo.plateSuggestions
import fi.tyomatka.ui.formatShortDate
import fi.tyomatka.ui.formatWhole
import fi.tyomatka.ui.components.AppTextField
import androidx.compose.material3.SuggestionChipDefaults
import fi.tyomatka.ui.components.ChipBorderWidth
import fi.tyomatka.ui.components.ChipHeight
import androidx.compose.foundation.layout.heightIn

/** Lyhyt suomalainen päivämäärä dialogeihin ja laajennettuun korttiin. */
internal fun shortDay(epochDay: Long): String = formatShortDate(epochDay)

/** Mittarilukemakenttä: vain numeroita. */
@Composable
internal fun OdometerField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    AppTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError || (value.isNotBlank() && value.toLongOrNull() == null),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Desimaalikenttä (litrat, hinnat): numerot ja yksi pilkku, desimaalinäppäimistö. */
@Composable
internal fun DecimalField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    AppTextField(
        value = value,
        onValueChange = { onChange(filterDecimalInput(it)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Rekisterinumerokenttä: isot kirjaimet näppäimistöstä ja syötteestä,
 * alla ehdotusnapit tunnetuista rekkareista (ks. [plateSuggestions]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlateField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    knownPlates: List<String>,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Column(modifier) {
        AppTextField(
            value = value,
            onValueChange = { raw ->
                onChange(raw.uppercase().filter { it.isLetterOrDigit() || it == '-' })
            },
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = plateSuggestions(value, knownPlates)
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { plate ->
                    SuggestionChip(
                        onClick = Tap { onChange(plate) },
                        label = { Text(plate) },
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline,
                            borderWidth = ChipBorderWidth,
                        ),
                        modifier = Modifier.heightIn(min = ChipHeight),
                    )
                }
            }
        }
    }
}

/**
 * Aloituslukeman kenttä + huomautus, jos syötetty lukema ylittää edellisen
 * kirjatun: myöhässä aloitettaessa alkupään työajot tulevat mukaan vain
 * käyttämällä edellistä lukemaa. Pienempää lukemaa ei kommentoida (auto
 * on voinut vaihtua). Käytössä Ajo-näkymän aloitusdialogissa.
 */
@Composable
internal fun StartReadingField(
    km: String,
    onChange: (String) -> Unit,
    prefillKm: Long?,
) {
    OdometerField(
        km,
        onChange,
        stringResource(R.string.work_odometer_optional),
        modifier = Modifier.fillMaxWidth(),
    )
    val value = km.toLongOrNull()
    if (value != null && prefillKm != null && value > prefillKm) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.work_start_diff_note, formatWhole(value - prefillKm), formatWhole(prefillKm)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        TextButton(onClick = Tap { onChange(prefillKm.toString()) }) {
            Text(stringResource(R.string.work_use_prev_reading))
        }
    }
}

/**
 * Kirjaamattoman välin vahvistus: erotus esitäytettynä puuttuville
 * työpäiville; määriä voi muokata (yksityisajot voi jättää pois).
 * Käytössä sekä Työmatka-sivulla että ajonaloituksen kirjauksessa.
 */
@Composable
internal fun GapDialog(
    suggestion: GapSuggestion,
    onConfirm: (Map<Long, Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val edits = remember(suggestion) {
        mutableStateMapOf<Long, String>().apply {
            suggestion.perDay.forEach { (day, km) -> put(day, km.toString()) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.work_gap_title)) },
        text = {
            // Monta puuttuvaa päivää voi ylittää näytön — rullattava.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        if (suggestion.previousWasEnd) {
                            R.string.work_gap_prev_end
                        } else {
                            R.string.work_gap_prev_start
                        },
                        formatWhole(suggestion.previousKm),
                        formatShortDate(suggestion.previousEpochDay, capitalize = false),
                    )
                )
                Text(stringResource(R.string.work_gap_new, formatWhole(suggestion.newKm)))
                Text(
                    stringResource(R.string.work_gap_diff, formatWhole(suggestion.diffKm)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.work_gap_days_note))
                Spacer(Modifier.height(8.dp))
                suggestion.perDay.keys.sorted().forEach { day ->
                    OdometerField(
                        edits[day] ?: "",
                        { edits[day] = it },
                        stringResource(R.string.work_gap_day_km, shortDay(day)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = Tap {
                    onConfirm(edits.mapValues { (_, text) -> text.toLongOrNull() ?: 0L })
                }
            ) {
                Text(stringResource(R.string.work_gap_record))
            }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.work_gap_skip)) }
        },
    )
}
