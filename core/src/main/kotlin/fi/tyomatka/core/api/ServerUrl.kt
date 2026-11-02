package fi.tyomatka.core.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException

/** Palvelinosoite asetuksista: siistitty muoto tai null, jos osoite ei kelpaa. Vain `https`, koska sovellus ei salli salaamatonta liikennettä. */
fun normalizeServerUrl(text: String): String? {
    val cleaned = text.trim().trimEnd('/')
    val uri = try {
        URI(cleaned)
    } catch (e: URISyntaxException) {
        return null
    }
    // Asiakkaat jäsentävät osoitteen rakentajassa: kelpaamaton portti kaataisi jokaisen käynnistyksen.
    val valid = cleaned.toHttpUrlOrNull() != null &&
        uri.scheme == "https" &&
        !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
    return cleaned.takeIf { valid }
}
