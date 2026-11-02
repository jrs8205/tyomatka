package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FallbackReverseGeocoderTest {
    private class Fixed(private val answer: () -> PlaceDetails?) : ReverseGeocoder {
        val calls = mutableListOf<Pair<GeoPoint, Double>>()
        override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
            calls += point to maxDistanceM
            return answer()
        }
    }

    private val petikko = GeoPoint(60.33, 25.05)
    private fun place(name: String) = PlaceDetails("$name:1", name, "$name-osoite", petikko)

    @Test
    fun `ensimmäinen löytö kelpaa eikä varahakua kysytä`() = runTest {
        val fallback = Fixed { place("photon") }
        val geocoder = FallbackReverseGeocoder(listOf(Fixed { place("digitransit") }, fallback))

        assertEquals("digitransit", geocoder.reverse(petikko, 300.0)?.name)
        assertEquals(emptyList(), fallback.calls)
    }

    @Test
    fun `vastattu ei osoitetta on lopullinen eikä varahakua kysytä`() = runTest {
        // Photonin talotason haku antaa usein kohteen ilman talonumeroa: stoppi saisi kadun nimen koordinaattien sijaan.
        val fallback = Fixed { place("photon") }

        assertNull(FallbackReverseGeocoder(listOf(Fixed { null }, fallback)).reverse(petikko, 300.0))
        assertEquals(emptyList(), fallback.calls)
    }

    @Test
    fun `virhe siirtää haun varapalveluun samalla säteellä`() = runTest {
        val fallback = Fixed { place("photon") }

        assertEquals("photon", FallbackReverseGeocoder(listOf(Fixed { throw ApiException("kiintiö", 429) }, fallback)).reverse(petikko, 300.0)?.name)
        assertEquals(listOf(petikko to 300.0), fallback.calls)
        assertNull(FallbackReverseGeocoder(listOf(Fixed { throw ApiException("alhaalla", 503) }, Fixed { null })).reverse(petikko, 300.0))
    }

    @Test
    fun `kun kukaan ei vastaa, viimeinen virhe nousee`() = runTest {
        val e = assertFailsWith<ApiException> {
            FallbackReverseGeocoder(listOf(Fixed { throw ApiException("kiintiö", 429) }, Fixed { throw ApiException("alhaalla", 503) })).reverse(petikko, 300.0)
        }
        assertEquals(503, e.httpCode)
    }

    @Test
    fun `peruutus ei siirry varapalveluun`() = runTest {
        val fallback = Fixed { place("photon") }

        assertFailsWith<CancellationException> {
            FallbackReverseGeocoder(listOf(Fixed { throw CancellationException("suljettu") }, fallback)).reverse(petikko, 300.0)
        }
        assertEquals(emptyList(), fallback.calls)
    }
}
