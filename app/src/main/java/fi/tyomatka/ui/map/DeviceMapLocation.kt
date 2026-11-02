package fi.tyomatka.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import fi.tyomatka.location.DeviceLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.asMapLibreLocation

/**
 * Viimeisin tunnettu piste heti, jotta kartta ei odota ensimmäistä uutta näytettä, ja sen jälkeen uudet näytteet.
 * Sijaintipalvelun virhe ei kaada Kartta-välilehteä: piste jää pois.
 */
internal fun <F : Any> lastThenUpdates(last: () -> F?, updates: Flow<F>): Flow<F?> = flow<F?> {
    emit(
        try {
            last()
        } catch (e: RuntimeException) {
            null
        },
    )
    emitAll(updates)
}.catch { emit(null) }

/** Tilaus elää kerääjän mukana, ja lyhyt välilehden vaihto ei katkaise sitä. Sijaintipalvelun kutsut pääsäikeen ulkopuolella. */
internal fun <F : Any, T : Any> locationState(
    last: () -> F?,
    updates: Flow<F>,
    convert: (F) -> T,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): StateFlow<T?> =
    lastThenUpdates(last, updates)
        .map { it?.let(convert) }
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000), null)

/** Kartan sijaintipiste puhelimen omasta sijainnista. Kutsuja tarkistaa sijaintiluvan ennen käyttöä. */
class DeviceMapLocationProvider(device: DeviceLocation, scope: CoroutineScope) : LocationProvider {
    override val location: StateFlow<Location?> =
        locationState(device::last, device.updates(), { it.asMapLibreLocation() }, scope)
}

/** Sekunnin välein päivittyvä tarkka sijainti kartan sijaintipisteelle. */
@Composable
fun rememberDeviceMapLocationProvider(): LocationProvider {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) { DeviceMapLocationProvider(DeviceLocation.of(context), scope) }
}
