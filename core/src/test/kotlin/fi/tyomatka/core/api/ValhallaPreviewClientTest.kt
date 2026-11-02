package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteChoice
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValhallaPreviewClientTest {
    private lateinit var server: MockWebServer

    /** Pisteet (60.0, 24.0) ja (60.1, 24.1) Valhallan kuuden desimaalin muodossa. */
    private val firstLeg = encode(listOf(60.0 to 24.0, 60.1 to 24.1))
    private val secondLeg = encode(listOf(60.1 to 24.1, 60.2 to 24.2))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = ValhallaPreviewClient(
        OkHttpClient(),
        server.url("/").toString().removeSuffix("/"),
        RateLimiter(minIntervalMs = 0),
    )

    private val origin = GeoPoint(60.0, 24.0)
    private val stops = listOf(GeoPoint(60.1, 24.1), GeoPoint(60.2, 24.2))

    private fun ok() = MockResponse().setBody(
        """{"trip":{"summary":{"length":147.1,"time":6660.4},"legs":[{"shape":"${firstLeg.json()}"},{"shape":"${secondLeg.json()}"}]}}""",
    )

    private fun String.json() = replace("\\", "\\\\")

    @Test
    fun `reitti puretaan kuuden desimaalin tarkkuudella ja osuudet yhdistetään`() = runTest {
        server.enqueue(ok())

        val preview = client().preview(origin, stops, RouteChoice.NORMAL)

        assertEquals(147_100, preview.distanceM)
        assertEquals(6_660, preview.durationSec)
        // Osuuksien yhteinen piste on mukana kerran.
        assertEquals(3, preview.points.size)
        assertTrue(abs(preview.points[1].lat - 60.1) < 1e-6, preview.points.toString())
        assertTrue(abs(preview.points[2].lng - 24.2) < 1e-6, preview.points.toString())
    }

    @Test
    fun `pyynnössä on kaikki pisteet, kevyt muoto ja nopein reitti ilman lisävalintoja`() = runTest {
        server.enqueue(ok())

        client().preview(origin, stops, RouteChoice.NORMAL)

        val request = server.takeRequest()
        assertEquals("/route", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("auto", body.getValue("costing").jsonPrimitive.content)
        assertEquals("none", body.getValue("directions_type").jsonPrimitive.content)
        assertEquals("kilometers", body.getValue("units").jsonPrimitive.content)
        val locations = body.getValue("locations").jsonArray
        assertEquals(3, locations.size)
        assertEquals("60.2", locations[2].jsonObject.getValue("lat").jsonPrimitive.content)
        assertEquals("24.2", locations[2].jsonObject.getValue("lon").jsonPrimitive.content)
        assertNull(body["costing_options"])
    }

    @Test
    fun `lyhin pyydetään samalla valinnalla kuin ajossa`() = runTest {
        server.enqueue(ok())

        client().preview(origin, stops, RouteChoice.SHORTEST)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(Json.parseToJsonElement("""{"auto":{"use_distance":1.0}}"""), body["costing_options"])
    }

    private fun part(length: Double, time: Double, vararg legTimes: Double) = MockResponse().setBody(
        """{"trip":{"summary":{"length":$length,"time":$time},"legs":[""" +
            legTimes.joinToString(",") { """{"summary":{"time":$it},"shape":"${firstLeg.json()}"}""" } + "]}}",
    )

    private fun points(count: Int) = List(count) { GeoPoint(60.0 + it / 100.0, 24.0) }

    @Test
    fun `kymmenen pistettä menee yhdellä pyynnöllä`() = runTest {
        server.enqueue(part(10.0, 600.0, *DoubleArray(9) { 60.0 }))

        val preview = client().preview(origin, points(9), RouteChoice.NORMAL)

        assertEquals(1, server.requestCount)
        assertEquals(10, Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("locations").jsonArray.size)
        assertEquals(List(9) { 60L }, preview.legDurationsSec)
    }

    @Test
    fun `yli kymmenen pistettä pilkotaan ja osat lasketaan yhteen`() = runTest {
        server.enqueue(part(10.0, 600.0, *DoubleArray(9) { 60.0 }))
        server.enqueue(part(5.0, 300.0, 100.0, 200.0))

        val preview = client().preview(origin, points(11), RouteChoice.NORMAL)

        assertEquals(2, server.requestCount)
        val firstBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("locations").jsonArray
        val secondBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("locations").jsonArray
        assertEquals(10, firstBody.size)
        assertEquals(3, secondBody.size)
        assertEquals(firstBody.last(), secondBody.first(), "seuraava osa alkaa edellisen lopusta")
        assertEquals(15_000, preview.distanceM)
        assertEquals(900, preview.durationSec)
        assertEquals(11, preview.legDurationsSec.size)
    }

    @Test
    fun `pisteiden yläraja ei ole reitin puuttumista`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":150,"error":"Exceeded max locations: 10"}"""))

        val failure = assertFailsWith<ApiException> { client().preview(origin, stops, RouteChoice.NORMAL) }
        assertFalse(failure is RouteNotFoundException)
    }

    @Test
    fun `yhteenveto ilman viivaa on vajaa vastaus`() = runTest {
        server.enqueue(MockResponse().setBody("""{"trip":{"summary":{"length":1.0,"time":60.0},"legs":[{"shape":""}]}}"""))

        assertFailsWith<ApiException> { client().preview(origin, stops, RouteChoice.NORMAL) }
    }

    @Test
    fun `reitin puuttuminen on oma virheensä`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":442,"error":"No path could be found for input"}"""))

        assertFailsWith<RouteNotFoundException> { client().preview(origin, stops, RouteChoice.SHORTEST) }
    }

    @Test
    fun `palvelimen häiriö ja vajaa vastaus eivät ole reitin puuttumista`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("rikki"))
        val failure = assertFailsWith<ApiException> { client().preview(origin, stops, RouteChoice.NORMAL) }
        assertFalse(failure is RouteNotFoundException)

        server.enqueue(MockResponse().setBody("""{"trip":{"legs":[]}}"""))
        assertFailsWith<ApiException> { client().preview(origin, stops, RouteChoice.NORMAL) }
    }

    private fun encode(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        fun write(delta: Long) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 0x20) {
                out.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
                value = value shr 5
            }
            out.append((value.toInt() + 63).toChar())
        }
        points.forEach { (lat, lng) ->
            val a = Math.round(lat * 1e6)
            val b = Math.round(lng * 1e6)
            write(a - lastLat)
            write(b - lastLng)
            lastLat = a
            lastLng = b
        }
        return out.toString()
    }
}
