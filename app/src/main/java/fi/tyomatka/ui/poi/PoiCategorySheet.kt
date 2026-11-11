package fi.tyomatka.ui.poi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.repo.PoiSettings
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.components.AppDragHandle
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color

/** Kohteet-valikko: neljä luokkaa kytkiminä sekä raskaan kaluston ja reitin varrella -tarkennukset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiCategorySheet(
    settings: PoiSettings,
    hasRoute: Boolean,
    onToggle: (PoiCategory) -> Unit,
    onHeavyOnly: (Boolean) -> Unit,
    onRouteOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(dragHandle = { AppDragHandle() }, onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.poi_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PoiCategory.entries.forEach { category ->
            SwitchRow(
                title = stringResource(PoiCardText.categoryLabelRes(category)),
                hint = null,
                icon = PoiCardText.categoryIcon(category),
                checked = category in settings.categories,
                onChecked = { onToggle(category) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SwitchRow(
            title = stringResource(R.string.poi_heavy_only),
            hint = stringResource(R.string.poi_heavy_only_hint),
            icon = null,
            checked = settings.heavyOnly,
            onChecked = { onHeavyOnly(!settings.heavyOnly) },
        )
        if (hasRoute) {
            SwitchRow(
                title = stringResource(R.string.poi_route_only),
                hint = stringResource(R.string.poi_route_only_hint),
                icon = null,
                checked = settings.routeOnly,
                onChecked = { onRouteOnly(!settings.routeOnly) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, icon: ImageVector?, checked: Boolean, onChecked: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = hint?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = { Tap.now(); onChecked() }) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = Tap(onChecked)),
        // Paneelin oma pohja näkyy rivin alla: ei eri sävyisiä raitoja.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
