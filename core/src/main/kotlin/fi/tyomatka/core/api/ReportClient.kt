package fi.tyomatka.core.api

import fi.tyomatka.core.poi.PoiBox
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationReport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Palvelimella ei (vielä) ole ilmoitusrajapintaa; ilmoitus lähetetään myöhemmin uudelleen. */
class ReportsUnavailableException : ApiException("Ilmoitukset eivät ole käytössä palvelimella", httpCode = null)

/** Palvelin on poistanut tämän tunnuksen ilmoitukset ja mitätöinyt tunnuksen; sovelluksen on vaihdettava se. */
class DeviceForgottenException : ApiException("Laitetunnus on poistettu palvelimelta", httpCode = null)

/**
 * Kuljettajien ilmoitukset omalta Workerilta: ikkunan uusimmat ilmoitukset ja yhden ilmoituksen lähetys. Pyynnössä
 * kulkee vain sovelluksen satunnainen laitetunniste (`X-Device`); ilmoitukset eivät kulje välimuistin kautta.
 * Ilmoituksen päivä lasketaan tekohetkestä kuljettajan aikavyöhykkeellä ([zone]). Lähetyksellä ja poistolla on
 * kokonaisaikaraja [callTimeoutMs]: ne pitävät sovelluksen lähetyslukkoa, eikä hitaasti tippuva vastaus saa pitää sitä
 * rajattomasti (lukuraja koskee vain taukoja). Niillä on oma jono, koska ajastin käynnistyy vasta jonosta lähtiessä, eikä
 * saman palvelimen hakuruuhka saa pitää lukkoa.
 */
class ReportClient(
    private val client: OkHttpClient,
    baseUrl: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    callTimeoutMs: Long = 30_000,
) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()
    private val writes = client.newBuilder().dispatcher(Dispatcher()).callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()

    suspend fun window(box: PoiBox): List<StationReport> = parseWindow(windowRaw(box))

    /** Ikkunan vastaus sellaisenaan laitteen välimuistia varten ([parseWindow]). */
    suspend fun windowRaw(box: PoiBox): String {
        val url = base.newBuilder()
            .addPathSegments("v1/reports")
            .addEncodedQueryParameter("bbox", String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", box.minLon, box.minLat, box.maxLon, box.maxLat))
            .build()
        val raw = client.executeRaw(Request.Builder().url(url).get().header("Accept", "application/json").build())
        if (raw.code !in 200..299) throw ApiException("HTTP ${raw.code}", httpCode = raw.code)
        return raw.body
    }

    fun parseWindow(body: String): List<StationReport> = json.decodeFromString<Window>(body).items.mapNotNull { item ->
        val field = ReportField.of(item.field) ?: return@mapNotNull null
        val at = item.at?.let(::instant)
        val day = at?.atZone(zone)?.toLocalDate()?.toEpochDay() ?: epochDay(item.date) ?: return@mapNotNull null
        StationReport(item.poi, field, item.value, day, atEpochSec = at?.epochSecond ?: (day * 86_400L))
    }

    /**
     * Lähettää ilmoituksen; palauttaa päivän. Saman [rid]:n uusinta kuitataan kaksoiskappaleena (200), joten aikakatkaisun
     * jälkeinen uusinta ei tuota toista riviä. [at] on ilmoituksen tekohetki (offline-jonossa ollut ilmoitus).
     */
    suspend fun send(rid: String, poiId: String, field: String, value: String, deviceId: String, at: Instant? = null): Long {
        val body = json.encodeToString(Send(poi = poiId, field = field, value = value, rid = rid, at = at?.toString()))
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/reports").build())
            .header("X-Device", deviceId)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val raw = writes.executeRaw(request)
        // Palvelin ilman ilmoitusrajapintaa (vanha julkaisu) vastaa reitittä 404 "not found": ilmoitus jää jonoon.
        if (raw.code == 404 && raw.body.contains("\"not found\"")) throw ReportsUnavailableException()
        // Vain palvelimen oma vastaus vaihtaa tunnuksen: välittäjän 410 ei saa jättää vanhan tunnuksen rivejä tavoittamattomiin.
        if (raw.code == 410 && raw.body.contains("\"tunnus poistettu\"")) throw DeviceForgottenException()
        if (raw.code !in 200..299) throw ApiException("HTTP ${raw.code}: ${raw.body.take(200)}", httpCode = raw.code)
        val sent = json.decodeFromString<Sent>(raw.body)
        return sent.at?.let(::localDay) ?: epochDay(sent.date) ?: throw ApiException("päivä puuttuu vastauksesta")
    }

    /**
     * Poistaa laitteen omat ilmoitukset palvelimelta; epäonnistuminen nousee virheenä. Sovellus vaihtaa tunnuksensa tämän
     * perusteella, joten pelkkä 200 (esim. välityspalvelimen sivu) ei riitä, vaan vastauksen pitää vahvistaa poisto.
     */
    suspend fun forget(deviceId: String) {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/reports/forget").build())
            .header("X-Device", deviceId)
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val raw = writes.executeRaw(request)
        if (raw.code !in 200..299) throw ApiException("HTTP ${raw.code}", httpCode = raw.code)
        try {
            json.decodeFromString<Forgotten>(raw.body)
        } catch (_: SerializationException) {
            throw ApiException("poistoa ei vahvistettu")
        }
    }

    private fun localDay(at: String): Long? = instant(at)?.atZone(zone)?.toLocalDate()?.toEpochDay()

    private fun instant(at: String): Instant? = try {
        Instant.parse(at)
    } catch (_: DateTimeParseException) {
        null
    }

    @Serializable
    private data class Window(val items: List<Item> = emptyList())

    @Serializable
    private data class Item(val poi: String, val field: String, val value: String, val date: String = "", val at: String? = null)

    @Serializable
    private data class Send(val poi: String, val field: String, val value: String, val rid: String, val at: String? = null)

    @Serializable
    private data class Sent(val date: String = "", val at: String? = null)

    @Serializable
    private data class Forgotten(val deleted: Int)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun epochDay(text: String): Long? = try {
            LocalDate.parse(text).toEpochDay()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
