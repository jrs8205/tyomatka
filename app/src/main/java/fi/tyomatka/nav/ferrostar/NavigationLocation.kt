package fi.tyomatka.nav.ferrostar

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import fi.tyomatka.location.DeviceLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.Route

/**
 * Navigoinnin sijainti: puhelimen oma sijaintivirta sekunnin välein, jota sekä Ferrostarin ydin että saapumisen
 * tunnistus lukevat. Virta elää vain ajosession ajan ([start]/[stop]). Debug-käytössä [simulate] vaihtaa lähteeksi
 * Ferrostarin simulaation reittiä pitkin; ydin saa samat pisteet kuin sovellus, joten kartta, ääni ja saapuminen
 * käyttäytyvät kuten oikealla ajolla.
 */
class NavigationLocation(context: Context) : NavigationLocationProviding {
    private val device = DeviceLocation.of(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Session lähde: oikea virta tai simulaatio. Vaihto kesken session siirtää kuuntelijat uuteen lähteeseen. */
    private val source = MutableStateFlow<Flow<Location>?>(null)
    private val _fixes = MutableStateFlow<Location?>(null)

    /** Viimeisin raaka sijainti; null ennen ensimmäistä pistettä ja session jälkeen. */
    val fixes: StateFlow<Location?> = _fixes.asStateFlow()

    private var collectJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (collectJob != null) return
        source.value = realUpdates()
        collectJob = scope.launch {
            source.flatMapLatest { it ?: emptyFlow() }.collect { _fixes.value = it }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        source.value = null
        _fixes.value = null
    }

    /** Debug: pisteet reittiä pitkin kaksinkertaisella nopeudella; null palauttaa oikean sijainnin. */
    fun simulate(route: Route?) {
        if (collectJob == null) return
        source.value = if (route == null) realUpdates() else simulatedUpdates(route)
    }

    /** Reitin lopussa simulaatio vaikenee: perillä seisova auto antaa silti tuoreita pisteitä seuraavaa reittihakua varten. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun simulatedUpdates(route: Route): Flow<Location> =
        SimulatedLocationProvider(scope, warpFactor = SIMULATION_WARP).apply { setRoute(route) }
            .locationUpdates()
            .transformLatest { fix ->
                emit(fix)
                while (true) {
                    delay(SIMULATION_IDLE_MS)
                    emit(
                        Location(fix).apply {
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            time = System.currentTimeMillis()
                        }
                    )
                }
            }

    /** Viimeisin tunnettu piste: oma tuore näyte tai puhelimen viimeisin. */
    override suspend fun lastLocation(): Location? =
        _fixes.value?.takeIf { ageMs(it) <= LAST_LOCATION_MAX_AGE_MS } ?: withContext(Dispatchers.Default) { device.last() }

    override fun locationUpdates(intervalMillis: Long): Flow<Location> = fixes.filterNotNull()

    /**
     * Tuore piste reittihakuun: enintään [maxAgeMs] vanha heti tai [waitMs] sisällä, muuten viimeisin tunnettu, jos se
     * on alle kahden minuutin ikäinen. Null, kun sijaintia ei ole (reittihaku yrittää myöhemmin uudelleen).
     */
    suspend fun freshFix(maxAgeMs: Long = FRESH_MAX_AGE_MS, waitMs: Long = FRESH_WAIT_MS): Location? {
        _fixes.value?.takeIf { ageMs(it) <= maxAgeMs }?.let { return it }
        withTimeoutOrNull(waitMs) { fixes.filterNotNull().first { ageMs(it) <= maxAgeMs } }?.let { return it }
        return lastLocation()?.takeIf { ageMs(it) <= LAST_LOCATION_MAX_AGE_MS }
    }

    /** Sijaintipalvelun virhe: ei pisteitä, reittihaku ilmoittaa sijainnin puuttumisesta. */
    private fun realUpdates(): Flow<Location> = device.updates().catch { }

    private companion object {
        const val FRESH_MAX_AGE_MS = 10_000L
        const val FRESH_WAIT_MS = 15_000L
        const val LAST_LOCATION_MAX_AGE_MS = 120_000L
        const val SIMULATION_IDLE_MS = 2_000L
        val SIMULATION_WARP = 2u

        fun ageMs(location: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
    }
}
