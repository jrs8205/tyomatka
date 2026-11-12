package fi.tyomatka.core.api

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * Photon-osoitehaku (OSM-data). Hakutulokset pidetään välimuistissa, koska
 * Photon ei tarjoa hakua tunnisteella: [placeDetails] vastaa vain viimeisimpien
 * hakujen ehdotuksiin.
 */
class PhotonClient(
    private val client: OkHttpClient,
    private val baseUrl: String = Defaults.PHOTON_URL,
    private val limiter: RateLimiter? = null,
    /** Valinnainen rajaus (länsi,etelä,itä,pohjoinen); oletuksena koko maailma. */
    private val bbox: String? = null,
    private val limit: Int = 8,
    /** Hyväksytyt maakoodit (esim. FI, AX); tyhjä = kaikki maat. */
    countries: Set<String> = emptySet(),
) : PlaceSearch, ReverseGeocoder {
    private val countries = countries.map { it.uppercase(Locale.ROOT) }.toSet()
    private val json = Json { ignoreUnknownKeys = true }
    private val host = baseUrl.toHttpUrl().host

    private val cache = PlaceCache()

    override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> {
        if (input.isBlank()) return emptyList()
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/")
            .addQueryParameter("q", input.trim())
            .addQueryParameter("limit", limit.toString())
            .apply { if (bbox != null) addEncodedQueryParameter("bbox", bbox) }
            .countries()
            .bias(biasLocation)
            .build()
        limiter?.acquire(host)
        return fetch(url.toString()).map { it.suggestion }
    }

    /**
     * Sijaintipainotus. Oletuspainolla Photon nostaa tunnetun kohteen kauempaa lähiosuman ohi
     * (kaupungissa haettu katu löytyy naapurikunnasta); `location_bias_scale=0` + `zoom=14` painottaa
     * pelkkää etäisyyttä, eikä kaupungin nimen sisältävä kaukohaku kärsi.
     */
    private fun HttpUrl.Builder.bias(location: GeoPoint?) = apply {
        if (location != null) {
            addQueryParameter("lat", location.lat.toString())
            addQueryParameter("lon", location.lng.toString())
            addQueryParameter("location_bias_scale", "0")
            addQueryParameter("zoom", "14")
        }
    }

    /**
     * Maarajaus palvelimelle toistettuna parametrina (`countrycode=FI&countrycode=AX`): pelkkä bbox kattaa Pohjois-Ruotsin
     * rannikon, jolloin ruotsalaiset osumat täyttäisivät sivun. Käänteishaku ei tunne parametria, vaan rajataan tuloksista.
     */
    private fun HttpUrl.Builder.countries() = apply { countries.forEach { addQueryParameter("countrycode", it) } }

    override suspend fun placeDetails(placeId: String): PlaceDetails = cache.get(placeId)

    /** Lähin osoite (`/reverse`); etäisyys lasketaan itse, koska Photon ei kerro sitä. */
    override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lng.toString())
            .addQueryParameter("limit", "1")
            // Vain talotaso: ilman rajausta lähin osuma voi olla kohde, katu tai kaupunki.
            .addQueryParameter("layer", "house")
            .build()
        limiter?.acquire(host)
        val best = fetch(url.toString()).firstOrNull() ?: return null
        return best.details.takeIf { distanceKm(it.location, point) * 1000 <= maxDistanceM }
    }

    override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? {
        if (addressLine.isBlank()) return null
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/")
            .addQueryParameter("q", addressLine.trim())
            .addQueryParameter("limit", "1")
            .apply { if (bbox != null) addEncodedQueryParameter("bbox", bbox) }
            .countries()
            .bias(biasLocation)
            .build()
        limiter?.acquire(host)
        val best = fetch(url.toString()).firstOrNull() ?: return null
        return best.details.takeIf { isPlausible(best, addressLine, biasLocation) }
    }

    /**
     * Listatuonnin vartija: Photon antaa olemattomalle osoitteelle kaukaisen kadun ("Olematon katu 5, Oulu"
     * → Intia), kaupungin keskipisteen tai lähellä olevan kohteen. Kelpaa vain talo tai katu, jonka paikkakunnan
     * tai postinumeron rivi mainitsee kokonaisina sanoina (Ii ei osu Piikkiöön, Jyvaskyla on Jyväskylä) tai joka
     * on [PLAUSIBLE_KM] säteellä sijainnista, ja jonka katu ja talonumero ovat rivillä ([streetMatches]).
     */
    private fun isPlausible(result: Resolved, line: String, bias: GeoPoint?): Boolean {
        if (result.type != null && result.type !in ADDRESS_TYPES) return false
        val words = line.words()
        val mentioned = listOfNotNull(result.city, result.postcode).map { it.words() }
            .any { name -> name.isNotEmpty() && words.windowed(name.size).any { it == name } }
        val near = bias != null && distanceKm(result.details.location, bias) <= PLAUSIBLE_KM
        return (mentioned || near) && streetMatches(result, line)
    }

    /**
     * Tuloksen katu on jossakin rivin osassa (lyhenteet auki, pieni kirjoitusvirhe sallitaan), ja sen perässä oleva
     * talonumero täsmää tuloksen numeroon, jos molemmissa on numero. Photon antaa paikkakunnan sisältä muun kohteen
     * ("Hakatie 2, Piikkiö" → Piikkiön rautatieasema; "Kauppak. 5, Oulu" → Kauppakeskus Valkea) tai saman
     * kadun toisen talon ("Isok. 10, Oulu" → Isokatu 102). Jos katu ei ole rivillä, tuloksen nimi kelpaa vain, kun
     * rivillä ei ole nimen lisäksi katuosoitetta ([hasStreetAddress]): samanniminen ketjukohde tulee muuten väärältä
     * kadulta ("R-kioski, Kauppakatu 5, Oulu" → R-kioski Isokatu 20), ja väärä stoppi on pahempi kuin hylätty rivi.
     *
     * Osoitteet ovat suomalaisia: talonumero on kadun perässä ja postinumero viisinumeroinen. Rivin postinumero
     * ([postcodeAt]) ei ole talonumero, vaikka Photonin postinumero olisi toinen tai puuttuisi ("Mannerheimintie 00100
     * Helsinki", "Kirkkotie 95100 Suomi"). Kadun nimen numeron pitää täsmätä tarkasti: "3. linja" ei ole "4. linja".
     */
    private fun streetMatches(result: Resolved, line: String): Boolean {
        val street = result.street?.words().orEmpty()
        val name = result.name?.words().orEmpty()
        val listNumber = LIST_NUMBER.containsMatchIn(line)
        // Numeroväli alkunsa mukaan ("Kauppakatu 5-7" → 5).
        val parts = line.split(',').map { part ->
            NUMBER_RANGE.replace(expandStreetAbbreviations(part)) { it.groupValues[1] }.words()
        }
        val house = result.housenumber?.takeWhile { it.isDigit() }?.ifEmpty { null }
        fun matches(number: String?): Boolean {
            val wanted = number?.takeWhile { it.isDigit() }
            return wanted == null || house == null || wanted == house
        }
        fun indexOf(words: List<String>, wanted: List<String>) = words.windowed(wanted.size).indexOfFirst { window ->
            window.indices.all { (!window[it].isNumber() && !wanted[it].isNumber()) || window[it] == wanted[it] } &&
                similarName(window.joinToString(" "), wanted.joinToString(" "), maxTypos = 2)
        }
        // Kadun tai nimen perässä oleva talonumero osassa [index]; rivin postinumero ohitetaan.
        fun numberAfter(index: Int, at: Int, size: Int, distant: Boolean): Int? {
            val postcode = postcodeAt(parts, result.postcode, house)
            return houseNumberAt(parts[index], at, size, distant, postcode?.takeIf { it.first == index }?.second)
        }
        if (street.isNotEmpty()) {
            for ((index, words) in parts.withIndex()) {
                val at = indexOf(words, street)
                if (at >= 0) return matches(numberAfter(index, at, street.size, distant = true)?.let { words[it] })
            }
        }
        // Katu ei ole rivillä: nimi (ketjukohde tai katu, jolle Photon ei tiedä taloa) kelpaa, kun sen perässä oleva
        // numero täsmää eikä rivillä ole muuta katuosoitetta.
        if (name.isNotEmpty()) {
            for ((index, words) in parts.withIndex()) {
                val at = indexOf(words, name)
                if (at < 0) continue
                val numberAt = numberAfter(index, at, name.size, distant = false)
                return matches(numberAt?.let { words[it] }) &&
                    !hasStreetAddress(parts, result.city?.words().orEmpty(), listNumber, index, at until at + name.size, numberAt)
            }
        }
        return false
    }

    /**
     * Rivin postinumeron paikka (osa ja sana) tai null. Postinumero on viisinumeroinen luku: tuloksen postinumeroa
     * vastaava, muuten rivin viimeinen. Rivin ainoa viisinumeroinen on kuitenkin talonumero, kun tuloksen talonumero
     * on viisinumeroinen ("Käsivarrentie 14622") eikä luku ole tuloksen postinumero.
     */
    private fun postcodeAt(parts: List<List<String>>, postcode: String?, house: String?): Pair<Int, Int>? {
        val candidates = parts.flatMapIndexed { index, words ->
            words.indices.filter { words[it].isPostcode() }.map { index to it }
        }
        val known = postcode?.trim()
        candidates.lastOrNull { (part, word) -> parts[part][word] == known }?.let { return it }
        if (candidates.size == 1 && house?.length == 5) return null
        return candidates.lastOrNull()
    }

    /**
     * Rivin osan kohdasta [at] alkavan kadun tai nimen talonumeron paikka. Talonumero on nimen perässä ("Kauppakatu 5",
     * "Kauppakatu nro 5", listarivi "3. Iijoentie 1234"): rivin alun listan numero tai kohteen nimen numero kadun
     * edellä ei ole talonumero, eikä rivin postinumero ([postcode], paikka osassa). Kauempana perässä oleva numero
     * kelpaa vasta sitten ja vain kadulle ([distant]): nimen kohdalla se on toisen kadun talonumero.
     */
    private fun houseNumberAt(words: List<String>, at: Int, size: Int, distant: Boolean, postcode: Int?): Int? {
        val after = (at + size until words.size).filter { it != postcode }.dropWhile { words[it] in HOUSE_NUMBER_MARKERS }
        val next = after.firstOrNull()?.takeIf { words[it].isNumber() }
        if (next != null || !distant) return next
        return after.firstOrNull { words[it].isNumber() }
    }

    /**
     * Rivillä on nimen ([namePart], [nameRange]) ja sen talonumeron ([numberAt]) lisäksi katuosoite: numero, jonka
     * vieressä on muu sana kuin paikkakunta tai talonumeron merkintä ("Kauppakatu 5"). Vähintään viisinumeroinen luku
     * (postinumero "00120 Helsinki", puhelinnumero) ja rivin alun listan numero eivät ole katuosoitteita. Epäselvä rivi
     * hylätään mieluummin kuin hyväksytään.
     */
    private fun hasStreetAddress(
        parts: List<List<String>>,
        city: List<String>,
        listNumber: Boolean,
        namePart: Int,
        nameRange: IntRange,
        numberAt: Int?,
    ): Boolean = parts.withIndex().any { (index, words) ->
        fun isName(i: Int) = index == namePart && i in nameRange
        words.indices.any { i ->
            val token = words[i]
            token.isNumber() && token.digits() < 5 && !isName(i) && !(index == namePart && i == numberAt) &&
                !(listNumber && index == 0 && i == 0) &&
                listOfNotNull(
                    (i - 1 downTo 0).firstOrNull { words[it] !in HOUSE_NUMBER_MARKERS },
                    (i + 1 until words.size).firstOrNull { words[it] !in HOUSE_NUMBER_MARKERS },
                ).any { !words[it].isNumber() && !isName(it) && words[it] !in city }
        }
    }

    private fun String.isNumber() = first().isDigit()

    private fun String.digits() = takeWhile { it.isDigit() }.length

    /** Suomen postinumero: tasan viisi numeroa. */
    private fun String.isPostcode() = length == 5 && all { it.isDigit() }

    /** Pienet kirjaimet ilman tarkkeita sanoiksi pilkottuna: "Mänttä-Vilppula" → [mantta, vilppula]. */
    private fun String.words(): List<String> =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .split(NOT_WORD)
            .filter { it.isNotEmpty() }

    private suspend fun fetch(url: String): List<Resolved> {
        val body = client.execute(Request.Builder().url(url).get().build())
        val response = json.decodeFromString<PhotonResponse>(body)
        val seen = HashSet<String>()
        return response.features.mapNotNull { resolve(it) }
            .filter { countries.isEmpty() || it.countrycode?.uppercase(Locale.ROOT) in countries }
            .filter { seen.add(it.details.placeId) }
            .also { list -> cache.putAll(list.map { it.details }) }
    }

    private fun resolve(feature: PhotonFeature): Resolved? {
        val p = feature.properties ?: return null
        val coords = feature.geometry?.coordinates ?: return null
        if (coords.size < 2) return null
        val streetLine = listOfNotNull(p.street, p.housenumber).joinToString(" ").ifBlank { null }
        val cityLine = listOfNotNull(p.postcode, p.city).joinToString(" ").ifBlank { null }
        val primary = p.name ?: streetLine ?: p.city ?: return null
        val secondary = listOfNotNull(if (p.name != null) streetLine else null, cityLine).joinToString(", ")
        val address = listOfNotNull(streetLine ?: p.name, cityLine).joinToString(", ")
        val location = GeoPoint(lat = coords[1], lng = coords[0])
        val placeId = if (p.osmType != null && p.osmId != null) {
            "osm:${p.osmType}:${p.osmId}"
        } else {
            "c:%.5f,%.5f".format(Locale.ROOT, location.lat, location.lng)
        }
        return Resolved(
            suggestion = PlaceSuggestion(placeId, primary, secondary, "Photon · © OpenStreetMap", location),
            details = PlaceDetails(placeId, primary, address, location),
            type = p.type,
            city = p.city,
            postcode = p.postcode,
            name = p.name,
            street = p.street,
            housenumber = p.housenumber,
            countrycode = p.countrycode,
        )
    }

    private class Resolved(
        val suggestion: PlaceSuggestion,
        val details: PlaceDetails,
        val type: String?,
        val city: String?,
        val postcode: String?,
        val name: String?,
        val street: String?,
        val housenumber: String?,
        val countrycode: String? = null,
    )

    private companion object {
        val ADDRESS_TYPES = setOf("house", "street")
        const val PLAUSIBLE_KM = 50.0
        val COMBINING_MARKS = Regex("""\p{Mn}+""")
        val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
        val NUMBER_RANGE = Regex("""(\d+)\s*[-–]\s*\d+""")

        /** Listan numero rivin alussa: "3. Iijoentie 1234", "3) Iijoentie 1234". */
        val LIST_NUMBER = Regex("""^\s*\d+[.)]\s""")

        /** Talonumeron merkinnät kadun ja numeron välissä: "nro 7", "nr 5", "n:o 3". */
        val HOUSE_NUMBER_MARKERS = setOf("nro", "nr", "no", "n", "o")
    }
}

@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(val properties: PhotonProperties? = null, val geometry: PhotonGeometry? = null)

@Serializable
internal data class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class PhotonProperties(
    @SerialName("osm_type") val osmType: String? = null,
    @SerialName("osm_id") val osmId: Long? = null,
    val type: String? = null,
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val countrycode: String? = null,
)
