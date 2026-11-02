package fi.tyomatka.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.settings.SourcesContent

/**
 * Yhdellä rivillä merkintä vie noin 190 dp, joten kapealla näytöllä se jäisi nopeusmittarin tai suuren fontin
 * Järjestys-napin alle. Kahdella rivillä se vie noin 100 dp ja mahtuu niiden viereen vielä 320 dp:n näytöllä.
 */
private const val ONE_LINE_MIN_WIDTH_DP = 400

internal fun mapCreditText(windowWidthDp: Int): String =
    if (windowWidthDp < ONE_LINE_MIN_WIDTH_DP) "© OpenMapTiles\n© OpenStreetMap" else "© OpenMapTiles © OpenStreetMap"

/**
 * Näkyvä lähdemerkintä kartan kulmassa: OSM ja OpenMapTiles edellyttävät merkintää ilman vuorovaikutusta. Kartta on aina
 * vaalea, joten värit eivät seuraa teemaa. Napautus avaa kaikki lähdemerkinnät.
 */
@Composable
fun MapCredit(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.map_attribution_open)
    val text = mapCreditText(LocalConfiguration.current.screenWidthDp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = Tap { open = true })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomStart,
    ) {
        // Kiinteä koko: suuri fontti veisi merkinnän nopeusmittarin ja nappien alle.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
            Text(
                text,
                fontSize = 11.sp,
                // Teeman oletusriviväli nostaisi kaksirivisen merkinnän sijaintinapin alle.
                lineHeight = 14.sp,
                color = Color.Black,
                maxLines = 2,
                softWrap = false,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.map_attribution_title)) },
            text = { SourcesContent() },
            confirmButton = { TextButton(onClick = Tap { open = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
