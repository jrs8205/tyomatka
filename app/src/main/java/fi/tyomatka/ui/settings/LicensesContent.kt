package fi.tyomatka.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lisenssiteksti sovelluksen mukana: aineistojen ehdot edellyttävät tekstin jakamista aineiston kanssa. */
internal data class DataLicense(val key: String, @param:StringRes val title: Int, @param:RawRes val text: Int)

internal val DATA_LICENSES = listOf(
    DataLicense("gpl", R.string.license_app, R.raw.license_gpl_3_0),
    DataLicense("cdla", R.string.license_cdla, R.raw.license_cdla_permissive_2_0),
    DataLicense("apache", R.string.license_apache, R.raw.license_apache_2_0),
    DataLicense("foursquare", R.string.license_foursquare, R.raw.notice_foursquare_places),
)

/** Natiivikirjastojen merkinnät, jotka eivät näy Gradlen riippuvuusluettelossa. */
internal val NATIVE_LICENSES = listOf(
    DataLicense("rust", R.string.license_rust, R.raw.notice_ferrostar_rust),
)

private data class ShownText(val title: String, val text: String)

/** Aineistojen tekstit ja kaikkien kirjastojen lisenssit, jotka kootaan rakennuksessa. */
@Composable
internal fun LicensesContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var shown by remember { mutableStateOf<ShownText?>(null) }
    var openRaw by remember { mutableStateOf<DataLicense?>(null) }
    val libraries by produceState(emptyList<LibraryInfo>()) {
        value = withContext(Dispatchers.Default) {
            val read = { id: Int -> context.resources.openRawResource(id).bufferedReader().use { it.readText() } }
            parseLibraries(read(R.raw.aboutlibraries), parseNotices(read(R.raw.library_notices)))
        }
    }
    val rawTitle = openRaw?.let { stringResource(it.title) }
    LaunchedEffect(openRaw) {
        val license = openRaw ?: return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            context.resources.openRawResource(license.text).bufferedReader().use { it.readText() }
        }
        shown = ShownText(rawTitle.orEmpty(), text)
        openRaw = null
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.license_section_data), style = MaterialTheme.typography.titleMedium) }
        items(DATA_LICENSES, key = { it.key }) { license ->
            ActionRow(stringResource(license.title)) { openRaw = license }
        }
        item {
            ActionRow(stringResource(R.string.license_odbl), onClick = { runCatching { uriHandler.openUri(ODBL_URL) } })
        }
        item {
            ActionRow(stringResource(R.string.license_cc_by), onClick = { runCatching { uriHandler.openUri(CC_BY_URL) } })
        }
        item { Text(stringResource(R.string.license_section_libraries), style = MaterialTheme.typography.titleMedium) }
        items(NATIVE_LICENSES, key = { it.key }) { license ->
            ActionRow(stringResource(license.title)) { openRaw = license }
        }
        items(libraries, key = { it.id }) { library ->
            val licenses = library.licenseNames.joinToString()
            ActionRow(library.name, listOfNotNull(library.version, licenses.ifBlank { null }).joinToString(" · ")) {
                val text = library.licenseText
                if (text != null) {
                    shown = ShownText(library.name, text)
                } else {
                    library.url?.let { url -> runCatching { uriHandler.openUri(url) } }
                }
            }
        }
    }
    shown?.let { text ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(text.title) },
            text = {
                // Kappaleittain laiskana listana: Rust-kirjastojen merkinnät ovat satoja kilotavuja, ja yhtenä
                // tekstinä niiden asettelu pysäyttäisi käyttöliittymän avattaessa.
                val paragraphs = remember(text.text) { text.text.split("\n\n") }
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paragraphs) { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = Tap { shown = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private const val ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
private const val CC_BY_URL = "https://creativecommons.org/licenses/by/4.0/"
