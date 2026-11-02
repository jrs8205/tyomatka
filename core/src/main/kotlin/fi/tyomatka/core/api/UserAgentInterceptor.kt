package fi.tyomatka.core.api

import okhttp3.Interceptor
import okhttp3.Response

fun userAgent(versionName: String): String = "Tyomatka/$versionName (+https://github.com/jrs8205/tyomatka)"

/** Vapaaehtoisten ylläpitämät palvelimet edellyttävät tunnistettavaa sovellusta. */
class UserAgentInterceptor(versionName: String) : Interceptor {
    private val value = userAgent(versionName)

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", value).build())
}
