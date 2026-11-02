package fi.tyomatka

import fi.tyomatka.ScreenAwakeController.Companion.DARK_DEBOUNCE_MS
import fi.tyomatka.ScreenAwakeController.Companion.TOUCH_HOLD_MS
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenAwakeControllerTest {

    private class FakePort : ScreenAwakePort {
        var screenOn = false
        var dim = false
        var lightListening = false
        override fun setKeepScreenOn(on: Boolean) { screenOn = on }
        override fun setDimmed(dimmed: Boolean) { dim = dimmed }
        override fun startLightListening() { lightListening = true }
        override fun stopLightListening() { lightListening = false }
    }

    private val dark = 0f
    private val band = 20f
    private val bright = 100f

    @Test
    fun `ajossa naytto pysyy paalla ja pimeys himmentaa vasta debouncen jalkeen`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        assertTrue(port.screenOn)
        assertTrue(port.lightListening)

        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS - 1)
        runCurrent()
        assertFalse(port.dim)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(port.dim)
    }

    @Test
    fun `lyhyt pimeys ei himmenna`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)

        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS - 500)
        runCurrent()
        c.onLux(bright)
        advanceTimeBy(DARK_DEBOUNCE_MS * 3)
        runCurrent()
        assertFalse(port.dim)
    }

    @Test
    fun `valo kirkastaa himmennetyn heti`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)

        c.onLux(bright)
        assertFalse(port.dim)
    }

    @Test
    fun `valialue peruu vireilla olevan himmennyksen mutta ei kirkasta`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)

        // Hämärä keskeyttää alkaneen pimeysjakson.
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS - 500)
        runCurrent()
        c.onLux(band)
        advanceTimeBy(DARK_DEBOUNCE_MS * 3)
        runCurrent()
        assertFalse(port.dim)

        // Himmennettynä hämärä ei kirkasta (hystereesi).
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)
        c.onLux(band)
        runCurrent()
        assertTrue(port.dim)
    }

    @Test
    fun `himmennettyna kosketus kulutetaan ja pito estaa uuden himmennyksen`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)

        assertTrue(c.onTouch())
        assertFalse(port.dim)

        // Pimeys jatkuu, mutta pidon aikana ei himmene.
        c.onLux(dark)
        advanceTimeBy(TOUCH_HOLD_MS - 1)
        runCurrent()
        assertFalse(port.dim)

        // Pidon päätyttyä viimeisin lux arvioidaan uudelleen ilman uutta
        // anturitapahtumaa (on-change-anturi ei toista samaa arvoa).
        advanceTimeBy(2)
        runCurrent()
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)
    }

    @Test
    fun `kirkkaana kosketusta ei kuluteta`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(bright)
        assertFalse(c.onTouch())
    }

    @Test
    fun `ajon paattyessa kaikki palautuu`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)

        c.onDrivingChanged(false)
        assertFalse(port.screenOn)
        assertFalse(port.dim)
        assertFalse(port.lightListening)

        advanceTimeBy(DARK_DEBOUNCE_MS * 3)
        runCurrent()
        assertFalse(port.dim)
    }

    @Test
    fun `taustalla kirkkaus palautuu ja edustalla kuuntelu jatkuu`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)

        c.onBackground()
        assertFalse(port.dim)
        assertFalse(port.lightListening)

        c.onForeground()
        assertTrue(port.lightListening)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)
    }

    @Test
    fun `ilman ajoa ei kuuntelua eika himmennysta`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        assertFalse(port.screenOn)
        assertFalse(port.lightListening)
        assertFalse(c.onTouch())
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS * 3)
        runCurrent()
        assertFalse(port.dim)
    }

    @Test
    fun `dimmedFlow julkaisee himmennystilan`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        assertFalse(c.dimmedFlow.value)

        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(c.dimmedFlow.value)

        c.onLux(bright)
        assertFalse(c.dimmedFlow.value)
    }

    @Test
    fun `ajo voi alkaa taustalla ja kuuntelu alkaa vasta edustalla`() = runTest {
        // Prosessin herätessä kesken ajon collect ehtii ajaa ennen onStartia.
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onDrivingChanged(true)
        assertTrue(port.screenOn)
        assertFalse(port.lightListening)

        c.onForeground()
        assertTrue(port.lightListening)
    }

    @Test
    fun `pimennys pois päältä ei himmennä mutta näyttö pysyy hereillä`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.setDimEnabled(false)
        c.onForeground()
        c.onDrivingChanged(true)

        assertTrue(port.screenOn)
        assertFalse(port.lightListening)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertFalse(port.dim)
    }

    @Test
    fun `pimennyksen kytkeminen pois kirkastaa heti`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.onForeground()
        c.onDrivingChanged(true)
        c.onLux(dark)
        advanceTimeBy(DARK_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(port.dim)

        c.setDimEnabled(false)

        assertFalse(port.dim)
        assertFalse(port.lightListening)
    }

    @Test
    fun `pimennyksen kytkeminen takaisin alkaa kuunnella ajossa`() = runTest {
        val port = FakePort()
        val c = ScreenAwakeController(port, backgroundScope)
        c.setDimEnabled(false)
        c.onForeground()
        c.onDrivingChanged(true)

        c.setDimEnabled(true)

        assertTrue(port.lightListening)
    }
}
