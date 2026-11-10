package fi.tyomatka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.formatWhole

/** Mittarijanan tila: valmis, käynnissä (normaali, ei virhe), lukema puuttuu tai lukemat ristiriidassa. */
enum class OdometerState { COMPLETE, ONGOING, MISSING, INVALID }

/** Janan loppupää: lukema, "kesken" (avoin jakso) tai "puuttuu" (päättynyt jakso ilman lukemaa). */
enum class OdometerEnd { VALUE, IN_PROGRESS, MISSING }

/** Mitä janalla näytetään: tila, puuttuuko alkulukema ja mitä loppupäässä lukee. */
data class OdometerDisplay(val state: OdometerState, val startMissing: Boolean, val end: OdometerEnd)

/**
 * Tila lukemista: avoimelta jaksolta loppulukema puuttuu luonnostaan, päättyneeltä se on puute. Alkulukeman
 * puuttuminen on aina puute.
 */
fun odometerState(startKm: Long?, endKm: Long?, ended: Boolean): OdometerState = when {
    startKm == null -> OdometerState.MISSING
    endKm == null -> if (ended) OdometerState.MISSING else OdometerState.ONGOING
    endKm < startKm -> OdometerState.INVALID
    else -> OdometerState.COMPLETE
}

/** Näytettävät osat lukemista: kumpikin pää kertoo oman puutteensa sanalla, ei pelkällä värillä. */
fun odometerDisplay(startKm: Long?, endKm: Long?, ended: Boolean): OdometerDisplay = OdometerDisplay(
    state = odometerState(startKm, endKm, ended),
    startMissing = startKm == null,
    end = when {
        endKm != null -> OdometerEnd.VALUE
        ended -> OdometerEnd.MISSING
        else -> OdometerEnd.IN_PROGRESS
    },
)

/**
 * Mittarilukemat janana: alkulukema, täytetty piste, viiva ja loppupiste sekä loppulukema. Käynnissä olevan jakson
 * loppu on avoin ympyrä ja sana "kesken", puuttuva lukema sana "puuttuu" virhevärillä. Värillisellä pohjalla
 * ([onColoredSurface], Tänään-kortti) kaikki on `onPrimaryContainer`-väristä, koska virheväri ei siellä täytä AAA:ta;
 * kortin oma varoitusrivi kertoo silloin puutteen. Kapeassa tilassa loppulukema rivittyy, mutta luku ei katkea.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OdometerSpan(
    startKm: Long?,
    endKm: Long?,
    ended: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
    onColoredSurface: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleSmall,
) {
    val display = odometerDisplay(startKm, endKm, ended)
    val state = display.state
    val scheme = MaterialTheme.colorScheme
    val base = if (onColoredSurface) scheme.onPrimaryContainer else scheme.onSurface
    val muted = if (onColoredSurface) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    val accent = if (onColoredSurface) scheme.onPrimaryContainer else scheme.primary
    val problem = if (onColoredSurface) scheme.onPrimaryContainer else scheme.error
    val missingWord = stringResource(R.string.odometer_missing)
    val startText = startKm?.let(::formatWhole) ?: missingWord
    val endText = when (display.end) {
        OdometerEnd.VALUE -> formatWhole(endKm ?: 0)
        OdometerEnd.IN_PROGRESS -> stringResource(R.string.work_in_progress)
        OdometerEnd.MISSING -> missingWord
    }
    val startColor = if (display.startMissing || state == OdometerState.INVALID) problem else base
    val endColor = when (display.end) {
        OdometerEnd.VALUE -> if (state == OdometerState.INVALID) problem else base
        OdometerEnd.IN_PROGRESS -> muted
        OdometerEnd.MISSING -> problem
    }
    val railColor = if (state == OdometerState.COMPLETE || state == OdometerState.ONGOING) accent else problem
    val description = listOfNotNull(
        label,
        stringResource(R.string.odometer_span_description, startText, endText),
        stringResource(R.string.odometer_invalid).takeIf { state == OdometerState.INVALID },
    ).joinToString(", ")
    val strong = style.copy(fontWeight = FontWeight.Bold)
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) Text(label, style = strong, color = base)
        Text(startText, style = strong, color = startColor)
        OdometerRail(
            color = railColor,
            endOpen = display.end != OdometerEnd.VALUE,
            modifier = Modifier.weight(1f).widthIn(min = 48.dp).height(12.dp),
        )
        Text(endText, style = if (display.end == OdometerEnd.IN_PROGRESS) style else strong, color = endColor)
    }
}

/** Jana: täytetty alkupiste, 3 dp:n viiva ja loppupiste, joka on avoin ympyrä, kun loppulukemaa ei ole. */
@Composable
private fun OdometerRail(color: Color, endOpen: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val radius = 5.dp.toPx()
        val y = size.height / 2
        val startX = radius
        val endX = size.width - radius
        drawCircle(color, radius, Offset(startX, y))
        drawLine(color, Offset(startX, y), Offset(endX - radius, y), strokeWidth = 3.dp.toPx())
        if (endOpen) {
            val ring = 2.5.dp.toPx()
            drawCircle(color, radius - ring / 2, Offset(endX, y), style = Stroke(width = ring))
        } else {
            drawCircle(color, radius, Offset(endX, y))
        }
    }
}
