package fi.tyomatka.ui.map

import fi.tyomatka.core.api.RoutePreview
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteComparisonTest {
    private val fastPoints = listOf(GeoPoint(60.0, 24.0), GeoPoint(60.5, 24.5))
    private val shortPoints = listOf(GeoPoint(60.0, 24.0), GeoPoint(60.2, 24.1), GeoPoint(60.5, 24.5))
    private val fallback = listOf(GeoPoint(61.0, 25.0))
    private val fast = RoutePreview(fastPoints, distanceM = 147_100, durationSec = 6_660)
    private val short = RoutePreview(shortPoints, distanceM = 145_500, durationSec = 7_380)
    private val both = mapOf(RouteChoice.NORMAL to fast, RouteChoice.SHORTEST to short)

    @Test
    fun `valittu reitti piirretään sinisenä ja toinen harmaana`() {
        val normal = RouteComparison(RouteChoice.NORMAL, both)
        assertEquals(fastPoints, normal.drawnRoute(fallback))
        assertEquals(shortPoints, normal.otherRoute)

        val shortest = RouteComparison(RouteChoice.SHORTEST, both)
        assertEquals(shortPoints, shortest.drawnRoute(fallback))
        assertEquals(fastPoints, shortest.otherRoute)
    }

    @Test
    fun `samaa reittiä ei piirretä kahdesti`() {
        val same = mapOf(RouteChoice.NORMAL to fast, RouteChoice.SHORTEST to fast.copy(durationSec = 6_700))

        assertTrue(RouteComparison(RouteChoice.SHORTEST, same).otherRoute.isEmpty())
    }

    @Test
    fun `ilman vertailua näytetään suunnitelman reitti`() {
        val none = RouteComparison(RouteChoice.SHORTEST, emptyMap())

        assertEquals(fallback, none.drawnRoute(fallback))
        assertTrue(none.otherRoute.isEmpty())
        assertFalse(none.available)
        assertEquals(1_000, none.finishEpochSec(1_000))
    }

    @Test
    fun `yksi saatu reitti ei vielä riitä valintaan`() {
        val one = RouteComparison(RouteChoice.NORMAL, mapOf(RouteChoice.NORMAL to fast))

        assertFalse(one.available)
        assertEquals(fastPoints, one.drawnRoute(fallback))
        assertTrue(one.otherRoute.isEmpty())
    }

    @Test
    fun `lyhimmän reitin ero näkyy päivän valmistumisessa`() {
        assertEquals(1_000, RouteComparison(RouteChoice.NORMAL, both).finishEpochSec(1_000))
        assertEquals(1_720, RouteComparison(RouteChoice.SHORTEST, both).finishEpochSec(1_000))
        assertNull(RouteComparison(RouteChoice.SHORTEST, both).finishEpochSec(null))
    }

    @Test
    fun `saapumisajat siirtyvät valitun reitin osuuksien mukaan`() {
        val legs = mapOf(
            RouteChoice.NORMAL to fast.copy(legDurationsSec = listOf(100, 200)),
            RouteChoice.SHORTEST to short.copy(legDurationsSec = listOf(160, 340)),
        )
        val shortest = RouteComparison(RouteChoice.SHORTEST, legs)

        assertEquals(1_060, shortest.arrivalEpochSec(0, 1_000))
        assertEquals(2_200, shortest.arrivalEpochSec(1, 2_000))
        assertNull(shortest.arrivalEpochSec(0, null))
        assertEquals(1_000, RouteComparison(RouteChoice.NORMAL, legs).arrivalEpochSec(1, 1_000))
        assertEquals(1_000, RouteComparison(RouteChoice.SHORTEST, both).arrivalEpochSec(0, 1_000), "ilman osuuksia ennallaan")
    }

    @Test
    fun `yksi saatu reitti ei siirrä valmistumista`() {
        assertEquals(1_000, RouteComparison(RouteChoice.SHORTEST, mapOf(RouteChoice.SHORTEST to short)).finishEpochSec(1_000))
    }

    @Test
    fun `avain muuttuu vain kun reitti voi muuttua`() {
        val stops = listOf(1L to GeoPoint(60.1, 24.1), 2L to GeoPoint(60.2, 24.2))
        val key = RouteComparison.key(GeoPoint(60.2905, 24.8530), stops)

        assertEquals(key, RouteComparison.key(GeoPoint(60.2906, 24.8531), stops), "alle sadan metrin siirtymä")
        assertFalse(key == RouteComparison.key(GeoPoint(60.3005, 24.8530), stops), "yli sadan metrin siirtymä")
        assertFalse(key == RouteComparison.key(GeoPoint(60.2905, 24.8530), stops.reversed()), "järjestys")
        assertFalse(key == RouteComparison.key(GeoPoint(60.2905, 24.8530), listOf(1L to GeoPoint(60.1, 24.1), 2L to GeoPoint(60.3, 24.2))), "stopin paikka")
    }

    @Test
    fun `vertailu lähtee onnistuneen suunnitelman lähtöpaikasta`() {
        val planned = GeoPoint(60.29, 24.85)
        val start = GeoPoint(60.31, 24.85)

        assertEquals(planned, RouteComparison.previewOrigin(Result.success(planned), start))
        assertNull(RouteComparison.previewOrigin(Result.success(null), start), "suunnitelma ilman lähtöpaikkaa")
    }

    @Test
    fun `epäonnistunut suunnitelma käyttää juuri saatua sijaintia`() {
        val start = GeoPoint(60.31, 24.85)
        val failed = Result.failure<GeoPoint?>(IllegalStateException("palvelin alhaalla"))

        assertEquals(start, RouteComparison.previewOrigin(failed, start))
        assertNull(RouteComparison.previewOrigin(failed, null), "ilman sijaintia ei vertailua")
    }
}
