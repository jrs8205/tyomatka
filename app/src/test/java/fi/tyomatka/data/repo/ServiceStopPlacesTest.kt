package fi.tyomatka.data.repo

import fi.tyomatka.core.api.ReverseGeocoder
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceStopPlacesTest {
    private class FakeReverse(private val result: PlaceDetails?) : ReverseGeocoder {
        val calls = mutableListOf<Pair<GeoPoint, Double>>()
        override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
            calls += point to maxDistanceM
            return result
        }
    }

    private val location = GeoPoint(60.33012, 25.05129)

    private fun item(name: String? = "Neste Truck", address: String? = "Esimerkkikaari 2, Vantaa") =
        PoiItem("n123", PoiCategory.FUEL, location, name, "Neste", address, hgv = true, tags = emptyMap())

    @Test
    fun `osoitteellinen kohde ei kutsu käänteishakua ja saa huoltostopin tunnuksen`() = runTest {
        val reverse = FakeReverse(null)

        val place = ServiceStopPlaces(reverse).place(item(), fallbackName = "Huoltoasema")

        assertEquals(PlaceDetails("service:osm:n123", "Neste Truck", "Esimerkkikaari 2, Vantaa", location), place)
        assertEquals(emptyList(), reverse.calls)
    }

    @Test
    fun `osoitteeton kohde saa lähimmän osoitteen 300 metrin säteeltä`() = runTest {
        val reverse = FakeReverse(PlaceDetails("osm:W:1", "Esimerkkikaari 4", "Esimerkkikaari 4, Vantaa", GeoPoint(60.3302, 25.0514)))

        val place = ServiceStopPlaces(reverse).place(item(address = null), fallbackName = "Huoltoasema")

        assertEquals("Esimerkkikaari 4, Vantaa", place.address)
        assertEquals(location, place.location)
        assertEquals(listOf(location to 300.0), reverse.calls)
    }

    @Test
    fun `hidas käänteishaku ei pidätä lisäystä vaan kahden sekunnin jälkeen osoitteeksi tulevat koordinaatit`() = runTest {
        val slow = object : ReverseGeocoder {
            override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
                delay(45_000)
                return PlaceDetails("osm:W:1", "Esimerkkikaari 4", "Esimerkkikaari 4, Vantaa", point)
            }
        }

        val place = ServiceStopPlaces(slow).place(item(address = null), fallbackName = "Huoltoasema")

        assertEquals("60.3301, 25.0513", place.address)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `ilman osoitetta ja osumaa osoite on koordinaatit, ilman nimeä luokan nimi`() = runTest {
        val place = ServiceStopPlaces(FakeReverse(null)).place(item(name = null, address = null), fallbackName = "Huoltoasema")

        assertEquals("60.3301, 25.0513", place.address)
        assertEquals("Neste", place.name)
        val nameless = ServiceStopPlaces(FakeReverse(null)).place(item(name = null, address = null).copy(brand = null), fallbackName = "Huoltoasema")
        assertEquals("Huoltoasema", nameless.name)
    }
}
