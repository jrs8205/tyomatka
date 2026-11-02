package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrivalDetectorTest {

    private fun fix(
        nowMs: Long,
        remainingRouteM: Double? = null,
        straightLineM: Double = 500.0,
        speedMps: Double? = 1.0,
        accuracyM: Double = 10.0,
        fixTimeMs: Long = nowMs,
        latitude: Double = 61.4978,
        longitude: Double = 23.7610,
    ) = ArrivalFix(nowMs, fixTimeMs, latitude, longitude, accuracyM, speedMps, remainingRouteM, straightLineM)

    private fun detector(legStartMs: Long = 0) = ArrivalDetector().apply { startLeg(legStartMs) }

    @Test
    fun `reitin loppu hitaasti ajaen on saapuminen kerran legia kohden`() {
        val d = detector()

        assertTrue(d.onFix(fix(11_000, remainingRouteM = 20.0, straightLineM = 20.0, speedMps = 2.0)))
        assertFalse(d.onFix(fix(12_000, remainingRouteM = 15.0, speedMps = 1.0)))
    }

    @Test
    fun `uusi legi sallii taas saapumisen`() {
        val d = detector()
        assertTrue(d.onFix(fix(11_000, remainingRouteM = 20.0, straightLineM = 20.0)))

        d.startLeg(20_000)

        assertTrue(d.onFix(fix(31_000, remainingRouteM = 10.0, straightLineM = 10.0)))
    }

    @Test
    fun `alle kymmenen sekunnin legi ei saavu`() {
        assertFalse(detector().onFix(fix(9_000, remainingRouteM = 5.0, speedMps = 0.0)))
    }

    @Test
    fun `reittia yli 50 metria ei ole saapuminen`() {
        assertFalse(detector().onFix(fix(11_000, remainingRouteM = 55.0, straightLineM = 60.0, speedMps = 2.0)))
    }

    @Test
    fun `reitin loppu 50 metrissa ajovauhdissa on saapuminen`() {
        // Perillä 50 m:ssä, jotta ilmoitus ehtii ennen taloa.
        assertTrue(detector().onFix(fix(11_000, remainingRouteM = 45.0, straightLineM = 60.0, speedMps = 7.0)))
    }

    @Test
    fun `liian kova vauhti reitin lopussa ei ole saapuminen`() {
        assertFalse(detector().onFix(fix(11_000, remainingRouteM = 45.0, straightLineM = 60.0, speedMps = 9.0)))
    }

    @Test
    fun `reitin loppu ei saavu kun kohde on linnuntietä kaukana`() {
        // Sivutielle poikettaessa reittiprojektio voi näyttää nollaa, vaikka kohde on satojen metrien päässä.
        assertFalse(detector().onFix(fix(11_000, remainingRouteM = 0.0, straightLineM = 334.0, speedMps = 1.0, accuracyM = 5.0)))
        assertTrue(detector().onFix(fix(11_000, remainingRouteM = 10.0, straightLineM = 60.0, speedMps = 1.0, accuracyM = 5.0)))
    }

    @Test
    fun `reitin loppu kelpaa 50 metrin tarkkuudella`() {
        assertTrue(detector().onFix(fix(11_000, remainingRouteM = 20.0, straightLineM = 20.0, speedMps = 2.0, accuracyM = 50.0)))
    }

    @Test
    fun `linnuntiella saavutaan kahdeksan sekunnin hitaan oleskelun jalkeen`() {
        val d = detector()
        for (t in 20_000L..27_000L step 1_000) {
            assertFalse(d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.5)), "t=$t")
        }
        assertTrue(d.onFix(fix(28_000, straightLineM = 35.0, speedMps = 1.5)))
    }

    @Test
    fun `nopea hetki aloittaa linnuntien laskennan alusta`() {
        val d = detector()
        for (t in 20_000L..23_000L step 1_000) d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.5))
        assertFalse(d.onFix(fix(24_000, straightLineM = 35.0, speedMps = 3.0)))
        for (t in 25_000L..32_000L step 1_000) {
            assertFalse(d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.5)), "t=$t")
        }
        assertTrue(d.onFix(fix(33_000, straightLineM = 35.0, speedMps = 1.5)))
    }

    @Test
    fun `linnuntie ei saavu kun reittia on jaljella yli sata metria`() {
        val d = detector()
        for (t in 20_000L..30_000L step 1_000) {
            assertFalse(d.onFix(fix(t, remainingRouteM = 400.0, straightLineM = 35.0, speedMps = 1.5)), "t=$t")
        }

        val e = detector()
        for (t in 20_000L..27_000L step 1_000) e.onFix(fix(t, remainingRouteM = 80.0, straightLineM = 35.0, speedMps = 1.5))
        assertTrue(e.onFix(fix(28_000, remainingRouteM = 80.0, straightLineM = 35.0, speedMps = 1.5)))
    }

    @Test
    fun `linnuntie vaatii 25 metrin tarkkuuden`() {
        val d = detector()
        for (t in 20_000L..30_000L step 1_000) {
            assertFalse(d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.5, accuracyM = 30.0)), "t=$t")
        }
    }

    @Test
    fun `vanha sijainti ei tuota saapumista tunnelissakaan`() {
        assertFalse(detector().onFix(fix(30_000, remainingRouteM = 5.0, speedMps = 0.0, fixTimeMs = 15_000)))
    }

    @Test
    fun `tulevaisuuteen leimattu sijainti hylataan mutta sekunnin kellovirhe sallitaan`() {
        assertFalse(detector().onFix(fix(30_000, remainingRouteM = 5.0, speedMps = 0.0, fixTimeMs = 31_001)))
        assertTrue(detector().onFix(fix(30_000, remainingRouteM = 5.0, straightLineM = 5.0, speedMps = 0.0, fixTimeMs = 31_000)))
    }

    @Test
    fun `epatarkka sijainti ei tuota saapumista`() {
        assertFalse(detector().onFix(fix(11_000, remainingRouteM = 5.0, speedMps = 0.0, accuracyM = 60.0)))
    }

    @Test
    fun `tuntematon nopeus ei laukaise reittihaaraa mutta paikallaan linnuntie saapuu`() {
        val d = detector()
        assertFalse(d.onFix(fix(20_000, remainingRouteM = 10.0, straightLineM = 100.0, speedMps = null)))

        val e = detector()
        for (t in 20_000L..27_000L step 1_000) e.onFix(fix(t, straightLineM = 35.0, speedMps = null))
        assertEquals(true, e.onFix(fix(28_000, straightLineM = 35.0, speedMps = null)))
    }

    @Test
    fun `tuntemattomalla nopeudella liikkuva sijainti ei saavu linnuntiella`() {
        val d = detector()
        // Noin 20 m siirtymä 8 sekunnissa pohjoiseen (0.00018° ≈ 20 m) ilman nopeustietoa.
        for (i in 0..8) {
            val fix = fix(20_000 + i * 1_000L, straightLineM = 35.0, speedMps = null, latitude = 61.4978 + i * 0.00003)
            assertFalse(d.onFix(fix), "i=$i")
        }
    }

    @Test
    fun `viipyminen mitataan sijainnin ajalla eika kasittelyhetkella`() {
        val d = detector()
        // Sama sijaintinäyte luetaan uudelleen kymmenen sekunnin ajan: kello kuluu, sijainti ei.
        for (t in 20_000L..30_000L step 1_000) {
            assertFalse(d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.0, fixTimeMs = 20_000)), "t=$t")
        }
    }

    @Test
    fun `ferrostarin matka valmis odottaa legin vahimmaiskeston`() {
        val d = detector()

        assertFalse(d.onTripComplete(2_000), "legi on kestänyt vasta 2 s")
        assertFalse(d.onFix(fix(9_000, remainingRouteM = 10.0, speedMps = 1.0)))
        assertTrue(d.onFix(fix(10_000, remainingRouteM = 500.0, straightLineM = 900.0, speedMps = 10.0)), "lukittu saapuminen laukeaa")
        assertFalse(d.onFix(fix(11_000, remainingRouteM = 5.0, speedMps = 0.0)), "vain kerran")
    }

    @Test
    fun `ferrostarin matka valmis vahimmaiskeston jalkeen on saapuminen heti`() {
        val d = detector()

        assertTrue(d.onTripComplete(12_000))
        assertFalse(d.onTripComplete(13_000))
    }

    @Test
    fun `uusi legi unohtaa lukitun matka valmis -tilan`() {
        val d = detector()
        d.onTripComplete(2_000)

        d.startLeg(20_000)

        assertFalse(d.onFix(fix(31_000, remainingRouteM = 500.0, straightLineM = 900.0, speedMps = 10.0)))
    }

    @Test
    fun `nayte jonka aikaleima ei etene kasitellaan vain kerran`() {
        val d = detector()
        d.onFix(fix(20_000, straightLineM = 35.0, speedMps = 1.0))
        // Toistettu näyte nopeana ei saa nollata viipymistä.
        assertFalse(d.onFix(fix(21_000, straightLineM = 35.0, speedMps = 5.0, fixTimeMs = 20_000)))
        for (t in 21_000L..27_000L step 1_000) {
            assertFalse(d.onFix(fix(t, straightLineM = 35.0, speedMps = 1.0)), "t=$t")
        }
        assertTrue(d.onFix(fix(28_000, straightLineM = 35.0, speedMps = 1.0)))
    }
}
