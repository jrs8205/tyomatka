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
     * (Göteborgissa "Kungsgatan 10" → Marstrand); `location_bias_scale=0` + `zoom=14` painottaa
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
     * Tuloksen katu on jossakin rivin osassa (lyhenteet auki, pieni kirjoitusvirhe sallitaan), ja sen vieressä oleva
     * talonumero täsmää tuloksen numeroon, jos molemmissa on numero. Photon antaa paikkakunnan sisältä muun kohteen
     * ("Hakatie 2, Piikkiö" → Piikkiön rautatieasema; "Kauppak. 5, Oulu" → Kauppakeskus Valkea) tai saman
     * kadun toisen talon ("Isok. 10, Oulu" → Isokatu 102). Jos katu ei ole rivillä, tuloksen nimi kelpaa vain, kun
     * rivillä ei ole nimen lisäksi katuosoitetta ([hasStreetAddress]): samanniminen ketjukohde tulee muuten väärältä
     * kadulta ("R-kioski, Kauppakatu 5, Oulu" → R-kioski Isokatu 20), ja väärä stoppi on pahempi kuin hylätty rivi.
     */
    private fun streetMatches(result: Resolved, line: String): Boolean {
        val street = result.street?.words().orEmpty()
        val name = result.name?.words().orEmpty()
        val names = listOf(street, name).filter { it.isNotEmpty() }
        val postcode = result.postcode?.words().orEmpty()
        val split = line.split(',').map { part ->
            // Numeroväli alkunsa mukaan ("10-12 Rue de Rivoli" → 10), paitsi väliviivallinen postinumero ("00-950").
            NUMBER_RANGE.replace(expandStreetAbbreviations(part)) { if (it.value.words() == postcode) it.value else it.groupValues[1] }
                .words()
        }
        // Postinumero poistetaan vain yhdestä esiintymästä, koska talonumero voi olla sama luku. Esiintymä on
        // postinumero, kun se on kaksiosainen ("411 19"), oma osansa ("Hverfisgata 101 Reykjavík, 101"), tuloksen
        // paikkakunnan edellä ("Mannerheimintie 00100 Helsinki", "101 Reykjavík, Hverfisgata 101") tai vähintään
        // nelinumeroisena osan lopussa kadun perässä, kun kadun edellä on numero ("10 Storgata 9008", "Bónus 10
        // Storgata 9008"), joka ei ole rivin alun listan numero ("3. Storgata 9008": Photon tulkitsisi listan numeron
        // talonumeroksi). Kolminumeroinen (Islanti) on kadun perässä talonumero, koska siellä talonumero tulee kadun
        // jälkeen ("Studio 24 Hverfisgata 101", "3. Hverfisgata 101"). Muuten kadun vieressä luku on talonumero
        // ("Hverfisgata 101, Reykjavík", "101 Hverfisgata", "Reykjavík 101, Hverfisgata 101"). Paikkakunta
        // tunnistetaan tuloksen tiedoista (kaupunki, kaupunginosa, kylä, seutukunta, maakunta, maa), ei mistä tahansa
        // luvun perässä olevasta sanasta: porraskirjain, kerros tai rakennus ("Hverfisgata 101 A", "Hverfisgata 101
        // kjallari") jättää talonumeron paikalleen. Maan nimi kelpaa vain vähintään
        // nelinumeroisen perässä ("Kirkkotie 95100 Suomi", "10 Storgata 9008 Norge"), koska Islannissa kolminumeroinen
        // kadun perässä on talonumero myös maan edellä ("Hverfisgata 101 Ísland"). Usean
        // postinumeroksi tulkitun esiintymän rivillä postinumero on viimeinen ("Hverfisgata 101 Reykjavík, 101").
        val found = if (postcode.isEmpty()) emptyList() else split.flatMapIndexed { index, words ->
            words.windowed(postcode.size).withIndex().filter { it.value == postcode }.map { index to it.index }
        }
        val listNumber = LIST_NUMBER.containsMatchIn(line)
        val places = result.placeNames().map { it.words() }.filter { it.isNotEmpty() }
        val countries = result.countryNames().map { it.words() }.filter { it.isNotEmpty() }
        fun isPostcode(index: Int, at: Int): Boolean {
            val words = split[index]
            if (postcode.size > 1 || words.size == postcode.size) return true
            val rest = words.drop(at + postcode.size)
            if (rest.isEmpty()) return postcode.single().length >= 4 && names.any { name ->
                val street = at - name.size
                street >= 1 && words[street - 1].isNumber() && !(listNumber && index == 0 && street == 1) &&
                    similarName(words.subList(street, at).joinToString(" "), name.joinToString(" "), maxTypos = 2)
            }
            fun follows(place: List<String>) =
                similarName(rest.take(place.size).joinToString(" "), place.joinToString(" "), maxTypos = 2)
            return places.any(::follows) || (postcode.single().length >= 4 && countries.any(::follows))
        }
        val (postcodePart, postcodeAt) = found.lastOrNull { (index, at) -> isPostcode(index, at) } ?: (-1 to -1)
        val parts = split.mapIndexed { index, words ->
            if (index == postcodePart) words.take(postcodeAt) + words.drop(postcodeAt + postcode.size) else words
        }
        val house = result.housenumber?.takeWhile { it.isDigit() }?.ifEmpty { null }
        fun matches(number: String?): Boolean {
            val wanted = number?.takeWhile { it.isDigit() }
            return wanted == null || house == null || wanted == house
        }
        fun indexOf(words: List<String>, wanted: List<String>) = words.windowed(wanted.size).indexOfFirst {
            similarName(it.joinToString(" "), wanted.joinToString(" "), maxTypos = 2)
        }
        if (street.isNotEmpty()) {
            for (words in parts) {
                val at = indexOf(words, street)
                if (at >= 0) return matches(houseNumberAt(words, at, street.size, distant = true)?.let { words[it] })
            }
        }
        // Katu ei ole rivillä: nimi (ketjukohde tai katu, jolle Photon ei tiedä taloa) kelpaa, kun sen vieressä oleva
        // numero täsmää eikä rivillä ole muuta katuosoitetta.
        if (name.isNotEmpty()) {
            for ((index, words) in parts.withIndex()) {
                val at = indexOf(words, name)
                if (at < 0) continue
                val numberAt = houseNumberAt(words, at, name.size, distant = false)
                return matches(numberAt?.let { words[it] }) &&
                    !hasStreetAddress(parts, result.city?.words().orEmpty(), listNumber, index, at until at + name.size, numberAt)
            }
        }
        return false
    }

    /**
     * Rivin osan kohdasta [at] alkavan kadun tai nimen talonumeron paikka. Talonumero on nimen vieressä: ensin perässä
     * ("Straße des 17. Juni 135", "Hauptstraße Nr. 5", listarivi "3. Kungsgatan 10"), sitten edellä ("10 Rue de Rivoli
     * Apt 2"). Perässä oleva viisinumeroinen on postinumero, kun edellä on numero ("10 Rue de Rivoli 75004 Paris",
     * vaikka tuloksesta puuttuisi postinumero); nelinumeroinen voi olla maaseudun talonumero ("3. Iijoentie 1234").
     * Kauempana oleva numero kelpaa vasta sitten ja vain kadulle ([distant]): nimen kohdalla se on toisen kadun
     * talonumero.
     */
    private fun houseNumberAt(words: List<String>, at: Int, size: Int, distant: Boolean): Int? {
        val after = (at + size until words.size).dropWhile { words[it] in HOUSE_NUMBER_MARKERS }
        val before = 0 until at
        val next = after.firstOrNull()?.takeIf { words[it].isNumber() }
        val previous = before.lastOrNull()?.takeIf { words[it].isNumber() }
        val near = if (previous != null && next != null && words[next].digits() >= 5) previous else next ?: previous
        if (near != null || !distant) return near
        return after.firstOrNull { words[it].isNumber() } ?: before.lastOrNull { words[it].isNumber() }
    }

    /**
     * Rivillä on nimen ([namePart], [nameRange]) ja sen talonumeron ([numberAt]) lisäksi katuosoite: numero, jonka
     * vieressä on muu sana kuin paikkakunta tai talonumeron merkintä ("Kauppakatu 5", "10 Storgata"). Postinumero ei
     * ole katuosoite: viisinumeroinen ("00120 Helsinki", vaikka tuloksen postinumero olisi toinen), paikkakunnan
     * vieressä oleva ("9008 Tromsø") tai rivin alun listan numero. Epäselvä rivi hylätään mieluummin kuin hyväksytään.
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
            district = p.district,
            locality = p.locality,
            county = p.county,
            state = p.state,
            country = p.country,
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
        val district: String? = null,
        val locality: String? = null,
        val county: String? = null,
        val state: String? = null,
        val country: String? = null,
        val countrycode: String? = null,
    ) {
        /** Tuloksen paikkakuntatiedot: kaupunki sekä kaupunginosa, kylä, seutukunta ja maakunta, jos Photon ne antaa. */
        fun placeNames(): List<String> = listOfNotNull(city, district, locality, county, state)

        /**
         * Tuloksen maan nimet. Photon antaa maan nimen vain pyydetyllä kielellä, mutta rivillä se voi olla toisella
         * ("Kirkkotie 95100 Finland"), joten mukana ovat myös maakoodin mukaiset nimet [COUNTRY_NAMES]-taulukosta.
         * Erillään [placeNames]ista, koska maan nimi kelpaa paikkakuntatiedoksi vain vähintään nelinumeroisen
         * postinumeron perässä ([streetMatches]).
         */
        fun countryNames(): List<String> =
            listOfNotNull(country) + COUNTRY_NAMES[countrycode?.uppercase(Locale.ROOT)].orEmpty()
    }

    private companion object {
        val ADDRESS_TYPES = setOf("house", "street")
        const val PLAUSIBLE_KM = 50.0
        val COMBINING_MARKS = Regex("""\p{Mn}+""")
        val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
        val NUMBER_RANGE = Regex("""(\d+)\s*[-–]\s*\d+""")

        /** Listan numero rivin alussa: "3. Storgata 9008", "3) Storgata 9008". */
        val LIST_NUMBER = Regex("""^\s*\d+[.)]\s""")

        /** Talonumeron merkinnät kadun ja numeron välissä: "Nr. 5", "nº 10", "n.º 10", "n. 3", "nro 7". */
        val HOUSE_NUMBER_MARKERS = setOf("nr", "nro", "no", "n", "nº", "º", "num", "numero")

        /** Maan nimi maakoodin mukaan suomeksi ja englanniksi (haku rajataan Suomeen). */
        val COUNTRY_NAMES = mapOf(
            "FI" to listOf("Suomi", "Finland"),
        )
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
    val district: String? = null,
    val locality: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    val countrycode: String? = null,
)
