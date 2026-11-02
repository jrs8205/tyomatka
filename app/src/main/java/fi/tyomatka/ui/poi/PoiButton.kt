package fi.tyomatka.ui.poi

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

/** Kohteet-nappi keskitysnapin yläpuolella: korostettu ja merkitty päällä olevien luokkien määrällä. */
@Composable
fun PoiButton(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BadgedBox(
        badge = { if (activeCount > 0) Badge { Text("$activeCount") } },
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = Tap(onClick),
            containerColor = if (activeCount > 0) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
        ) {
            Icon(Icons.Filled.LocalGasStation, contentDescription = stringResource(R.string.poi_button))
        }
    }
}
