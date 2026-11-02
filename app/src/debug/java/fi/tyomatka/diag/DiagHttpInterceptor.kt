package fi.tyomatka.diag

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kirjaa jokaisen verkkopyynnön diagnostiikkalokiin yhtenä rivinä vasta, kun vastauksen runko on luettu tai suljettu:
 * metodi, isäntä ja polku ilman kyselyparametreja (avaimet eivät saa päätyä lokiin), vastauskoodi, luetut tavut ja
 * kokonaiskesto. Rungon luvun katkeaminen on virhe, ei onnistuminen; peruttu kutsu (haun debounce, hävinnyt
 * rinnakkaishaku) kirjataan `PERUTTU`, ei virheenä. Vastauksen sisältöä ei lueta eikä talleteta.
 */
class DiagHttpInterceptor(private val now: () -> Long = System::currentTimeMillis) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = now()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Diag.event("http", describe(request.method, request.url, null, null, now() - started, e, chain.call().isCanceled()))
            throw e
        }
        val logged = AtomicBoolean(false)
        var read = 0L
        fun finish(error: Throwable?) {
            if (!logged.compareAndSet(false, true)) return
            Diag.event("http", describe(request.method, request.url, response.code, read, now() - started, error, chain.call().isCanceled()))
        }
        val source = object : ForwardingSource(response.body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = try {
                    super.read(sink, byteCount)
                } catch (e: IOException) {
                    finish(e)
                    throw e
                }
                if (n == -1L) finish(null) else read += n
                return n
            }

            override fun close() {
                super.close()
                finish(null)
            }
        }
        return response.newBuilder()
            .body(source.buffer().asResponseBody(response.body.contentType(), response.body.contentLength()))
            .build()
    }

    companion object {
        fun describe(method: String, url: HttpUrl, code: Int?, bytes: Long?, elapsedMs: Long, error: Throwable?, canceled: Boolean): String {
            val target = "${url.host}${url.encodedPath}"
            return when {
                canceled -> "$method $target PERUTTU $elapsedMs ms"
                error != null -> "$method $target VIRHE ${error.javaClass.simpleName}: ${error.message} $elapsedMs ms"
                else -> "$method $target $code ${bytes ?: -1} t $elapsedMs ms"
            }
        }
    }
}
