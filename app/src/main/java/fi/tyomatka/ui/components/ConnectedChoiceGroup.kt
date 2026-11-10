package fi.tyomatka.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.tyomatka.ui.Tap

private val GroupGap = 3.dp
private val LabelPadding = 4.dp
private val OuterCorner = 24.dp
private val InnerCorner = 8.dp
private val Pill = RoundedCornerShape(percent = 50)

/**
 * Sarakkeiden määrä: kaikki vaihtoehdot rinnakkain, jos jokainen nimi reunuksineen mahtuu omaan soluunsa; muuten neljä
 * tai useampi kahteen sarakkeeseen ja lopulta allekkain. Kolme tai kaksi vaihtoehtoa asettuu suoraan allekkain, koska
 * vajaa rivi näyttäisi rikkinäiseltä.
 */
fun choiceGroupColumns(labelWidthsPx: List<Int>, availableWidthPx: Int, gapPx: Int, labelPaddingPx: Int): Int {
    val count = labelWidthsPx.size
    if (count <= 1) return 1
    val candidates = if (count >= 4) listOf(count, 2) else listOf(count)
    val widest = labelWidthsPx.max() + 2 * labelPaddingPx
    return candidates.firstOrNull { columns ->
        val cell = (availableWidthPx - gapPx * (columns - 1)).toDouble() / columns
        widest <= cell
    } ?: 1
}

/**
 * Yhdistetty valintaryhmä: 2–4 vaihtoehtoa vierekkäin pienin välein, ulkokulmat pyöreät ja sisäkulmat pienet. Valittu
 * on täysin pyöreä pilleri pääväreillä ja lihavoitu, joten valinnan erottaa myös muodosta. Ahtaassa tilassa ryhmä
 * jakautuu riveiksi ([choiceGroupColumns]); leveydet mitataan lihavoidusta nimestä (ja [supportingText]in rivistä), jotta
 * valinta ei muuta asettelua. Toinen rivi on esimerkiksi reitin matka ja aika.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: (@Composable (T) -> String?)? = null,
) {
    val labels = options.map { label(it) }
    val supporting = options.map { supportingText?.invoke(it) }
    val style = MaterialTheme.typography.labelLarge
    val supportStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widths = options.indices.map { i ->
            val main = measurer.measure(labels[i], style.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false).size.width
            val second = supporting[i]?.let { measurer.measure(it, supportStyle, maxLines = 1, softWrap = false).size.width } ?: 0
            maxOf(main, second)
        }
        val columns = with(density) {
            choiceGroupColumns(widths, constraints.maxWidth, GroupGap.roundToPx(), LabelPadding.roundToPx())
        }
        val rows = options.indices.chunked(columns)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            rows.forEachIndexed { rowIndex, indices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GroupGap)) {
                    indices.forEachIndexed { column, index ->
                        val option = options[index]
                        val isSelected = option == selected
                        val shape = if (isSelected || columns == 1) Pill else segmentShape(rowIndex, rows.size, column, indices.size)
                        Surface(
                            shape = shape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(shape)
                                .selectable(selected = isSelected, role = Role.RadioButton, onClick = Tap { onSelect(option) }),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = LabelPadding, vertical = 12.dp),
                            ) {
                                Text(
                                    labels[index],
                                    style = style.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize),
                                )
                                supporting[index]?.let { second ->
                                    Text(
                                        second,
                                        style = supportStyle,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        softWrap = false,
                                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = supportStyle.fontSize),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ryhmän ulkoreunan kulmat pyöreät, muut pienet: ruudukossa vain neljä kulmaa ovat ulkokulmia. */
private fun segmentShape(row: Int, rowCount: Int, column: Int, columnCount: Int): RoundedCornerShape {
    fun corner(outer: Boolean): Dp = if (outer) OuterCorner else InnerCorner
    val top = row == 0
    val bottom = row == rowCount - 1
    val start = column == 0
    val end = column == columnCount - 1
    return RoundedCornerShape(
        topStart = corner(top && start),
        topEnd = corner(top && end),
        bottomEnd = corner(bottom && end),
        bottomStart = corner(bottom && start),
    )
}
