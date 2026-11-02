package fi.tyomatka.core.nav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Valhalla-reittipyynnön omat valinnat ja vastausten virheiden tulkinta.
 *
 * Ferrostarin pyyntögeneraattori lisää itse OSRM-muodon, puhe- ja otsikko-ohjeet sekä nopeusrajoitusten suodattimet;
 * tänne jää vain se, mikä riippuu sovelluksesta. Virheissä OSRM-muotoinen runko kertoo syyn `code`-kentässä.
 */
object ValhallaRequest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lyhin reitti painottaa pelkkää matkaa. `shortest` jätetään käyttämättä, koska se ohittaisi myös yksityisteiden ja
     * puomien sakot ja voisi ohjata niiden läpi; oikeilla reiteillä tulos on lähes sama.
     */
    fun optionsJson(languageTag: String, choice: RouteChoice): String = buildJsonObject {
        put("language", languageTag)
        put("units", "kilometers")
        if (choice == RouteChoice.SHORTEST) {
            putJsonObject("costing_options") {
                putJsonObject("auto") { put("use_distance", 1.0) }
            }
        }
    }.toString()

    fun failureOf(httpCode: Int, body: String, retryAfter: String?): RouteFailure = when {
        httpCode == 429 -> RouteFailure.RateLimited(
            retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_AFTER_SEC)
                ?: DEFAULT_RETRY_AFTER_SEC
        )
        httpCode == 408 || httpCode in 500..599 -> RouteFailure.Unavailable
        else -> when (osrmCode(body)) {
            "NoRoute", "NoSegment" -> RouteFailure.NoRoute
            "DistanceExceeded" -> RouteFailure.TooLong
            else -> RouteFailure.BadConfig
        }
    }

    /**
     * Verkko- ja aikakatkaisuvirheet, myös kesken katkennut vastaus, ovat [IOException]eja. Korutiinin peruutus ei ole
     * reittivirhe: se heitetään edelleen, jotta peruttu pyyntö ei näy käyttäjälle virheenä eikä käynnistä uutta yritystä.
     */
    fun failureOf(error: Throwable): RouteFailure = when (error) {
        is CancellationException -> throw error
        is IOException -> RouteFailure.Unavailable
        else -> RouteFailure.BadConfig
    }

    private fun osrmCode(body: String): String? =
        runCatching { json.decodeFromString<OsrmError>(body).code }.getOrNull()

    @Serializable
    private data class OsrmError(val code: String? = null)

    /** Palvelin ei kertonut odotusaikaa sekunteina. */
    private const val DEFAULT_RETRY_AFTER_SEC = 10L

    /** Pidempi odotus ei auta ajossa, ja rajaton arvo ylivuotaisi millisekunneiksi kerrottaessa. */
    private const val MAX_RETRY_AFTER_SEC = 300L
}
