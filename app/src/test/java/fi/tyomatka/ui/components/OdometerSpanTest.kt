package fi.tyomatka.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mittarijanan tila: käynnissä oleva jakso ei ole virhe, päättynyt ilman loppulukemaa on. */
class OdometerSpanTest {

    @Test
    fun `molemmat lukemat on valmis`() {
        assertEquals(OdometerState.COMPLETE, odometerState(125_400, 125_486, ended = true))
    }

    @Test
    fun `nollan kilometrin jakso on valmis`() {
        assertEquals(OdometerState.COMPLETE, odometerState(125_400, 125_400, ended = true))
    }

    @Test
    fun `avoin jakso ilman loppua on kaynnissa`() {
        assertEquals(OdometerState.ONGOING, odometerState(125_400, null, ended = false))
    }

    @Test
    fun `paattynyt jakso ilman loppua puuttuu`() {
        assertEquals(OdometerState.MISSING, odometerState(125_400, null, ended = true))
    }

    @Test
    fun `alkulukeman puuttuminen on puute myos kaynnissa`() {
        assertEquals(OdometerState.MISSING, odometerState(null, null, ended = false))
        assertEquals(OdometerState.MISSING, odometerState(null, 125_486, ended = true))
    }

    @Test
    fun `loppu ennen alkua on virheellinen`() {
        assertEquals(OdometerState.INVALID, odometerState(125_486, 125_400, ended = true))
    }

    @Test
    fun `puuttuva alku ja kesken oleva loppu naytetaan erikseen`() {
        assertEquals(OdometerDisplay(OdometerState.MISSING, startMissing = true, end = OdometerEnd.IN_PROGRESS), odometerDisplay(null, null, ended = false))
    }

    @Test
    fun `puuttuva alku ja olemassa oleva loppu`() {
        assertEquals(OdometerDisplay(OdometerState.MISSING, startMissing = true, end = OdometerEnd.VALUE), odometerDisplay(null, 125_486, ended = true))
    }

    @Test
    fun `paattyneen jakson puuttuva loppu`() {
        assertEquals(OdometerDisplay(OdometerState.MISSING, startMissing = false, end = OdometerEnd.MISSING), odometerDisplay(125_400, null, ended = true))
    }

    @Test
    fun `kesken oleva ja valmis jakso`() {
        assertEquals(OdometerDisplay(OdometerState.ONGOING, startMissing = false, end = OdometerEnd.IN_PROGRESS), odometerDisplay(125_400, null, ended = false))
        assertEquals(OdometerDisplay(OdometerState.COMPLETE, startMissing = false, end = OdometerEnd.VALUE), odometerDisplay(125_400, 125_486, ended = true))
        assertEquals(OdometerDisplay(OdometerState.INVALID, startMissing = false, end = OdometerEnd.VALUE), odometerDisplay(125_486, 125_400, ended = true))
    }
}
