package fi.tyomatka.ui.form

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/**
 * Tilan Saver `rememberSaveable(saver = …)`-muotoon: kelvoton tai toisen version tallenne ([inner] antaa null) johtaa
 * alkuarvoon. `rememberSaveable(stateSaver = …)` käärii sisemmän nullin tilaan `mutableStateOf(null)`, jolloin
 * alkuarvoa ei lasketa ja lomake kaatuu null-arvoon.
 */
fun <T : Any> mutableStateSaver(inner: Saver<T, Any>): Saver<MutableState<T>, Any> = Saver(
    save = { with(inner) { save(it.value) } },
    restore = { inner.restore(it)?.let(::mutableStateOf) },
)
