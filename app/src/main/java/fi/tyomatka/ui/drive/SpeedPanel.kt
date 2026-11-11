package fi.tyomatka.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SignageStyle
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SpeedLimitView
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeed
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import fi.tyomatka.R
import fi.tyomatka.core.nav.SpeedAlert
import fi.tyomatka.core.nav.speedAlert

/** Googlen varoitusväri ylinopeudelle. */
val SpeedWarningRed = Color(0xFFD93025)

/**
 * Tiheys, jolla nopeuspaneeli piirretään kuvana: fonttikoko kasvattaa kyltin ja mittarin ympyrän samassa suhteessa
 * kuin niiden tekstit, joten "km/h" ja kolminumeroinen nopeus eivät leikkaudu eivätkä katkea isolla fontilla.
 * Pienempi fontti ei pienennä paneelia.
 */
internal fun signDensity(density: Density): Density =
    Density(density.density * density.fontScale.coerceAtLeast(1f), fontScale = 1f)

/**
 * Nopeusmittari aina näkyvissä ja nopeusrajoitus Wienin
 * sopimuksen kylttinä sen vieressä. Ylitys näkyy väreinä: lievä ylitys punaisella luvulla valkoisella, selvä ylitys
 * valkoisella luvulla punaisella pohjalla. Nopeus näytetään vain, kun sijainti on riittävän tarkka.
 */
@Composable
fun SpeedPanel(speedKmh: Int?, limitKmh: Int?, modifier: Modifier = Modifier) {
    val alert = speedAlert(speedKmh, limitKmh)
    if (limitKmh == null && speedKmh == null) return
    // Sijainti (modifier) tavallisella tiheydellä, jottei paneelin paikka siirry fonttikoon mukana.
    Box(modifier) {
        CompositionLocalProvider(LocalDensity provides signDensity(LocalDensity.current)) {
            SpeedPanelContent(speedKmh, limitKmh, alert)
        }
    }
}

@Composable
private fun SpeedPanelContent(speedKmh: Int?, limitKmh: Int?, alert: SpeedAlert) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (limitKmh != null) {
            val limitDescription = stringResource(R.string.speed_limit_sign, limitKmh)
            SpeedLimitView(
                // Kyltin omat tekstit eivät toistu ruudunlukijassa: yksi kuvaus riittää.
                modifier = Modifier.clearAndSetSemantics { contentDescription = limitDescription },
                speedLimit = MeasurementSpeed(limitKmh.toDouble(), MeasurementSpeedUnit.KilometersPerHour),
                signageStyle = SignageStyle.ViennaConvention,
            )
        }
        if (speedKmh != null) {
            val (background, foreground) = when (alert) {
                SpeedAlert.MAJOR -> SpeedWarningRed to Color.White
                SpeedAlert.MINOR -> Color.White to SpeedWarningRed
                else -> Color.White to Color.Black
            }
            val speedDescription = stringResource(R.string.speed_current, speedKmh)
            Surface(shape = CircleShape, color = background, shadowElevation = 2.dp) {
                Text(
                    speedKmh.toString(),
                    color = foreground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .size(56.dp)
                        .wrapContentSize(Alignment.Center)
                        .semantics { contentDescription = speedDescription },
                )
            }
        }
    }
}
