package fi.tyomatka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.core.api.normalizeServerUrl
import fi.tyomatka.data.repo.ServerKind
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.form.HelperLine
import fi.tyomatka.ui.form.HelperTone

/** Palvelinosoitteet: tyhjä kenttä on oletus. Sovellus lukee osoitteet käynnistyessään. */
@Composable
internal fun ServersContent(
    stored: Map<ServerKind, String>,
    defaults: Map<ServerKind, String>,
    onSave: (Map<ServerKind, String>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var saved by remember { mutableStateOf(false) }
    val fields = ServerKind.entries.associateWith { kind ->
        rememberSaveable(kind.name, stored[kind]) { mutableStateOf(stored[kind].orEmpty()) }
    }
    val invalid = fields.filterValues { it.value.isNotBlank() && normalizeServerUrl(it.value) == null }.keys
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_servers_hint), style = MaterialTheme.typography.bodyMedium)
        ServerKind.entries.forEach { kind ->
            val field = fields.getValue(kind)
            OutlinedTextField(
                value = field.value,
                onValueChange = {
                    field.value = it
                    saved = false
                },
                label = { Text(stringResource(kind.labelRes())) },
                placeholder = { Text(defaults.getValue(kind)) },
                singleLine = true,
                isError = kind in invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (kind in invalid) HelperLine(stringResource(R.string.settings_server_invalid), HelperTone.ERROR)
        }
        if (saved) HelperLine(stringResource(R.string.settings_servers_saved), HelperTone.ACCENT)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = Tap {
                    onReset()
                    fields.values.forEach { it.value = "" }
                    saved = true
                },
            ) { Text(stringResource(R.string.settings_servers_reset)) }
            Button(
                enabled = invalid.isEmpty(),
                onClick = Tap {
                    onSave(fields.mapValues { it.value.value })
                    saved = true
                },
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun ServerKind.labelRes(): Int = when (this) {
    ServerKind.VALHALLA -> R.string.settings_server_valhalla
    ServerKind.OSRM -> R.string.settings_server_osrm
    ServerKind.MAP_STYLE -> R.string.settings_server_map
    ServerKind.PHOTON -> R.string.settings_server_photon
    ServerKind.POI -> R.string.settings_server_poi
}
