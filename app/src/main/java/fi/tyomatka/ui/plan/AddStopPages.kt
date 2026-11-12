package fi.tyomatka.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

/**
 * Lisäysikkunan välilehdet: kaikki näkyvät kokonaan tasaleveinä, ja sivupisteet kertovat pyyhkäistävistä sivuista.
 * Otsikko rivittyy vain sanojen välistä. Jos pisin sana ei mahdu välilehteen (suuri fontti, kapea näyttö), kaikkien
 * otsikoiden teksti pienenee yhtä paljon ([tabTextScale]), jotta mikään sana ei katkea eikä leikkaudu reunasta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopTabs(pagerState: PagerState, titles: List<String>, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleSmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textWidthPx = constraints.maxWidth / titles.size.coerceAtLeast(1) -
            with(LocalDensity.current) { 2 * TabTextPadding.roundToPx() }
        val wordWidths = titles.flatMap(::tabTitleWords).map { word ->
            measurer.measure(word, style, maxLines = 1, softWrap = false).size.width
        }
        val scale = tabTextScale(wordWidths, textWidthPx)
        val tabStyle = if (scale < 1f) style.copy(fontSize = style.fontSize * scale) else style
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = Tap { onSelect(index) },
                    // Kaksirivinen otsikko kasvattaa riviä; yksirivisenä korkeus on Materialin välilehden 48 dp.
                    modifier = Modifier.heightIn(min = TabMinHeight),
                ) {
                    Text(
                        title,
                        style = tabStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = TabTextPadding, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Otsikon sanat: rivi saa vaihtua vain näiden välissä. */
fun tabTitleWords(title: String): List<String> = title.split(' ').filter { it.isNotEmpty() }

/**
 * Välilehtien tekstikoon kerroin: 1, kun pisin sana mahtuu [availablePx]:iin pikselin varalla, muuten niin pieni, että
 * se mahtuu (leveys skaalautuu fonttikoon mukana). Asettelu pyöristää osat erikseen, siksi pikselin vara.
 */
fun tabTextScale(wordWidthsPx: List<Int>, availablePx: Int): Float {
    val longest = wordWidthsPx.maxOrNull() ?: return 1f
    if (longest < availablePx) return 1f
    return ((availablePx - 1).toFloat() / longest).coerceAtLeast(MIN_TAB_TEXT_SCALE)
}

/** Sivupisteet välilehtien alla: nykyinen korostettuna; TalkBack lukee "Sivu 2/3". */
@Composable
fun PageDots(count: Int, current: Int) {
    val description = stringResource(R.string.nearby_page, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

/** Takaisin-ele lisäysikkunassa: edellinen sivu; ensimmäiseltä sivulta ei mihinkään (ikkuna suljetaan vain nuolesta). */
fun previousAddStopPage(current: Int): Int? = (current - 1).takeIf { it >= 0 }

/** Välilehden tekstin vaakavara kummallakin puolella: kolmen välilehden otsikot mahtuvat yleensä yhdelle riville. */
private val TabTextPadding = 12.dp
private val TabMinHeight = 48.dp

/** Alaraja, ettei äärimmäisen kapea tila tuota näkymätöntä tekstiä. */
private const val MIN_TAB_TEXT_SCALE = 0.25f
