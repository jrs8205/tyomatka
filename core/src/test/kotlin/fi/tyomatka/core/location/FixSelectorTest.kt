package fi.tyomatka.core.location

import fi.tyomatka.core.location.LocationSource.FUSED
import fi.tyomatka.core.location.LocationSource.GPS
import fi.tyomatka.core.location.LocationSource.NETWORK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixSelectorTest {
    private val selector = FixSelector()

    /** Realistinen käynnistyksestä kulunut aika, ei nolla. */
    private val start = 432_000_000_000_000L

    private fun at(seconds: Double) = start + (seconds * 1_000_000_000).toLong()

    private fun sample(source: LocationSource, seconds: Double, accuracy: Float?) = LocationSample(source, at(seconds), accuracy)

    private fun accept(source: LocationSource, seconds: Double, accuracy: Float?) = selector.accept(sample(source, seconds, accuracy))

    @Test
    fun `ensimmäinen näyte kelpaa`() {
        assertNull(selector.active)
        assertTrue(accept(GPS, 0.0, 10f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `tarkka fused ohjaa eikä gpstä julkaista`() {
        assertTrue(accept(FUSED, 0.0, 10f))

        assertFalse(accept(GPS, 1.0, 5f))
        assertEquals(FUSED, selector.active)
    }

    @Test
    fun `mykkä fused korvautuu gpslla viiden sekunnin jälkeen`() {
        accept(FUSED, 0.0, 10f)

        assertFalse(accept(GPS, 5.0, 5f), "viisi sekuntia ei vielä ole hiljaisuus")
        assertTrue(accept(GPS, 5.5, 5f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `epätarkka fused väistyy tarkan gpsn tieltä heti`() {
        accept(FUSED, 0.0, 60f)
        assertTrue(accept(GPS, 1.0, 5f))

        assertFalse(accept(FUSED, 2.0, 60f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `tarkkuusraja 25 metriä kuuluu kelpaaviin`() {
        accept(FUSED, 0.0, 25f)
        assertFalse(accept(GPS, 1.0, 5f), "25 m kelpaa saapumiseen")

        accept(FUSED, 2.0, 26f)
        assertTrue(accept(GPS, 3.0, 5f))
    }

    @Test
    fun `fused palaa vasta kolmannesta peräkkäisestä tarkasta näytteestä`() {
        accept(GPS, 0.0, 5f)

        assertFalse(accept(FUSED, 1.0, 10f))
        assertFalse(accept(FUSED, 2.0, 10f))
        assertTrue(accept(FUSED, 3.0, 10f))
        assertEquals(FUSED, selector.active)
    }

    @Test
    fun `harvoin näytteitä antava fused ei keskeytä tarkkaa gpstä`() {
        var published = 0
        accept(GPS, 0.0, 5f)
        for (second in 1..60) {
            if (accept(GPS, second.toDouble(), 5f)) published++
            if (second % 3 == 0) assertFalse(accept(FUSED, second + 0.5, 10f), "kello $second")
        }

        assertEquals(60, published)
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `rajalla vuorotteleva tarkkuus ei palauta fusedia`() {
        accept(GPS, 0.0, 5f)
        for (second in 1..20) {
            val accuracy = if (second % 2 == 0) 24f else 26f
            assertFalse(accept(FUSED, second.toDouble(), accuracy), "kello $second")
            assertTrue(accept(GPS, second + 0.1, 5f))
        }
    }

    @Test
    fun `network ei syrjäytä elävää virtaa vaikka olisi tarkempi`() {
        accept(GPS, 0.0, 35f)
        for (second in 1..30) {
            assertTrue(accept(GPS, second.toDouble(), 35f), "kello $second")
            if (second % 6 == 0) assertFalse(accept(NETWORK, second + 0.5, 20f))
        }
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `network kelpaa vasta kun virrat ovat vaienneet`() {
        accept(GPS, 0.0, 5f)
        accept(FUSED, 0.5, 40f)

        assertFalse(accept(NETWORK, 5.0, 50f))
        assertTrue(accept(NETWORK, 6.0, 50f))
        assertEquals(NETWORK, selector.active)
    }

    @Test
    fun `verkosta palataan heti tarkkaan virtaan`() {
        accept(NETWORK, 0.0, 40f)

        assertTrue(accept(GPS, 1.0, 5f))
        assertFalse(accept(NETWORK, 2.0, 40f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `verkosta palataan vain vähintään yhtä tarkkaan`() {
        accept(NETWORK, 0.0, 40f)

        assertFalse(accept(GPS, 1.0, 80f))
        assertTrue(accept(GPS, 2.0, 40f))
    }

    @Test
    fun `tarkkuudeton näyte ei ole kelpaava`() {
        assertTrue(accept(FUSED, 0.0, null), "ainoa näyte julkaistaan")

        assertFalse(accept(GPS, 1.0, 150f), "yli 100 m:n näyte ei syrjäytä")
        assertTrue(accept(GPS, 2.0, 80f), "tarkkuuden kertova syrjäyttää tarkkuudettoman")
        assertFalse(accept(FUSED, 3.0, null))
    }

    @Test
    fun `tunnelissa epätarkka näyte pidätetään minuutin ajan`() {
        accept(GPS, 0.0, 5f)

        assertFalse(accept(NETWORK, 10.0, 500f))
        assertEquals(GPS, selector.active, "pidätetty näyte ei vaihda lähdettä")
        assertFalse(accept(NETWORK, 59.0, 500f))
        assertTrue(accept(NETWORK, 61.0, 500f))
        assertEquals(NETWORK, selector.active)
    }

    @Test
    fun `tarkkuudeton näyte pidätetään tunnelissa`() {
        accept(GPS, 0.0, 5f)

        assertFalse(accept(NETWORK, 10.0, null))
    }

    @Test
    fun `sadan metrin näyte ei jää pidätykseen`() {
        accept(GPS, 0.0, 5f)

        assertTrue(accept(NETWORK, 10.0, 100f))
    }

    @Test
    fun `pidätys lasketaan viimeisestä tarkasta näytteestä`() {
        accept(GPS, 0.0, 5f)
        assertTrue(accept(NETWORK, 50.0, 100f))

        assertTrue(accept(NETWORK, 61.0, 500f))
    }

    @Test
    fun `pidätys ei estä lähdettä palaamasta tarkkana`() {
        accept(GPS, 0.0, 5f)
        assertFalse(accept(NETWORK, 10.0, 500f))

        assertTrue(accept(GPS, 12.0, 6f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `sama näyte kahdesti julkaistaan kerran`() {
        assertTrue(accept(GPS, 1.0, 5f))

        assertFalse(accept(GPS, 1.0, 5f))
    }

    @Test
    fun `julkaisu ei mene ajassa taaksepäin`() {
        accept(GPS, 0.0, 5f)
        assertTrue(accept(GPS, 10.0, 5f))

        assertFalse(accept(GPS, 9.0, 5f), "sama lähde")
        assertFalse(accept(FUSED, 6.0, 5f))
        assertFalse(accept(FUSED, 7.0, 5f))
        assertFalse(accept(FUSED, 8.0, 5f), "kolmas tarkka näyte olisi riittänyt, mutta se on liian vanha")
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `sama näyte ei kasvata tarkkojen näytteiden putkea`() {
        accept(GPS, 0.0, 5f)

        repeat(3) { assertFalse(accept(FUSED, 1.0, 5f)) }

        assertEquals(GPS, selector.active)
    }

    @Test
    fun `network ei syrjäytä elävää fusedia vaikka gps on vaiti`() {
        accept(FUSED, 0.0, 5f)
        accept(FUSED, 5.0, 5f)

        assertFalse(accept(NETWORK, 6.0, 20f))
        assertEquals(FUSED, selector.active)
    }

    @Test
    fun `tarkat virrat koko ajon ajan ja fusedin katko`() {
        var fused = 0
        var gps = 0
        for (second in 0 until 600) {
            val silent = second in 300 until 320
            if (!silent && accept(FUSED, second.toDouble(), 5f)) fused++
            if (accept(GPS, second + 0.2, 8f)) gps++
        }

        assertEquals(596, gps + fused, "neljä sekuntia ilman pistettä katkon alussa")
        assertEquals(18, gps, "gps ohjaa, kunnes fused on palannut")
        assertEquals(FUSED, selector.active)
    }

    @Test
    fun `erittäin epätarkka fused väistyy epätarkan gpsn tieltä`() {
        accept(FUSED, 0.0, 800f)

        assertTrue(accept(GPS, 1.0, 30f))
        assertEquals(GPS, selector.active)
    }

    @Test
    fun `pidätys ei jäädytä pistettä kun toinen virta kelpaa`() {
        accept(FUSED, 0.0, 5f)
        assertFalse(accept(FUSED, 1.0, 150f))

        assertTrue(accept(GPS, 2.0, 40f))
    }

    @Test
    fun `fused palaa vaikka leima on hieman gpsn jäljessä`() {
        assertTrue(accept(GPS, 1.0, 5f))
        assertFalse(accept(FUSED, 0.8, 5f))
        assertTrue(accept(GPS, 2.0, 5f))
        assertFalse(accept(FUSED, 1.8, 5f))
        assertTrue(accept(GPS, 3.0, 5f))

        assertFalse(accept(FUSED, 2.8, 5f), "vaihtuu, mutta tätä näytettä ei julkaista")
        assertEquals(FUSED, selector.active)
        assertFalse(accept(GPS, 4.0, 5f))
        assertTrue(accept(FUSED, 3.8, 5f))
    }

    @Test
    fun `fused palaa vaikka leimat ovat täsmälleen samat`() {
        repeat(3) { index ->
            accept(GPS, index + 1.0, 5f)
            accept(FUSED, index + 1.0, 5f)
        }
        assertEquals(FUSED, selector.active)

        assertFalse(accept(GPS, 4.0, 5f))
        assertTrue(accept(FUSED, 4.0, 5f))
    }

    @Test
    fun `verkosta ei palata epätarkkaan vaikka verkon näyte on hetken vanha`() {
        accept(NETWORK, 0.0, 90f)

        assertFalse(accept(GPS, 6.0, 500f))
        assertEquals(NETWORK, selector.active)
        assertTrue(accept(GPS, 31.0, 500f), "vanhentunut verkko ei pidä paikkaansa")
    }

    @Test
    fun `tarkkojen näytteiden väli saa olla 2,5 sekuntia`() {
        accept(GPS, 0.0, 5f)
        listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).forEach { accept(GPS, it, 5f) }

        assertFalse(accept(FUSED, 1.5, 5f))
        assertFalse(accept(FUSED, 4.0, 5f))
        assertEquals(GPS, selector.active)
        assertTrue(accept(FUSED, 6.5, 5f), "kolmas tarkka näyte vaihtaa lähteen")
        assertEquals(FUSED, selector.active)
    }

    @Test
    fun `yli 2,5 sekunnin väli katkaisee tarkkojen näytteiden putken`() {
        accept(GPS, 0.0, 5f)
        listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).forEach { accept(GPS, it, 5f) }

        accept(FUSED, 1.0, 5f)
        accept(FUSED, 3.6, 5f)
        accept(FUSED, 6.2, 5f)

        assertEquals(GPS, selector.active)
    }

    @Test
    fun `pidätys päättyy tasan minuutin kohdalla`() {
        accept(GPS, 0.0, 5f)

        assertFalse(accept(NETWORK, 59.9, 500f))
        assertTrue(accept(NETWORK, 60.0, 500f))
    }
}
