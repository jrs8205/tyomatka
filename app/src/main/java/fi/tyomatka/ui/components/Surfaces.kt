package fi.tyomatka.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Kartan päällä kelluvat kortit `surfaceContainerLow`-pohjalla: kortin oletuspohja (`surfaceContainerHighest`) ei anna
 * pää- ja virhevärille AAA-kontrastia.
 */
@Composable
fun mapCardColors(): CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * Kellonvalitsin: tunti- ja minuuttiruudut käyttävät teeman small-muotoa, joka on sovelluksessa pilleri chipejä varten.
 * Ruudut pidetään suorakulmaisina pyöristetyin kulmin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePicker(state: TimePickerState) {
    MaterialTheme(shapes = MaterialTheme.shapes.copy(small = RoundedCornerShape(14.dp))) {
        TimePicker(state = state)
    }
}
