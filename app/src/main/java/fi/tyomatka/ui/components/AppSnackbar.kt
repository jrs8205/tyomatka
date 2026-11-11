package fi.tyomatka.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyomatka.R

/**
 * Ilmoitus käänteisin värein: toiminto lihavoituna `inversePrimary`-värillä ja sulkemisnappi, jolla on oma
 * ruudunlukijateksti. Käytetään `SnackbarHost`in sisältönä.
 */
@Composable
fun AppSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    Snackbar(
        modifier = Modifier.padding(12.dp),
        action = visuals.actionLabel?.let { label ->
            {
                TextButton(colors = AppButtonDefaults.snackbarActionColors(), onClick = { data.performAction() }) {
                    Text(label, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissAction = if (visuals.withDismissAction) {
            {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.notice_dismiss),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        } else {
            null
        },
    ) { Text(visuals.message) }
}
