package fi.tyomatka.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Rajapintakutsun epäonnistuminen (HTTP-virhe tai virheellinen vastaus). */
open class ApiException(message: String, val httpCode: Int? = null) : IOException(message)

/** Pisteiden välillä ei ole reittiä: uusi yritys ei auta, vaan stoppien osoitteet pitää tarkistaa. */
class RouteNotFoundException(message: String, httpCode: Int? = null) : ApiException(message, httpCode)

/** Haun ehdotus ei ole enää välimuistissa: käyttäjän pitää hakea uudelleen. */
class StaleSuggestionException : ApiException("Osoite-ehdotus on vanhentunut, hae uudelleen")

internal suspend fun OkHttpClient.execute(request: Request): String {
    val raw = executeRaw(request)
    if (raw.code !in 200..299) {
        throw ApiException("HTTP ${raw.code}: ${raw.body.take(500)}", httpCode = raw.code)
    }
    return raw.body
}

internal class RawResponse(val code: Int, val body: String)

/** Kuten [execute], mutta virhekoodikin palautetaan rungon kanssa (OSRM kertoo syyn JSONissa). */
internal suspend fun OkHttpClient.executeRaw(request: Request): RawResponse =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Rungon luku voi katketa kesken (yhteys poikki): ilman kiinniottoa
                // poikkeus katoaa OkHttp:n säikeeseen ja odottaja jää ikuisesti jumiin.
                val result = runCatching { response.use { RawResponse(it.code, it.body.string()) } }
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) },
                )
            }
        })
    }
