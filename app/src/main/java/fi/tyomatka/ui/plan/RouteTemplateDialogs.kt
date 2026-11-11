package fi.tyomatka.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.plan.TEMPLATE_NAME_MAX_LENGTH
import fi.tyomatka.core.plan.normalizeTemplateName
import fi.tyomatka.data.db.RouteTemplateEntity
import fi.tyomatka.data.db.TemplateListItem
import fi.tyomatka.data.repo.OpenMode
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.components.AppTextField
import fi.tyomatka.ui.components.AppButtonDefaults
import androidx.compose.material3.Button

/**
 * Tallennuskysely: nimi ja Tallenna; pohjasta avatulle
 * päivälle Tallenna uutena ja Korvaa <nimi>. Toisen pohjan nimi → huomautus ja Korvaa sille pohjalle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveTemplateDialog(
    customerStopCount: Int,
    templates: List<TemplateListItem>,
    openedTemplate: RouteTemplateEntity?,
    onSaveNew: (String) -> Unit,
    onOverwrite: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(openedTemplate?.name ?: "") }
    val normalized = normalizeTemplateName(name)
    val duplicate = if (normalized.isEmpty()) {
        null
    } else {
        templates.firstOrNull {
            it.template.id != openedTemplate?.id && normalizeTemplateName(it.template.name) == normalized
        }?.template
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_save)) },
        text = {
            Column {
                TemplateNameField(value = name, onChange = { name = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    pluralStringResource(R.plurals.template_save_summary, customerStopCount, customerStopCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (duplicate != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.template_name_exists),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
                val saveLabel = if (openedTemplate != null || duplicate != null) R.string.template_save_new else R.string.save
                TextButton(colors = AppButtonDefaults.textColors(), enabled = normalized.isNotEmpty(), onClick = Tap { onSaveNew(normalized) }) {
                    Text(stringResource(saveLabel))
                }
                openedTemplate?.let { opened ->
                    TextButton(onClick = Tap { onOverwrite(opened.id) }) {
                        Text(stringResource(R.string.template_overwrite, opened.name))
                    }
                }
                duplicate?.let { other ->
                    TextButton(onClick = Tap { onOverwrite(other.id) }) {
                        Text(stringResource(R.string.template_overwrite, other.name))
                    }
                }
            }
        },
    )
}

/** Avauskysely, kun päivän listassa on jo stoppeja: Peruuta, Lisää perään, Korvaa päivän stopit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpenTemplateDialog(
    template: RouteTemplateEntity,
    dayStopCount: Int,
    onOpen: (OpenMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.template_open_title, dayStopCount, dayStopCount)) },
        text = { Text(stringResource(R.string.template_open_text, template.name)) },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = Tap { onOpen(OpenMode.APPEND) }) { Text(stringResource(R.string.template_open_append)) }
                TextButton(onClick = Tap { onOpen(OpenMode.REPLACE) }) { Text(stringResource(R.string.template_open_replace)) }
            }
        },
    )
}

/** Nimen muutos; OK vain, kun nimi kelpaa ja eroaa nykyisestä. */
@Composable
fun RenameTemplateDialog(
    template: RouteTemplateEntity,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(template.name) }
    val normalized = normalizeTemplateName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_rename)) },
        text = { TemplateNameField(value = name, onChange = { name = it }) },
        confirmButton = {
            TextButton(colors = AppButtonDefaults.textColors(), enabled = normalized.isNotEmpty() && normalized != template.name, onClick = Tap { onRename(normalized) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Poiston varmistus; päivän stopit säilyvät. */
@Composable
fun DeleteTemplateDialog(
    template: RouteTemplateEntity,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_delete)) },
        text = { Text(stringResource(R.string.template_delete_confirm, template.name)) },
        confirmButton = {
            Button(colors = AppButtonDefaults.dangerColors(), onClick = Tap(onDelete)) {
                Text(stringResource(R.string.template_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Muutosrivin Hylkää muutokset -varmistus: päivän lista palautetaan pohjan mukaiseksi, huoltostopit säilyvät. */
@Composable
fun DiscardTemplateChangesDialog(
    templateName: String,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_discard)) },
        text = { Text(stringResource(R.string.template_discard_confirm, templateName)) },
        confirmButton = {
            Button(colors = AppButtonDefaults.dangerColors(), onClick = Tap(onDiscard)) {
                Text(stringResource(R.string.template_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = Tap(onDismiss)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Reitin nimen kenttä: yksi rivi, enintään [TEMPLATE_NAME_MAX_LENGTH] merkkiä, iso alkukirjain. */
@Composable
private fun TemplateNameField(value: String, onChange: (String) -> Unit) {
    AppTextField(
        value = value,
        onValueChange = { onChange(it.take(TEMPLATE_NAME_MAX_LENGTH)) },
        label = { Text(stringResource(R.string.template_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}
