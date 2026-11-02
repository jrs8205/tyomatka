package fi.tyomatka.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import fi.tyomatka.core.location.LocationCore
import fi.tyomatka.core.location.LocationPort
import fi.tyomatka.core.location.LocationSample
import fi.tyomatka.core.location.LocationSource
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Puhelimen oma sijainti: ainoa `LocationManager`in käyttäjä, joten sovellus toimii myös ilman Google-palveluja.
 * Kutsuja tarkistaa sijaintiluvan; ilman sitä pisteitä ei vain tule.
 */
class DeviceLocation private constructor(context: Context) {
    private val core = LocationCore(
        port = AndroidLocationPort(context.getSystemService(LocationManager::class.java)),
        describe = { source, fix ->
            LocationSample(source, fix.elapsedRealtimeNanos, if (fix.hasAccuracy()) fix.accuracy else null)
        },
        nowNanos = SystemClock::elapsedRealtimeNanos,
        onSourceChanged = { Diag.event("sijainti", "lähde $it") },
    )

    /** Tarkka sijainti sekunnin välein. */
    fun updates(): Flow<Location> = core.updates()

    /** Tuore sijainti, enintään 8 s odotuksella. */
    suspend fun current(): Location? = core.current()

    /** Paras tunnettu sijainti ilman uutta hakua. */
    fun last(): Location? = core.last()

    companion object {
        @Volatile
        private var instance: DeviceLocation? = null

        /** Yksi yhteinen olio, jotta vastaukseton kertahaku muistetaan kaikille käyttäjille. */
        fun of(context: Context): DeviceLocation =
            instance ?: synchronized(this) {
                instance ?: DeviceLocation(context.applicationContext).also { instance = it }
            }
    }
}

@SuppressLint("MissingPermission")
private class AndroidLocationPort(private val manager: LocationManager) : LocationPort<Location> {
    override fun sources(): List<LocationSource> =
        LocationSource.entries.filter { LocationManagerCompat.hasProvider(manager, it.provider) }

    override fun lastKnown(source: LocationSource): Location? = manager.getLastKnownLocation(source.provider)

    override fun subscribe(source: LocationSource, onFix: (Location) -> Unit): AutoCloseable {
        val listener = LocationListenerCompat { onFix(it) }
        val request = if (source == LocationSource.NETWORK) FALLBACK else STREAM
        LocationManagerCompat.requestLocationUpdates(manager, source.provider, request, executor, listener)
        return AutoCloseable { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    override fun currentOnce(source: LocationSource, onResult: (Location?) -> Unit): AutoCloseable {
        val signal = CancellationSignal()
        LocationManagerCompat.getCurrentLocation(manager, source.provider, signal, executor) { onResult(it) }
        return AutoCloseable { signal.cancel() }
    }

    private companion object {
        val STREAM: LocationRequestCompat = LocationRequestCompat.Builder(1_000)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1_000)
            .build()

        /** `network` on vain varalla, kun virrat ovat vaienneet: harvempi ja säästävä, ettei se kuluta akkua ja verkkoa turhaan. */
        val FALLBACK: LocationRequestCompat = LocationRequestCompat.Builder(10_000)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .build()

        /** Sijaintikutsut omassa säikeessään, ettei pääsäie ruuhkaudu sekunnin välein tulevista näytteistä. */
        val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DeviceLocation").apply { isDaemon = true }
        }

        val LocationSource.provider: String
            get() = when (this) {
                LocationSource.FUSED -> "fused"
                LocationSource.GPS -> LocationManager.GPS_PROVIDER
                LocationSource.NETWORK -> LocationManager.NETWORK_PROVIDER
            }
    }
}
