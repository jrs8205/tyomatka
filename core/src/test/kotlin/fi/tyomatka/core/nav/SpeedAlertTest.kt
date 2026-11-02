package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedAlertTest {

    @Test
    fun `rajoituksen mukainen nopeus ei avaa mittaria`() {
        assertEquals(SpeedAlert.NONE, speedAlert(50, 50))
        assertEquals(SpeedAlert.NONE, speedAlert(30, 50))
    }

    @Test
    fun `ylitys avaa mittarin ja kymmenen ja kaksikymmenta kilometria ovat varoituksen rajat`() {
        assertEquals(SpeedAlert.OVER, speedAlert(51, 50))
        assertEquals(SpeedAlert.OVER, speedAlert(59, 50))
        assertEquals(SpeedAlert.MINOR, speedAlert(60, 50))
        assertEquals(SpeedAlert.MINOR, speedAlert(69, 50))
        assertEquals(SpeedAlert.MAJOR, speedAlert(70, 50))
    }

    @Test
    fun `ilman nopeutta tai rajoitusta ei varoitusta`() {
        assertEquals(SpeedAlert.NONE, speedAlert(null, 50))
        assertEquals(SpeedAlert.NONE, speedAlert(80, null))
    }

    @Test
    fun `tuntematon rajoitus nollana tai negatiivisena ei varoita`() {
        assertEquals(SpeedAlert.NONE, speedAlert(80, 0))
        assertEquals(SpeedAlert.NONE, speedAlert(80, -1))
    }
}
