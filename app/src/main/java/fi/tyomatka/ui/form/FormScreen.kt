package fi.tyomatka.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import fi.tyomatka.ui.FullScreenDialog

/**
 * Koko näytön lomake: yläpalkki (sulje, otsikko, valinnainen toiminto), rullaava
 * sisältö lohkoina ja alareunan päätoiminto, joka pysyy näppäimistön yläpuolella.
 * [primaryError] näytetään päätoiminnon yläpuolella, missä käyttäjä katsoo juuri napautettuaan sitä.
 */
@Composable
fun FormScreen(
    title: String,
    onClose: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    topAction: (@Composable RowScope.() -> Unit)? = null,
    primaryError: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    FullScreenDialog(onDismissRequest = onClose) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                ) {
                    IconButton(onClick = Tap(onClose)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    topAction?.invoke(this)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
                Surface(tonalElevation = 3.dp) {
                    Column {
                        if (primaryError != null) {
                            // Ruudunlukija lukee virheen, kun se ilmestyy: käyttäjän kohdistus on päätoiminnossa.
                            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                HelperLine(primaryError, HelperTone.ERROR)
                            }
                        }
                        Button(
                            onClick = Tap(onPrimary),
                            enabled = primaryEnabled,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                        ) { Text(primaryLabel, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }
}

@Composable
fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

enum class HelperTone { INFO, ERROR, ACCENT }

@Composable
fun HelperLine(text: String, tone: HelperTone = HelperTone.INFO) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = when (tone) {
            HelperTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            HelperTone.ERROR -> MaterialTheme.colorScheme.error
            HelperTone.ACCENT -> MaterialTheme.colorScheme.tertiary
        },
    )
}
