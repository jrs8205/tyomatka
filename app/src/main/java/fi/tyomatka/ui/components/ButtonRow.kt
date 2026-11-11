package fi.tyomatka.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Montako nappia samalle riville: kaikki rinnakkain, jos jokainen mahtuu kokonaisena, muuten jokainen omalle
 * rivilleen. Tasaleveinä ([equalWidths]) jokaisen pitää mahtua tasaosuuteensa; muuten riittää, että leveyksien summa
 * väleineen mahtuu (yksi nappi täyttää loput). Näin napin teksti ei koskaan katkea kesken sanan isolla fontilla tai
 * pitkällä kielellä.
 */
fun buttonsPerRow(widthsPx: List<Int>, availableWidthPx: Int, gapPx: Int, equalWidths: Boolean = true): Int {
    val count = widthsPx.size
    if (count <= 1) return 1
    val free = availableWidthPx - gapPx * (count - 1)
    val fits = if (equalWidths) widthsPx.all { it <= free.toDouble() / count } else widthsPx.sum() <= free
    return if (fits) count else 1
}

/** Napin vaakareunus kummallakin puolella sekä ikonin leveys ja väli tekstiin. */
private val SidePadding = 24.dp
private val IconSize = 20.dp
private val IconGap = 8.dp

/**
 * Nappirivi, joka mittaa nappien tekstit: napit ovat rinnakkain tasaleveinä, jos tekstit mahtuvat kokonaisina, ja
 * muuten allekkain täysleveinä ([buttonsPerRow]). Kun [fillIndex] on annettu, rinnakkain vain se nappi täyttää
 * jäljelle jäävän tilan ja muut ovat tekstinsä levyisiä. [button] saa napin indeksin ja leveyden määräävän modifierin.
 */
@Composable
fun AdaptiveButtonRow(
    labels: List<String>,
    hasIcon: List<Boolean>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    gap: Dp = 10.dp,
    fillIndex: Int? = null,
    button: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow = with(density) {
            val widths = labels.mapIndexed { i, label ->
                val text = measurer.measure(label, style, maxLines = 1, softWrap = false).size.width
                // Asettelu pyöristää jokaisen osan erikseen; sama tässä ja 1 px varaa, ettei raja katkaise sanaa.
                text + 2 * SidePadding.roundToPx() + 1 +
                    if (hasIcon.getOrElse(i) { false }) IconSize.roundToPx() + IconGap.roundToPx() else 0
            }
            buttonsPerRow(widths, constraints.maxWidth, gap.roundToPx(), equalWidths = fillIndex == null)
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            labels.indices.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { index ->
                        val fills = fillIndex == null || perRow == 1 || index == fillIndex
                        button(index, if (fills) Modifier.weight(1f) else Modifier)
                    }
                }
            }
        }
    }
}
