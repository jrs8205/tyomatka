package fi.tyomatka.ui.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.config.withProgressView
import com.stadiamaps.ferrostar.composeui.config.withRoadNameView
import com.stadiamaps.ferrostar.core.NavigationViewModel
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.nav.NavTarget
import fi.tyomatka.ui.formatKm
import fi.tyomatka.ui.map.DestroyMapOnDispose
import fi.tyomatka.ui.map.LocalMapStyleUrl
import fi.tyomatka.ui.map.MapCredit
import fi.tyomatka.ui.map.MapGeometry
import fi.tyomatka.ui.map.rememberMapLifecycleGate
import fi.tyomatka.ui.poi.PoiLayers
import fi.tyomatka.ui.poi.PoiMarker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle

/** Reittiviivan sininen kuten Kartta-välilehdellä. */
private val RouteBlue = Color(0xFF1A73E8)

/** Kohteen nasta. */
private val DestinationRed = Color(0xFFD93025)

/** Nopeuspaneelin korkein osa 100 %:n fontilla: rajoituskyltti (Ferrostarin Wienin sopimuksen kyltti, mitattu 64 dp). */
internal val SpeedPanelSize = 64.dp

/**
 * Nopeuspaneelin ja tietolähdenapin rivi kurkistusosan yläpuolella: väli ja paneeli samalla skaalalla kuin
 * [signDensity] piirtää sen, joten Ferrostarin napit, Kohteet-nappi ja vihjekortti nousevat aina paneelin yläpuolelle.
 */
internal fun bottomRowHeight(fontScale: Float): Dp = 12.dp + SpeedPanelSize * fontScale.coerceAtLeast(1f)

/**
 * Nopeuspaneelin ja tietolähdenapin etäisyys alareunasta: mitatun kurkistuskorkeuden yläpuolella väljästi, joten
 * nopeus ja rajoitus näkyvät kokonaan aina, kun alapaneeli on kiinni (myös 150 %:n fontilla ja pitkällä nimellä, kun
 * paneeli kasvaa). Paneelin pyöristetyt yläkulmat jäävät tämän alle.
 */
internal fun speedPanelBottomInset(sheetPeekHeight: Dp): Dp = sheetPeekHeight + 12.dp

/**
 * Ajokartta Ferrostarin navigointinäkymällä: kamera seuraa ajosuuntaa kallistettuna, otsikko on oma vihreä
 * [GuidanceHeader] kiinni yläpalkin reunassa, nopeus ja rajoitus [SpeedPanel]issa ja tietolähteet omassa napissa.
 * Pysäytetyssä opastuksessa näkymä saa pelkän sijainnin, joten piste jää kartalle. [sleeping] laskee kartan
 * elinkaaren CREATED-tilaan (välilehti piilossa tai verho päällä): piirto ja seuranta pysähtyvät, mutta kartta säilyy.
 * Kartta on aina vaalea kuten Kartta-välilehdellä, eikä sillä ole yötyyliä. Kartta elää Scaffoldin sisällössä järjestelmäpalkkien ulkopuolella, joten reunuksiin ei lisätä niitä.
 * Karttakohteet ([pois]) piirretään vain, kun seuranta on keskeytetty karttaa koskemalla; [onViewport] saa näkyvän
 * ikkunan silloin ja null-ikkunan, kun seuranta palaa. [recenterRequests] palauttaa seurannan (kohde lisätty).
 */
@Composable
fun DriveMap(
    viewModel: NavigationViewModel,
    destination: NavTarget?,
    sheetPeekHeight: Dp,
    sleeping: Boolean,
    modifier: Modifier = Modifier,
    pois: List<PoiMarker> = emptyList(),
    onPoiClick: (String) -> Unit = {},
    onViewport: (PoiBox?, Double) -> Unit = { _, _ -> },
    onTrackingChanged: (Boolean) -> Unit = {},
    recenterRequests: Flow<Unit> = emptyFlow(),
) {
    val gate = rememberMapLifecycleGate()
    LaunchedEffect(gate, sleeping) {
        gate.cap(if (sleeping) Lifecycle.State.CREATED else Lifecycle.State.RESUMED)
    }
    val mapState = rememberNavigationMapState()
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.isTrackingUser }.collect { tracking ->
            onTrackingChanged(tracking)
            if (tracking) onViewport(null, mapState.cameraState.position.zoom)
        }
    }
    // Näkyvä ikkuna kohteiden hakua varten, kun kamera on pysähtynyt eikä seuraa.
    LaunchedEffect(mapState) {
        snapshotFlow { Triple(mapState.cameraState.isCameraMoving, mapState.cameraState.position, mapState.isTrackingUser) }
            .filter { (moving, _, tracking) -> !moving && !tracking }
            .collect {
                val visible = mapState.cameraState.projection?.queryVisibleBoundingBox()
                onViewport(visible?.let { PoiBox(it.west, it.south, it.east, it.north) }, mapState.cameraState.position.zoom)
            }
    }
    LaunchedEffect(mapState, recenterRequests) {
        recenterRequests.collect { mapState.recenter(isNavigating = true) }
    }
    val uiState by viewModel.navigationUiState.collectAsStateWithLifecycle()
    val guidance = guidanceUi(uiState, ::formatKm)
    val showHeader = uiState.isNavigating() || guidance.offRoute
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    LaunchedEffect(showHeader) { if (!showHeader) headerHeight = 0.dp }
    // Ferrostarin omat napit (keskitys) otsikon alle ja alarivin yläpuolelle.
    val overlayPadding = PaddingValues(top = headerHeight, bottom = sheetPeekHeight + bottomRowHeight(density.fontScale))
    val views = remember {
        NavigationViewComponentBuilder.Default()
            .withInstructionsView { _, _ -> }
            .withProgressView { _, _, _ -> }
            .withRoadNameView { _, _, _ -> }
    }
    val routeOverlay = remember {
        RouteOverlayBuilder { state ->
            state.routeGeometry?.let { BorderedPolyline(points = it, color = RouteBlue, lineWidth = 8f, borderWidth = 2f) }
        }
    }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLifecycleOwner provides gate) {
            DynamicallyOrientingNavigationView(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(LocalMapStyleUrl.current),
                navigationMapState = mapState,
                viewModel = viewModel,
                config = VisualNavigationViewConfig(showMute = false, showZoom = false, showRecenter = true, speedLimitStyle = null),
                views = views,
                ornamentPadding = overlayPadding,
                overlayPadding = overlayPadding,
                routeOverlayBuilder = routeOverlay,
                mapOptions = MapOptions(ornamentOptions = OrnamentOptions.AllDisabled),
                mapContent = {
                    DestinationPin(destination)
                    // Kohteet vain seurannan tauolla: ajokuva pysyy rauhallisena.
                    if (!mapState.isTrackingUser) PoiLayers(pois, onPoiClick)
                },
            )
            DestroyMapOnDispose(gate)
        }
        if (showHeader) {
            GuidanceHeader(
                ui = guidance,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
            )
        }
        val bottomInset = speedPanelBottomInset(sheetPeekHeight)
        SpeedPanel(
            speedKmh = guidance.speedKmh,
            limitKmh = guidance.limitKmh,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomInset),
        )
        MapCredit(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset))
    }
}

@Composable
private fun DestinationPin(destination: NavTarget?) {
    val source = rememberGeoJsonSource(GeoJsonData.Features(MapGeometry.originFeature(destination?.location)))
    CircleLayer(
        id = "destination-ring",
        source = source,
        color = const(Color.White),
        radius = const(13.dp),
        strokeColor = const(DestinationRed),
        strokeWidth = const(3.dp),
    )
    CircleLayer(
        id = "destination-dot",
        source = source,
        color = const(DestinationRed),
        radius = const(6.dp),
    )
}
