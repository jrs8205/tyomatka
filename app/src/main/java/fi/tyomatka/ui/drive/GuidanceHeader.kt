package fi.tyomatka.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stadiamaps.ferrostar.composeui.views.components.maneuver.ManeuverImage
import fi.tyomatka.R
import uniffi.ferrostar.VisualInstructionContent

/** Googlen navigointinäkymän vihreä otsikko: valkoinen teksti 7,2:1-kontrastilla (AAA). */
val GuidanceGreen = Color(0xFF0D652D)

/** Otsikon alarivi (Sitten ja kaistat). */
val GuidanceGreenDark = Color(0xFF0A4F23)

/**
 * Käännösotsikko kiinni yläpalkin reunassa: nuoli, matka ja kadunnimi; alla kaistat, kun niitä on,
 * ja Sitten-rivi, kun seuraava käännös tulee heti perään. Reitiltä poikettaessa ydin ei anna ohjetta, mutta otsikko
 * kertoo silti uuden reitin hausta. Tekstit rivittyvät sanarajoista; mitään ei katkaista kesken sanan.
 */
@Composable
fun GuidanceHeader(ui: GuidanceUi, modifier: Modifier = Modifier) {
    val maneuver = ui.maneuver
    if (maneuver == null && !ui.offRoute) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GuidanceGreen,
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (ui.offRoute || maneuver == null) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    ManeuverImage(maneuver, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    if (ui.offRoute) {
                        Text(
                            stringResource(R.string.guidance_rerouting),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        ui.distanceText?.let {
                            Text(it, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        ui.primaryText?.let {
                            Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 3)
                        }
                        ui.secondaryText?.let {
                            Text(it, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        }
                    }
                }
            }
            if (!ui.offRoute && ui.lanes.isNotEmpty()) {
                LaneRow(ui.lanes)
            }
            if (!ui.offRoute && ui.thenManeuver != null) {
                ThenRow(ui.thenManeuver)
            }
        }
    }
}

/**
 * Kaistat kuvakkeina (merkit olivat liian pieniä ja vaikeita hahmottaa): jokainen kaista on oma
 * ryhmänsä, reitin mukainen kaista valkoisena vaalealla taustalla, muut himmeinä.
 */
@Composable
private fun LaneRow(lanes: List<LaneUi>) {
    val description = stringResource(R.string.guidance_lanes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LANE_ROW_HEIGHT)
            .background(GuidanceGreenDark)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            val tint = if (lane.active) Color.White else Color.White.copy(alpha = 0.45f)
            Row(
                modifier = Modifier
                    .then(if (lane.active) Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp)) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lane.directions.forEach { direction ->
                    val icon = laneIcon(direction)
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(LANE_ICON_SIZE))
                    } else {
                        Text("•", color = tint, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private val LANE_ROW_HEIGHT = 44.dp
private val LANE_ICON_SIZE = 32.dp

@Composable
private fun ThenRow(then: VisualInstructionContent) {
    Row(
        modifier = Modifier.fillMaxWidth().background(GuidanceGreenDark).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.guidance_then), color = Color.White, style = MaterialTheme.typography.titleMedium)
        // Ferrostarin nuoli on kiinteästi 64 dp: pienennetään rivin kokoon.
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(64.dp).scale(28f / 64f), contentAlignment = Alignment.Center) {
                ManeuverImage(then, tint = Color.White)
            }
        }
        Text(then.text, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

/** OSRM:n kaistasuunnat kuvakkeiksi; tuntematon suunta (esim. "none") piirretään pisteenä. */
internal fun laneIcon(direction: String): ImageVector? = when (direction) {
    "straight" -> Icons.Filled.Straight
    "left" -> Icons.Filled.TurnLeft
    "right" -> Icons.Filled.TurnRight
    "slight left" -> Icons.Filled.TurnSlightLeft
    "slight right" -> Icons.Filled.TurnSlightRight
    "sharp left" -> Icons.Filled.TurnSharpLeft
    "sharp right" -> Icons.Filled.TurnSharpRight
    "uturn" -> Icons.Filled.UTurnLeft
    else -> null
}
