package fi.tyomatka.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifikaatiosta tulevat UI-pyynnöt: lippu pysyy pystyssä kunnes kohde
 * kuluttaa sen, joten järjestys navigoinnin ja ruudun luonnin välillä ei
 * ole herkkä.
 */
@Singleton
class UiEvents @Inject constructor() {
    private val _openWorkStart = MutableStateFlow(false)
    val openWorkStart: StateFlow<Boolean> = _openWorkStart.asStateFlow()

    fun requestOpenWorkStart() {
        _openWorkStart.value = true
    }

    fun consumeOpenWorkStart() {
        _openWorkStart.value = false
    }
}
