package fi.tyomatka.ui

import android.net.Uri
import fi.tyomatka.data.photo.PhotoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sovellukseen jaetut kuvat (latausapin kuvakaappaus suoraan sovellukseen). Kuvat pakataan heti
 * odottaviksi, koska jakavan sovelluksen antama lukuoikeus on väliaikainen; Työmatka-sivu avaa niille kirjauksen ja
 * kuluttaa ne. Lippu pysyy pystyssä, kunnes kohde kuluttaa sen (myös esittelyn ajan).
 */
@Singleton
class SharedPhotos @Inject constructor(private val processor: PhotoProcessor) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /** Jokin jaettu kuva ei ollut luettava. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun receive(sources: List<Uri>) {
        if (sources.isEmpty()) return
        scope.launch {
            val prepared = sources.mapNotNull { runCatching { processor.prepare(it) }.getOrNull() }
            if (prepared.size < sources.size) _failed.value = true
            if (prepared.isNotEmpty()) _files.update { it + prepared }
        }
    }

    /** Kohde otti kuvat lomakkeelleen. */
    fun consume(): List<File> = _files.getAndUpdate { emptyList() }

    fun consumeFailure() {
        _failed.value = false
    }
}
