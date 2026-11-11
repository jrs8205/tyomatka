package fi.tyomatka.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.TemplateListItem
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.formatShortDate
import java.time.Instant
import java.time.ZoneId
import fi.tyomatka.ui.components.AppDragHandle
import fi.tyomatka.ui.boundParts
import fi.tyomatka.ui.PART_SEPARATOR

/**
 * Tallennettujen reittien lista koko korkeuden alavalikkona: rivillä nimi, stoppien määrä ja viimeisin avaus;
 * napautus avaa reitin päivälle, kolmen pisteen valikossa Nimeä uudelleen ja Poista.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteTemplateSheet(
    templates: List<TemplateListItem>,
    onOpen: (RouteTemplateEntity) -> Unit,
    onRename: (RouteTemplateEntity) -> Unit,
    onDelete: (RouteTemplateEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(dragHandle = { AppDragHandle() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.template_list_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (templates.isEmpty()) {
            Text(
                stringResource(R.string.template_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(templates, key = { it.template.id }) { item ->
                    TemplateRow(
                        item = item,
                        onOpen = { onOpen(item.template) },
                        onRename = { onRename(item.template) },
                        onDelete = { onDelete(item.template) },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TemplateRow(
    item: TemplateListItem,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val opened = item.template.lastOpenedEpochSec
        ?.let { stringResource(R.string.template_last_opened, boundParts(formatShortDate(epochDayOf(it), capitalize = false))) }
        ?: stringResource(R.string.template_never_opened)
    ListItem(
        // Nimi rivittyy kokonaisena eikä lyhene.
        headlineContent = { Text(item.template.name) },
        supportingContent = {
            // Määrä ja päivämäärä pysyvät koossa, lause rivittyy sanojen välistä.
            Text(boundParts(pluralStringResource(R.plurals.stop_count, item.stopCount, item.stopCount)) + PART_SEPARATOR + opened)
        },
        trailingContent = {
            Box {
                IconButton(onClick = Tap { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.template_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_rename)) },
                        onClick = Tap {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_delete)) },
                        onClick = Tap {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = Tap(onOpen)),
    )
}

private fun epochDayOf(epochSec: Long): Long =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
