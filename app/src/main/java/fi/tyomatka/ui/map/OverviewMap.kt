package fi.tyomatka.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import fi.tyomatka.R
import fi.tyomatka.diag.Diag
import fi.tyomatka.ui.Tap
import fi.tyomatka.core.api.Defaults
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.ui.poi.PoiLayers
import fi.tyomatka.ui.poi.PoiMarker
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.rememberNullLocationProvider
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.inMeters
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private val RouteBlue = Color(0xFF1A73E8)
private val OtherRouteGray = Color(0xFF6B7280)
private val OriginGreen = Color(0xFF188038)

/** Helsinki — kameran aloituskohta ennen ensimmäistä sijaintia tai stoppia. */
private val DefaultTarget = Position(24.94, 60.17)

/** Seurannan zoomi: katunäkymä, jossa seuraava risteys ehtii näkyä. */
private const val FOLLOW_ZOOM = 16.0

/** Keskitysnappi istuu MapLibre-logon yläpuolella; Kohteet-nappi sen yläpuolella. */
internal val RecenterBottomPadding = 48.dp
/**
 * Koko reitin sovituksen reunukset: ylhäällä yhteenvetokortti, alhaalla Järjestys-nappi ja kohdistusnappi.
 * Stopit eivät saa jäädä niiden alle, eivät järjestyksen vaihdon jälkeenkään.
 */
private val FitPadding = PaddingValues(start = 48.dp, top = 220.dp, end = 48.dp, bottom = 120.dp)

/** Nopeuskapseli nappirivin (keskitys, Järjestys) yläpuolella, ettei se jaa tilaa niiden kanssa. */
private val SpeedBottomPadding = 84.dp

/** Seurannan kamera-animaatio on sijaintinäytteiden välin (1 s) mittainen, jotta liike on tasaista. */
private val FollowAnimation = 1000.milliseconds

/**
 * Päivän reitti kartalla: vaalea OpenFreeMap-tyyli, reittiviiva, numeroidut stopit,
 * lähtöpaikka ja oma sijainti. Stopeilla välilehti avautuu vapaana koko reittiin sovitettuna (stopit, lähtöpaikka ja
 * oma sijainti), ilman stoppeja se seuraa omaa sijaintia heti, ja stoppien muutos
 * sovittaa reitin uudelleen myös seurannassa. Keskitysnappi
 * käynnistää seurannan, jossa kamera pysyy omassa sijainnissa ajosuunta ylöspäin ja nopeus näkyy; ele lopettaa
 * sen. Sijaintinuoli päivittyy kaikissa tiloissa. Säännöt ovat [OverviewCamera]ssa.
 */
@Composable
fun OverviewMap(
    stops: List<StopEntity>,
    origin: GeoPoint?,
    routePoints: List<GeoPoint>,
    modifier: Modifier = Modifier,
    /** Piirrettävä reitti; [routePoints] ohjaa vain kameraa, jottei reittivalinnan vaihto siirrä karttaa. */
    drawnRoute: List<GeoPoint> = routePoints,
    /** Vertailun toinen reitti harmaana valitun alla. */
    otherRoute: List<GeoPoint> = emptyList(),
    onLocationGranted: () -> Unit = {},
    pois: List<PoiMarker> = emptyList(),
    onPoiClick: (String) -> Unit = {},
    onViewport: (PoiBox?, Double) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val cameraState = rememberCameraState(CameraPosition(target = DefaultTarget, zoom = 10.0))
    // Ei tallentuva: jokainen välilehden avaus (ja prosessin palautus) alkaa alusta: ilman stoppeja seuranta, stopeilla
    // koko reitti.
    val camera = remember { OverviewCamera() }
    var mode by remember { mutableStateOf(camera.mode) }

    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    // Lupa on voitu myöntää järjestelmäasetuksista sovelluksen ollessa taustalla.
    LifecycleResumeEffect(Unit) {
        locationGranted = hasLocationPermission(context)
        onPauseOrDispose {}
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            locationGranted = true
            camera.onFollow()
            mode = camera.mode
            Diag.event("kartta", "tila $mode syy=lupa")
            onLocationGranted()
        }
    }

    val locationProvider = if (locationGranted) rememberDeviceMapLocationProvider() else rememberNullLocationProvider()
    val userLocation = rememberUserLocationState(locationProvider)

    suspend fun animateTo(fit: Fit) {
        val box = MapGeometry.boundingBoxOf(fit.points) ?: return
        cameraState.animateTo(boundingBox = box, padding = FitPadding, duration = 600.milliseconds)
    }

    // Datan muutos sovittaa aina (myös seurannassa) ja päättää seurannan; oma sijainti otetaan mukaan, jos se on jo
    // tiedossa. Avaimena ovat stoppien tunnisteet, järjestys ja sijainnit: tilan vaihto (saapunut) ajon aikana ei
    // saa lopettaa seurantaa.
    val stopPoints = stops.map { GeoPoint(it.lat, it.lng) }
    val stopSignature = stops.map { Triple(it.id, it.lat, it.lng) }
    LaunchedEffect(stopSignature) {
        val fit = camera.onData(stopPoints, origin, userLocation.location?.toGeoPoint())
        mode = camera.mode
        Diag.event("kartta", "tila $mode syy=avaus stoppeja ${stopPoints.size}")
        fit?.let { animateTo(it) }
    }

    // Lähtöpaikka ja reittiviiva päivittyvät taustalla (stopin tilan vaihto hakee reitin uudelleen): vain koskematon
    // vapaa kartta sovitetaan, seuranta säilyy.
    LaunchedEffect(origin, routePoints) {
        camera.onBackgroundData(stopPoints, origin, userLocation.location?.toGeoPoint())?.let { animateTo(it) }
    }

    // Ensimmäinen sijainti avauksen jälkeen sovitetaan mukaan kerran; myöhemmät näytteet eivät liikuta kameraa.
    // Luvan myöntäminen vaihtaa sijaintilähteen, joten efekti käynnistyy luvan mukaan uudelleen (kamera sovittaa vain kerran).
    val currentStops by rememberUpdatedState(stopPoints)
    val currentOrigin by rememberUpdatedState(origin)
    LaunchedEffect(locationGranted) {
        val location = snapshotFlow { userLocation.location }.filterNotNull().first()
        camera.onFirstLocation(location.toGeoPoint(), currentStops, currentOrigin)?.let { animateTo(it) }
    }

    // Näkyvä ikkuna ja zoom kohteiden hakua varten aina, kun kameran liike päättyy (myös ohjelmallinen sovitus).
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.isCameraMoving to cameraState.position }
            .filter { (moving, _) -> !moving }
            .collect {
                val visible = cameraState.projection?.queryVisibleBoundingBox()
                onViewport(visible?.let { PoiBox(it.west, it.south, it.east, it.north) }, cameraState.position.zoom)
            }
    }

    // Jokainen ele (myös odotettaessa ensimmäistä sijaintia) lopettaa seurannan.
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.isCameraMoving to cameraState.moveReason }
            .collect { (moving, reason) ->
                if (moving && reason == CameraMoveReason.GESTURE) {
                    camera.onGesture()
                    mode = camera.mode
                    Diag.event("kartta", "tila $mode syy=ele")
                }
            }
    }

    // Oma seuranta: kirjaston LocationTrackingEffect (0.13.0) ei havaitse sijainnin
    // muuttumista eikä laske suuntaa oikein. Ensimmäinen näyte asettaa myös zoomin,
    // joten seuranta alkaa samalla tavalla riippumatta siitä, mitä kautta se käynnistyi.
    LaunchedEffect(mode) {
        if (mode != CameraMode.FOLLOW) return@LaunchedEffect
        snapshotFlow { userLocation.location }
            .filterNotNull()
            .collect { location ->
                cameraState.animateTo(followPosition(location, cameraState.position.bearing), FollowAnimation)
            }
    }

    Box(modifier) {
        val lifecycleGate = rememberMapLifecycleGate()
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycleGate) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                // Kartta on aina vaalea myös tummassa teemassa: tumma karttapohja on
                // ajossa vaikealukuinen, ja vaalea vastaa edeltäjän karttaa.
                baseStyle = BaseStyle.Uri(LocalMapStyleUrl.current),
                cameraState = cameraState,
                // Oma näkyvä merkintä korvaa kartan i-napin ja logon, jotka jäisivät nappien alle. Mittakaavapalkki
                // lukee yksikkönsä oletuskielestä, joten se näytetään vain metrisenä.
                options = MapOptions(
                    ornamentOptions = OrnamentOptions(
                        isLogoEnabled = false,
                        isAttributionEnabled = false,
                        isScaleBarEnabled = scaleBarIsMetric(Locale.getDefault()),
                    ),
                ),
            ) {
                // Lähteet luodaan kartan tyylikompositiossa — sen ulkopuolella ei ole tyylisolmua.
                val stopSource = rememberGeoJsonSource(GeoJsonData.Features(MapGeometry.stopFeatures(stops)))
                val originSource = rememberGeoJsonSource(GeoJsonData.Features(MapGeometry.originFeature(origin)))
                // Sama data säilyy: uusi olio lähettäisi tuhansien pisteiden viivat uudelleen joka sijaintinäytteellä.
                val routeData = remember(drawnRoute) { GeoJsonData.Features(MapGeometry.routeFeature(drawnRoute)) }
                val otherRouteData = remember(otherRoute) { GeoJsonData.Features(MapGeometry.routeFeature(otherRoute)) }
                val routeSource = rememberGeoJsonSource(routeData)
                val otherRouteSource = rememberGeoJsonSource(otherRouteData)

                // Kohteet reitin ja stoppien alle.
                PoiLayers(pois, onPoiClick)
                LineLayer(
                    id = "route-other",
                    source = otherRouteSource,
                    color = const(OtherRouteGray),
                    width = const(4.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                LineLayer(
                    id = "route-casing",
                    source = routeSource,
                    color = const(Color.White),
                    width = const(9.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                LineLayer(
                    id = "route",
                    source = routeSource,
                    color = const(RouteBlue),
                    width = const(5.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                CircleLayer(
                    id = "origin",
                    source = originSource,
                    color = const(OriginGreen),
                    radius = const(8.dp),
                    strokeColor = const(Color.White),
                    strokeWidth = const(2.dp),
                )
                CircleLayer(
                    id = "stops",
                    source = stopSource,
                    color = const(RouteBlue),
                    radius = const(14.dp),
                    strokeColor = const(Color.White),
                    strokeWidth = const(2.dp),
                )
                SymbolLayer(
                    id = "stop-numbers",
                    source = stopSource,
                    textField = format(span(Feature["n"].asString())),
                    textFont = const(listOf("Noto Sans Bold")),
                    textSize = const(14.sp),
                    textColor = const(Color.White),
                    textAllowOverlap = const(true),
                    textIgnorePlacement = const(true),
                )
                LocationPuck(
                    idPrefix = "me",
                    location = userLocation.location,
                    cameraState = cameraState,
                    bearing = userLocation.location?.course,
                )
            }
            DestroyMapOnDispose(lifecycleGate)
        }

        MapCredit(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp))

        val following = mode == CameraMode.FOLLOW
        SmallFloatingActionButton(
            onClick = Tap {
                if (!locationGranted) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                    return@Tap
                }
                camera.onFollow()
                mode = camera.mode
                Diag.event("kartta", "tila $mode syy=nappi")
            },
            containerColor = if (following) {
                MaterialTheme.colorScheme.primary
            } else {
                FloatingActionButtonDefaults.containerColor
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = RecenterBottomPadding),
        ) {
            Icon(
                if (following) Icons.Filled.Navigation else Icons.Filled.MyLocation,
                contentDescription = stringResource(if (following) R.string.map_following else R.string.map_recenter),
            )
        }

        val speedKmh = userLocation.location?.let {
            MapFollow.speedKmh(it.speed?.distancePerSecond?.inMeters, it.position.accuracy?.inMeters)
        }
        if (MapFollow.showSpeed(mode, speedKmh)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SpeedBottomPadding),
            ) {
                Text(
                    stringResource(R.string.map_speed_kmh, requireNotNull(speedKmh)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun Location.toGeoPoint() = GeoPoint(position.value.latitude, position.value.longitude)

/** Seuranta-asento: oma sijainti keskellä, ajosuunta ylös; paikallaan suunta säilyy. */
private fun followPosition(location: Location, currentBearing: Double): CameraPosition = CameraPosition(
    target = location.position.value,
    zoom = FOLLOW_ZOOM,
    bearing = MapFollow.cameraBearing(location.course?.value) ?: currentBearing,
)
