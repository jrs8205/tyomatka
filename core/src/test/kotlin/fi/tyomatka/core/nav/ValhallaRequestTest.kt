package fi.tyomatka.core.nav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValhallaRequestTest {

    @Test
    fun `valinnoissa on kieli ja kilometrit`() {
        val options = Json.parseToJsonElement(ValhallaRequest.optionsJson("fi-FI", RouteChoice.NORMAL))

        assertEquals(
            JsonObject(mapOf("language" to JsonPrimitive("fi-FI"), "units" to JsonPrimitive("kilometers"))),
            options,
        )
    }

    @Test
    fun `lyhin reitti painottaa matkaa eikä ohita yksityisteiden sakkoja`() {
        val options = Json.parseToJsonElement(ValhallaRequest.optionsJson("fi-FI", RouteChoice.SHORTEST)) as JsonObject

        assertEquals(JsonPrimitive("fi-FI"), options["language"])
        assertEquals(
            Json.parseToJsonElement("""{"auto":{"use_distance":1.0}}"""),
            options["costing_options"],
        )
    }

    @Test
    fun `reittivalinta luetaan avaimesta`() {
        assertEquals(RouteChoice.SHORTEST, RouteChoice.of("shortest"))
        assertEquals(RouteChoice.NORMAL, RouteChoice.of("normal"))
        assertEquals(RouteChoice.NORMAL, RouteChoice.of(null))
        assertEquals(RouteChoice.NORMAL, RouteChoice.of("muu"))
    }

    @Test
    fun `reitin puuttuminen tunnistetaan OSRM-rungon koodista`() {
        assertEquals(RouteFailure.NoRoute, ValhallaRequest.failureOf(400, """{"code":"NoRoute","message":"x"}""", null))
        assertEquals(RouteFailure.NoRoute, ValhallaRequest.failureOf(400, """{"code":"NoSegment"}""", null))
    }

    @Test
    fun `liian pitka reitti`() {
        assertEquals(RouteFailure.TooLong, ValhallaRequest.failureOf(400, """{"code":"DistanceExceeded"}""", null))
    }

    @Test
    fun `vaarat valinnat ja jasentymaton runko ovat asetusvirheita`() {
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(400, """{"code":"InvalidValue"}""", null))
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(400, """{"code":"InvalidOptions"}""", null))
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(400, "<html>Bad Request</html>", null))
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(404, "Not Found", null))
    }

    @Test
    fun `onnistunut vastaus jota ei voi jasentaa on asetusvirhe`() {
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(200, "<html>captive portal</html>", null))
    }

    @Test
    fun `kuormaraja noudattaa Retry-After-otsaketta`() {
        assertEquals(RouteFailure.RateLimited(30), ValhallaRequest.failureOf(429, "", "30"))
        assertEquals(RouteFailure.RateLimited(10), ValhallaRequest.failureOf(429, "", null))
        assertEquals(RouteFailure.RateLimited(10), ValhallaRequest.failureOf(429, "", "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `kohtuuton tai epakelpo Retry-After rajataan`() {
        assertEquals(RouteFailure.RateLimited(300), ValhallaRequest.failureOf(429, "", "301"))
        assertEquals(RouteFailure.RateLimited(300), ValhallaRequest.failureOf(429, "", "999999999999999"))
        assertEquals(RouteFailure.RateLimited(10), ValhallaRequest.failureOf(429, "", "0"))
        assertEquals(RouteFailure.RateLimited(10), ValhallaRequest.failureOf(429, "", "-5"))
    }

    @Test
    fun `palvelinvirheet tarkoittavat etta palvelin ei vastaa`() {
        for (code in listOf(500, 502, 503, 504, 408)) {
            assertEquals(RouteFailure.Unavailable, ValhallaRequest.failureOf(code, "", null), "HTTP $code")
        }
    }

    @Test
    fun `verkkovirheet ja kesken katkennut vastaus tarkoittavat etta palvelin ei vastaa`() {
        val errors = listOf(
            SocketTimeoutException("timeout"),
            InterruptedIOException("call timeout"),
            UnknownHostException("valhalla1.openstreetmap.de"),
            EOFException("\\n not found: limit=0"),
            IOException("unexpected end of stream"),
        )
        for (error in errors) {
            assertEquals(RouteFailure.Unavailable, ValhallaRequest.failureOf(error), error.javaClass.simpleName)
        }
    }

    @Test
    fun `muu poikkeus on asetusvirhe`() {
        assertEquals(RouteFailure.BadConfig, ValhallaRequest.failureOf(IllegalStateException("parse")))
    }

    @Test
    fun `peruutus ei ole reittivirhe vaan etenee kutsujalle`() {
        assertFailsWith<CancellationException> { ValhallaRequest.failureOf(CancellationException("cancelled")) }
    }
}
