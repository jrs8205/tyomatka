package fi.tyomatka.ui.plan.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.tyomatka.R
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.poi.PoiCard
import fi.tyomatka.ui.poi.PoiCardText
import fi.tyomatka.ui.poi.ReportSheet
import java.time.LocalDateTime
import fi.tyomatka.ui.components.AppTextField
import fi.tyomatka.ui.components.AppFilterChip
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.ui.text.font.FontWeight

/** Lajisirujen järjestys. */
private val CATEGORIES = listOf(PoiCategory.FUEL, PoiCategory.REST, PoiCategory.GAS, PoiCategory.CHARGING)

/** Kohteet-sivun hakukenttä lisäysikkunan yläpalkissa; ei nosta näppäimistöä itsestään. */
@Composable
fun NearbySearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    AppTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.nearby_search)) },
        modifier = modifier,
        shape = CircleShape,
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/**
 * Kohteet-sivu: vyöhykkeet, lajit ja Raskas, määrärivi ja rivit lähin ensin. Plus-nappi ja kortin Lisää stopiksi
 * kutsuvat [onAdd]ia, joka lisää stopin ja sulkee ikkunan.
 */
@Composable
fun NearbyPage(state: NearbyUiState, viewModel: NearbyViewModel, onAdd: (PoiItem) -> Unit) {
    var showFix by remember { mutableStateOf(false) }
    // Kortin sulkeminen sulkee myös Ilmoita muutos -näkymän: seuraava kortti aukeaa korttina.
    LaunchedEffect(state.selection == null) { if (state.selection == null) showFix = false }
    Column(Modifier.fillMaxSize()) {
        when (val session = state.session) {
            NearbySessionState.Idle, NearbySessionState.Locating -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            NearbySessionState.NoLocation -> Text(
                stringResource(R.string.nearby_no_location),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
            is NearbySessionState.Loaded -> NearbyContent(state, session, viewModel, onAdd, Modifier.weight(1f))
        }
        if (state.selection == null || !showFix) {
            state.notice?.let { notice ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    dismissAction = { TextButton(colors = AppButtonDefaults.snackbarActionColors(), onClick = Tap(viewModel::dismissNotice)) { Text(stringResource(R.string.ok), fontWeight = FontWeight.Bold) } },
                ) { Text(notice) }
            }
        }
    }

    // Sama kohdekortti kuin kartalla; Ilmoita muutos korvaa kortin, kunnes se suljetaan.
    state.selection?.let { selection ->
        if (showFix) {
            ReportSheet(
                selection = selection,
                notice = state.notice,
                onReport = viewModel::report,
                onDismiss = { showFix = false },
            )
        } else {
            PoiCard(
                selection = selection,
                primaryText = stringResource(R.string.poi_add_stop),
                onPrimary = { onAdd(selection.item) },
                onFix = { showFix = true },
                onDismiss = { viewModel.select(null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyContent(
    state: NearbyUiState,
    session: NearbySessionState.Loaded,
    viewModel: NearbyViewModel,
    onAdd: (PoiItem) -> Unit,
    modifier: Modifier,
) {
    val choices = state.choices
    val data = session.data
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            NearbyBand.entries.forEachIndexed { index, band ->
                val label = stringResource(bandRes(band))
                SegmentedButton(
                    selected = band == choices.band,
                    onClick = Tap { viewModel.setBand(band) },
                    shape = SegmentedButtonDefaults.itemShape(index, NearbyBand.entries.size),
                    // Ei valintamerkkiä, ja teksti yhdellä rivillä tarvittaessa pienempänä: määrä mahtuu katkeamatta kesken sanan.
                    icon = {},
                ) {
                    Text(
                        state.bandCounts?.get(band)?.let { stringResource(R.string.nearby_band_count, label, it) } ?: label,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            CATEGORIES.forEach { category ->
                val name = stringResource(categoryRes(category))
                AppFilterChip(
                    selected = category == choices.category,
                    onClick = Tap { viewModel.setCategory(category) },
                    label = { Text(state.categoryCounts[category]?.let { stringResource(R.string.nearby_chip_count, name, it) } ?: name) },
                )
            }
            AppFilterChip(
                selected = choices.heavyOnly,
                onClick = Tap(viewModel::toggleHeavy),
                label = { Text(stringResource(R.string.nearby_heavy)) },
            )
        }
        if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val categoryLoaded = choices.category in data.items
        if (categoryLoaded) {
            Text(
                pluralStringResource(countRes(choices.category), state.rows.size, state.rows.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Lähin ensin: valintojen vaihtuessa lista alkuun, ja alussa ollessa uusi lähin (esim. alisoluista) näkyviin.
        val listState = rememberLazyListState()
        LaunchedEffect(choices.band, choices.category, choices.heavyOnly, choices.query) { listState.scrollToItem(0) }
        val firstId = state.rows.firstOrNull()?.item?.id
        LaunchedEffect(firstId) { if (listState.firstVisibleItemIndex == 0) listState.scrollToItem(0) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(state.rows, key = { it.item.id }) { row ->
                NearbyRowItem(row, onOpen = { viewModel.select(row.item.id) }, onAdd = { onAdd(row.item) })
                HorizontalDivider()
            }
            if (categoryLoaded && state.rows.isEmpty()) {
                item {
                    Text(
                        stringResource(if (choices.query.isNotBlank()) R.string.nearby_no_matches else R.string.nearby_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        // Tila listan alla kartan tapaan: välimuisti ilman verkkoa, palvelu ei saatavilla tai kohteita jäi pois.
        nearbyHints(data, choices.category).forEach { hint ->
            Text(
                when (hint) {
                    is NearbyHint.Stale ->
                        if (hint.days < 1) stringResource(R.string.poi_stale_today) else pluralStringResource(R.plurals.poi_stale, hint.days, hint.days)
                    NearbyHint.Offline -> stringResource(R.string.poi_offline)
                    NearbyHint.Unavailable -> stringResource(R.string.poi_unavailable)
                    NearbyHint.Truncated -> stringResource(R.string.nearby_truncated)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun NearbyRowItem(row: NearbyRow, onOpen: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val strings: (Int, Array<out Any>) -> String = { id, args -> context.getString(id, *args) }
    val item = row.item
    val info = row.info
    // Sama nimi kuin kortissa ja lisätyssä stopissa (nimi tai ketju).
    val name = PoiServices.displayName(item) ?: stringResource(PoiCardText.categoryNameRes(item.category))
    val closed = info.closed?.value == true
    // Suljettu asema himmennetään tekstivärillä (AAA säilyy), ei läpinäkyvyydellä.
    val dim = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    val station = item.category == PoiCategory.FUEL || item.category == PoiCategory.GAS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = Tap(onOpen))
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = dim)
            item.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            NearbyText.details(item, info, strings)?.let { details ->
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                    textDecoration = if (station && info.fuelsOff) TextDecoration.LineThrough else null,
                )
            }
            when {
                closed -> Text(stringResource(PoiCardText.closedRes(item.category)), style = MaterialTheme.typography.bodyMedium, color = dim)
                station && info.fuelsOff -> Text(stringResource(R.string.poi_warning_teboil), style = MaterialTheme.typography.bodyMedium)
            }
            NearbyText.hours(info, LocalDateTime.now(), strings)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = dim) }
        }
        Text(
            NearbyText.distance(row.distanceKm),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
        IconButton(onClick = Tap(onAdd)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nearby_add, name))
        }
    }
}

private fun bandRes(band: NearbyBand): Int = when (band) {
    NearbyBand.NEAR -> R.string.nearby_band_near
    NearbyBand.MID -> R.string.nearby_band_mid
    NearbyBand.FAR -> R.string.nearby_band_far
}

private fun categoryRes(category: PoiCategory): Int = when (category) {
    PoiCategory.FUEL -> R.string.nearby_cat_fuel
    PoiCategory.REST -> R.string.nearby_cat_rest
    PoiCategory.GAS -> R.string.nearby_cat_gas
    PoiCategory.CHARGING -> R.string.nearby_cat_charging
}

private fun countRes(category: PoiCategory): Int = when (category) {
    PoiCategory.FUEL -> R.plurals.nearby_count_fuel
    PoiCategory.REST -> R.plurals.nearby_count_rest
    PoiCategory.GAS -> R.plurals.nearby_count_gas
    PoiCategory.CHARGING -> R.plurals.nearby_count_charging
}
