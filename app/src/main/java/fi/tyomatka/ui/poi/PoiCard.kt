package fi.tyomatka.ui.poi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiServices
import fi.tyomatka.core.poi.StationInfo
import fi.tyomatka.ui.Tap
import java.time.LocalDate

/**
 * Kohdekortti alavalikkona: kuvake ja nimi, osoite, matka ja aika. Huoltoasemalla osiot lähteineen ([StationSections]),
 * latauspisteellä ja lepopaikalla palvelut siruina (puuttuva tieto katkoviivalla); napit ja käytettyjen lähteiden rivi. [maxHeightFraction] rajaa korkeuden,
 * jotta ajon käännösotsikko jää näkyviin.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PoiCard(
    selection: PoiSelection,
    primaryText: String,
    onPrimary: () -> Unit,
    onFix: (() -> Unit)?,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.9f,
) {
    val context = LocalContext.current
    val strings: (Int, Array<out Any>) -> String = { id, args -> context.getString(id, *args) }
    val item = selection.item
    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction).dp
    val website = PoiServices.website(item) ?: item.tags["k:url"]?.takeIf { it.startsWith("https://") }
    val station = item.category == PoiCategory.FUEL || item.category == PoiCategory.GAS
    val info = selection.info ?: StationInfo.of(item, emptyList())
    // Huoltoasemalla osiot korvaavat sirut; latauspisteellä ja lepopaikalla sirut säilyvät, ja ilmoitukset korvaavat ne kentittäin.
    val chips = if (station) emptyList() else PoiServices.chips(item, selection.corrections + PoiCardText.driverCorrections(info))
    val today = LocalDate.now().toEpochDay()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PoiCardText.categoryIcon(item.category), contentDescription = null, tint = PoiCardText.categoryColor(item.category), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    PoiServices.displayName(item) ?: stringResource(PoiCardText.categoryNameRes(item.category)),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(item.address ?: stringResource(R.string.poi_no_address), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PoiCardText.distance(selection.eta, selection.straightM, strings)?.let { text ->
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { chip ->
                        val label = PoiCardText.chip(chip, strings)
                        InfoChip(
                            text = if (chip.corrected) "$label · ${stringResource(R.string.poi_corrected)}" else label,
                            dashed = !chip.known,
                        )
                    }
                }
            }
            if (station) {
                StationSections(info, selection.chargingNearby, today, strings)
            } else {
                if (item.category == PoiCategory.CHARGING) ChargingSection(item, info, strings)
                OtherSections(item, info, strings)
            }
            PoiServices.phone(item)?.let { InfoRow(stringResource(R.string.poi_phone), it) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = Tap(onPrimary), modifier = Modifier.fillMaxWidth()) { Text(primaryText) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (website != null) {
                    OutlinedButton(onClick = Tap { openStationPage(context, website) }) {
                        Text(stringResource(R.string.poi_website))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                if (onFix != null) {
                    OutlinedButton(onClick = Tap(onFix)) { Text(stringResource(R.string.poi_report)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(attribution(item, selection.chargingNearby, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Spacer(Modifier.height(8.dp))
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Ei-napautettava siru; [dashed] = tieto puuttuu ("ei tietoa"). */
@Composable
private fun InfoChip(text: String, dashed: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    val outline = MaterialTheme.colorScheme.outline
    val modifier = if (dashed) {
        Modifier.drawBehind {
            drawRoundRect(
                color = outline,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
            )
        }
    } else {
        Modifier
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = if (dashed) null else BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (dashed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
