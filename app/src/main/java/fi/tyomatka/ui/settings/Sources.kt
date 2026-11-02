package fi.tyomatka.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tyomatka.R
import fi.tyomatka.ui.Tap

internal data class SourceLink(@param:StringRes val label: Int, val url: String)

/** Palvelu tai aineisto: [hosts] kertoo, mitkä palvelimet merkintä kattaa, jotta uusi palvelin ei jää ilman merkintää. */
internal data class Source(
    val name: String,
    @param:StringRes val text: Int,
    val links: List<SourceLink>,
    val hosts: List<String> = emptyList(),
    /** Kielikohtainen nimi; muuten [name]. */
    @param:StringRes val nameRes: Int? = null,
)

internal val SOURCES: List<Source> = listOf(
    Source(
        "OpenStreetMap",
        R.string.source_osm,
        listOf(
            SourceLink(R.string.map_attribution_osm, "https://www.openstreetmap.org/copyright"),
            SourceLink(R.string.map_fix_map, "https://www.openstreetmap.org/fixthemap"),
        ),
    ),
    Source(
        "OpenFreeMap",
        R.string.source_tiles,
        listOf(SourceLink(R.string.source_link_site, "https://openfreemap.org/")),
        hosts = listOf("tiles.openfreemap.org"),
    ),
    Source(
        "OpenMapTiles",
        R.string.source_tiles,
        listOf(SourceLink(R.string.source_link_site, "https://openmaptiles.org/")),
    ),
    Source(
        "Valhalla, OSRM (FOSSGIS)",
        R.string.source_routing,
        listOf(SourceLink(R.string.source_link_site, "https://www.fossgis.de/")),
        hosts = listOf("valhalla1.openstreetmap.de", "routing.openstreetmap.de"),
    ),
    Source(
        "Digitransit",
        R.string.source_digitransit,
        listOf(SourceLink(R.string.source_link_site, "https://digitransit.fi/")),
    ),
    Source(
        "Photon",
        R.string.source_photon,
        listOf(SourceLink(R.string.source_link_site, "https://photon.komoot.io/")),
        hosts = listOf("photon.komoot.io"),
    ),
    Source(
        "Overture Maps",
        R.string.source_overture,
        listOf(SourceLink(R.string.source_link_site, "https://docs.overturemaps.org/attribution/")),
    ),
    Source(
        "Fintraffic",
        R.string.source_fintraffic,
        listOf(
            SourceLink(R.string.source_link_site, "https://www.digitraffic.fi/"),
            SourceLink(R.string.source_link_license, "https://creativecommons.org/licenses/by/4.0/"),
        ),
    ),
    Source(
        "Work Trip",
        R.string.source_own,
        emptyList(),
        hosts = listOf("poi.jrs8205.com"),
        nameRes = R.string.app_name,
    ),
)

/** Lähdemerkinnät asetuksissa ja kartan merkinnän takana. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesContent(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SOURCES.forEach { source ->
            Column {
                Text(source.nameRes?.let { stringResource(it) } ?: source.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(source.text), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    source.links.forEach { link ->
                        TextButton(onClick = Tap { runCatching { uriHandler.openUri(link.url) } }) {
                            Text(stringResource(link.label))
                        }
                    }
                }
            }
        }
    }
}
