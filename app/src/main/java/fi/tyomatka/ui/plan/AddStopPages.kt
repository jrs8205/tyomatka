package fi.tyomatka.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

/**
 * Lisäysikkunan välilehdet: niin leveät, että puhelimessa (360–412 dp) näkyy kaksi välilehteä ja kolmannesta pala, joten
 * pyyhkäistävä sivu on arvattavissa. Rivi vierittyy valitun välilehden mukana ja seuraa pyyhkäisyä.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopTabs(pagerState: PagerState, titles: List<String>, onSelect: (Int) -> Unit) {
    val tabWidth = (LocalConfiguration.current.screenWidthDp * TAB_WIDTH_SHARE).dp
    PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 0.dp) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = Tap { onSelect(index) },
                // requiredWidth: rivin korkeus lasketaan tällä leveydellä, joten suurella fontilla kaksirivinen otsikko
                // kasvattaa riviä eikä leikkaudu.
                modifier = Modifier.requiredWidth(tabWidth),
                text = { Text(title, textAlign = TextAlign.Center) },
            )
        }
    }
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

/** Välilehden osuus näytön leveydestä: 2 × 0,42 = 0,84, joten kolmannesta näkyy pala. */
private const val TAB_WIDTH_SHARE = 0.42f
