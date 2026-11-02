package fi.tyomatka.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.data.repo.normalizePlate
import fi.tyomatka.ui.history.PlateField

/**
 * Tunnetut rekkarit chippeinä; "Muu" avaa tekstikentän. Ilman tunnettuja rekkareita
 * näkyy vain kenttä.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlateChips(
    value: String,
    onChange: (String) -> Unit,
    knownPlates: List<String>,
    label: String,
    modifier: Modifier = Modifier,
) {
    val selectedKnown = knownPlates.firstOrNull { it == normalizePlate(value) }
    // Tunnettujen lista voi latautua vasta lomakkeen avauduttua, joten tila johdetaan
    // joka kerta; käyttäjän oma valinta (chip tai Muu) voittaa johdetun arvon.
    var manualShowField by remember { mutableStateOf<Boolean?>(null) }
    val showField = knownPlates.isEmpty() || (manualShowField ?: (value.isNotBlank() && selectedKnown == null))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (knownPlates.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                knownPlates.forEach { plate ->
                    FilterChip(
                        selected = !showField && plate == selectedKnown,
                        onClick = Tap {
                            manualShowField = false
                            onChange(plate)
                        },
                        label = { Text(plate) },
                    )
                }
                FilterChip(
                    selected = showField,
                    onClick = Tap {
                        manualShowField = true
                        if (selectedKnown != null) onChange("")
                    },
                    label = { Text(stringResource(R.string.plate_other)) },
                )
            }
        }
        if (showField) {
            // Tunnetut ovat jo chippeinä — kentän omat ehdotukset pois.
            PlateField(value, onChange, label, knownPlates = emptyList(), modifier = Modifier.fillMaxWidth())
        }
    }
}
