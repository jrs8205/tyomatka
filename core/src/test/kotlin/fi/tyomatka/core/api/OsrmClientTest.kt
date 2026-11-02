package fi.tyomatka.core.api

import fi.tyomatka.core.geo.Polyline
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlannedStop
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsrmClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OsrmClient(OkHttpClient(), baseUrl = server.url("/").toString().removeSuffix("/"))

    private fun stop(id: Long, lat: Double, lng: Double) = PlannedStop(id, "S$id", GeoPoint(lat, lng))

    @Test
    fun `parsii legit, kokonaiskeston ja polylinen ja lähettää pisteet lon,lat-järjestyksessä`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":"Ok","routes":[{"distance":5145.4,"duration":800.9,"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`@",
               "legs":[{"distance":2475.8,"duration":484.8},{"distance":2669.6,"duration":316.1}]}]}"""
            )
        )

        val overview = client().computeRouteOverview(
            origin = GeoPoint(60.1699, 24.9384),
            orderedStops = listOf(stop(1, 60.18, 24.95), stop(2, 60.19, 24.96)),
        )

        assertEquals(listOf(485L, 316L), overview.legDurationsSec)
        assertEquals(listOf(2476L, 2670L), overview.legDistancesM)
        assertEquals(801L, overview.totalDurationSec)
        assertEquals(3, Polyline.decode(overview.encodedPolyline).size)
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/route/v1/driving/24.938400,60.169900;24.950000,60.180000;24.960000,60.190000?"), path)
        assertTrue(path.contains("overview=full") && path.contains("geometries=polyline") && path.contains("steps=false"), path)
    }

    @Test
    fun `NoRoute nousee ApiExceptionina OSRM-koodilla`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"code":"NoRoute","message":"Impossible route between points"}""")
        )

        val e = assertFailsWith<ApiException> {
            client().computeRouteOverview(GeoPoint(60.0, 24.0), listOf(stop(1, 0.0, 0.0)))
        }
        assertTrue(e.message!!.contains("NoRoute"), e.message)
        assertEquals(400, e.httpCode)
        assertTrue(e is RouteNotFoundException)
    }

    @Test
    fun `NoSegment ja tyhjä reittilista ovat reitin puuttumista`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"NoSegment"}"""))
        assertFailsWith<RouteNotFoundException> { client().computeRouteOverview(GeoPoint(60.0, 24.0), listOf(stop(1, 0.0, 0.0))) }
        server.enqueue(MockResponse().setBody("""{"code":"Ok","routes":[]}"""))
        assertFailsWith<RouteNotFoundException> { client().computeRouteOverview(GeoPoint(60.0, 24.0), listOf(stop(1, 0.0, 0.0))) }
    }

    @Test
    fun `palvelimen häiriö ei ole reitin puuttumista`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("rikki"))
        val e = assertFailsWith<ApiException> { client().computeRouteOverview(GeoPoint(60.0, 24.0), listOf(stop(1, 0.0, 0.0))) }
        assertFalse(e is RouteNotFoundException)
    }

    @Test
    fun `puuttuva geometria ja vajaa legilista eivät kaada`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":"Ok","routes":[{"distance":1.0,"duration":2.0,"legs":[]}]}"""))

        val overview = client().computeRouteOverview(GeoPoint(60.0, 24.0), listOf(stop(1, 60.1, 24.1)))

        assertEquals("", overview.encodedPolyline)
        assertEquals(emptyList(), overview.legDurationsSec)
        assertEquals(2L, overview.totalDurationSec)
    }

    @Test
    fun `tyhjä stoppilista on ohjelmointivirhe`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            client().computeRouteOverview(GeoPoint(60.0, 24.0), emptyList())
        }
    }
}
