package fi.tyomatka.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.plan.TemplateChange
import fi.tyomatka.ui.Tap

/**
 * Muutosrivi Suunnittelu-välilehden yläreunassa: "<nimi> on muuttunut: <kuvaus>" ja napit Tallenna, Hylkää muutokset
 * (vain, kun päivän ajo ei ole alkanut) ja Myöhemmin. Napit rivittyvät, tekstit eivät katkea kesken sanan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateChangedBanner(
    banner: TemplateBanner,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onLater: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.template_changed, banner.templateName, changeDescription(banner.change)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = Tap(onSave), colors = buttonColors) { Text(stringResource(R.string.save)) }
                if (banner.canDiscard) {
                    TextButton(onClick = Tap(onDiscard), colors = buttonColors) { Text(stringResource(R.string.template_discard)) }
                }
                TextButton(onClick = Tap(onLater), colors = buttonColors) { Text(stringResource(R.string.template_later)) }
            }
        }
    }
}

/** Muutoksen kuvaus pilkulla eroteltuna: "1 stoppi lisätty, 2 stoppia muutettu, järjestys vaihdettu". */
@Composable
fun changeDescription(change: TemplateChange): String = buildList {
    if (change.added > 0) add(pluralStringResource(R.plurals.template_change_added, change.added, change.added))
    if (change.removed > 0) add(pluralStringResource(R.plurals.template_change_removed, change.removed, change.removed))
    if (change.edited > 0) add(pluralStringResource(R.plurals.template_change_edited, change.edited, change.edited))
    if (change.reordered) add(stringResource(R.string.template_change_reordered))
}.joinToString(", ")
