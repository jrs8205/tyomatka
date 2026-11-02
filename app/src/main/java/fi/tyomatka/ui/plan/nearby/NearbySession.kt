package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.data.repo.NearbyData
import fi.tyomatka.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Kohdelistan avauksen tila: lähtöpisteen luku, ei sijaintia tai lataus lähtöpisteen ympäriltä. */
sealed interface NearbySessionState {
    data object Idle : NearbySessionState
    data object Locating : NearbySessionState
    data object NoLocation : NearbySessionState
    data class Loaded(val center: GeoPoint, val data: NearbyData) : NearbySessionState
}

/**
 * Kohdelistan avauksen ja latauksen elinkaari: [open] lukee lähtöpisteen kerran ja lataa sen ympäriltä, [close] (ikkuna
 * suljettu) peruu sijainnin luvun ja latauksen, jolloin seuraava [open] lukee sijainnin uudelleen. Latauksen odottamaton
 * virhe ei jätä latausta päälle.
 */
class NearbySession(
    private val scope: CoroutineScope,
    private val locate: suspend () -> GeoPoint?,
    private val load: (GeoPoint, PoiCategory) -> Flow<NearbyData>,
) {
    private val _state = MutableStateFlow<NearbySessionState>(NearbySessionState.Idle)
    val state: StateFlow<NearbySessionState> = _state.asStateFlow()
    private var job: Job? = null

    fun open(first: PoiCategory) {
        if (job != null) return
        _state.value = NearbySessionState.Locating
        job = scope.launch {
            val center = try {
                locate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.event("kohdelista", "sijainti ${e.javaClass.simpleName}")
                null
            }
            if (center == null) {
                Diag.event("kohdelista", "avaus ilman sijaintia")
                _state.value = NearbySessionState.NoLocation
                return@launch
            }
            Diag.event("kohdelista", String.format(Locale.ROOT, "avaus %.3f,%.3f %s", center.lat, center.lng, first.apiName))
            _state.value = NearbySessionState.Loaded(center, NearbyData(emptyMap(), emptyList(), loading = true))
            try {
                load(center, first).collect { _state.value = NearbySessionState.Loaded(center, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.event("kohdelista", "lataus ${e.javaClass.simpleName}")
                _state.update { (it as? NearbySessionState.Loaded)?.let { s -> s.copy(data = s.data.copy(unavailable = true)) } ?: it }
            }
            // Lataus päättyy aina: myös virheen tai ilman viimeistä vaihetta päättyneen latauksen jälkeen.
            _state.update { (it as? NearbySessionState.Loaded)?.let { s -> s.copy(data = s.data.copy(loading = false)) } ?: it }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = NearbySessionState.Idle
    }
}
