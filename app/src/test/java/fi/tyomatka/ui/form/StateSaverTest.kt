package fi.tyomatka.ui.form

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StateSaverTest {
    /** Sisempi Saver hylkää muun kuin kokonaisluvun, kuten lomakkeiden Saverit hylkäävät toisen version tallenteen. */
    private val inner: Saver<Int, Any> = Saver(save = { it }, restore = { it as? Int })
    private val saver = mutableStateSaver(inner)

    @Test
    fun `kelvollinen tallenne palauttaa saman arvon tilana`() {
        val saved = assertNotNull(with(saver) { SaverScope { true }.save(mutableStateOf(7)) })
        assertEquals(7, saved)
        assertEquals(7, assertNotNull(saver.restore(saved)).value)
    }

    @Test
    fun `kelvoton tallenne antaa null, jolloin tila alkaa alusta`() {
        // rememberSaveable kutsuu init-lohkoa vain, kun restore antaa null; tilaan kääritty null kaataisi lomakkeen.
        assertNull(saver.restore("rikki"))
    }
}
