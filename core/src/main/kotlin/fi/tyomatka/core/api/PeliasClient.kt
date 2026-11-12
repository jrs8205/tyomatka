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

/**
 * Pelias-yhteensopiva osoitehaku (`/autocomplete`, `/search`, `/reverse`) Digitransitissa (Suomi, viralliset
 * osoitteet). Tulokset pidetään välimuistissa [placeDetails]-hakua varten.
 */
class PeliasClient(
    private val client: OkHttpClient,
    baseUrl: String,
    private val limit: Int = 8,
    private val layers: String = "address,street,venue",
    /** Lähdemaininta tulosten alle; oletuksena palvelimen nimi. */
    private val source: String = baseUrl.toHttpUrl().host,
) : PlaceSearch, ReverseGeocoder {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    /** Kelpaavat tasot: samat, joihin haku rajataan. */
    private val placeLayers = layers.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val cache = PlaceCache()

    override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> {
        if (input.isBlank()) return emptyList()
        fun url() = base.newBuilder()
            .addPathSegment("autocomplete")
            .addQueryParameter("text", input.trim())
            .addQueryParameter("size", limit.toString())
            .addQueryParameter("layers", layers)
            .focus(biasLocation)
        val found = if (biasLocation == null) {
            fetch(url()).results
        } else {
            val box = boxAround(biasLocation)
            nearbyFirst(
                limit,
                key = { it.details.placeId },
                nearby = {
                    fetch(
                        url().addQueryParameter("boundary.rect.min_lat", box.south.toString())
                            .addQueryParameter("boundary.rect.max_lat", box.north.toString())
                            .addQueryParameter("boundary.rect.min_lon", box.west.toString())
                            .addQueryParameter("boundary.rect.max_lon", box.east.toString())
                    ).results
                },
                wide = { fetch(url()).results },
            )
        }
        if (found.isNotEmpty()) return found.map { it.suggestion }
        // Digitransitin autocomplete ei tunne ruotsinkielisiä osoitteita ("Mannerheimvägen 10
        // Helsingfors"), search tuntee; ulkomaiset samannimiset kadut karsitaan kaupungin mukaan.
        val searched = fetch(searchUrl(input, limit, biasLocation))
        return searched.results.filter { isPlausible(it, searched.parsed, partial = true) }.map { it.suggestion }
    }

    override suspend fun placeDetails(placeId: String): PlaceDetails = cache.get(placeId)

    override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? {
        if (addressLine.isBlank()) return null
        val fetched = fetch(searchUrl(addressLine, 1, biasLocation))
        val best = fetched.results.firstOrNull() ?: return null
        return best.details.takeIf { isPlausible(best, fetched.parsed, partial = false) }
    }

    /** Lähin katuosoite (`/reverse`): Pelias kertoo etäisyyden kilometreinä, ja raja tarkistetaan siitä. */
    override suspend fun reverse(point: GeoPoint, maxDistanceM: Double): PlaceDetails? {
        val builder = base.newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("point.lat", point.lat.toString())
            .addQueryParameter("point.lon", point.lng.toString())
            .addQueryParameter("layers", "address")
            .addQueryParameter("size", "1")
        val best = fetch(builder).results.firstOrNull() ?: return null
        val distanceM = best.distanceKm?.let { it * 1000 } ?: (distanceKm(best.details.location, point) * 1000)
        return best.details.takeIf { distanceM <= maxDistanceM }
    }

    /** Search rajataan samoihin tasoihin kuin autocomplete: muuten pysäkki tai kunta voi olla ainoa osuma. */
    private fun searchUrl(text: String, size: Int, bias: GeoPoint?) = base.newBuilder()
        .addPathSegment("search")
        .addQueryParameter("text", text.trim())
        .addQueryParameter("size", size.toString())
        .addQueryParameter("layers", layers)
        .focus(bias)

    /**
     * Search-haku sovittaa herkästi muualle: pelkkä kunta (kaupungin keskipiste), samannimisen
     * kadun ulkomaille (Göteborgin Kungsgatan → Raaseporin Kuninkaankatu) tai toisen kadun samassa
     * kunnassa ("Hakatie 2, Piikkiö" → Hietatie 2). Kelpaa vain haun tasojen tulos, ja
     * epävarman osuman kunnan ja kadun on vastattava haettua; pieni kirjoitusvirhe sallitaan ja kadun lyhenne
     * avataan ("Kauppak." → Kauppakatu). Kesken kirjoitettu haku ([partial]) kelpaa nimen alkuna.
     */
    private fun isPlausible(result: Resolved, parsed: PeliasParsedText?, partial: Boolean): Boolean {
        if (result.layer != null && result.layer !in placeLayers) return false
        if ((result.confidence ?: 0.0) >= 1.0) return true
        val city = parsed?.city?.trim()?.lowercase()?.ifBlank { null }
        if (city != null && result.placeNames.none { resembles(it, city, maxTypos = 1, partial) }) return false
        val street = parsed?.street?.let(::expandStreetAbbreviations)?.trim()?.lowercase()?.ifBlank { null }
        if (street != null && result.layer != "venue" && result.streetNames.none { resembles(it, street, maxTypos = 2, partial) }) return false
        return true
    }

    /** Sama nimi pienellä kirjoitusvirheellä ([similarName]); kesken kirjoitettu haku ([partial]) myös nimen alkuna. */
    private fun resembles(candidate: String, wanted: String, maxTypos: Int, partial: Boolean): Boolean =
        (partial && wanted.length >= MIN_PREFIX && candidate.trim().lowercase().startsWith(wanted)) ||
            similarName(candidate, wanted, maxTypos)

    private fun HttpUrl.Builder.focus(location: GeoPoint?) = apply {
        if (location != null) {
            addQueryParameter("focus.point.lat", location.lat.toString())
            addQueryParameter("focus.point.lon", location.lng.toString())
        }
    }

    private suspend fun fetch(builder: HttpUrl.Builder): Fetched {
        val request = Request.Builder().url(builder.build()).get().build()
        val body = client.execute(request)
        val response = json.decodeFromString<PeliasResponse>(body)
        val seen = HashSet<String>()
        val results = response.features.mapNotNull { resolve(it) }
            .filter { seen.add(it.details.placeId) }
            .also { list -> cache.putAll(list.map { it.details }) }
        return Fetched(response.geocoding?.query?.parsedText, results)
    }

    private fun resolve(feature: PeliasFeature): Resolved? {
        val p = feature.properties ?: return null
        val coords = feature.geometry?.coordinates ?: return null
        if (coords.size < 2) return null
        val placeId = p.gid ?: return null
        // Pelias antaa osoitteen nimen jo numeroineen ("Mallireuna 2").
        val primary = p.name ?: listOfNotNull(p.street, p.housenumber).joinToString(" ").ifBlank { null } ?: return null
        val cityLine = listOfNotNull(p.postalcode, p.locality ?: p.localadmin ?: p.region).joinToString(" ").ifBlank { null }
        val location = GeoPoint(lat = coords[1], lng = coords[0])
        // Labelin osat kantavat paikkakunnan haun kielellä ("Storgatan 1, Uleåborg").
        val placeNames = listOfNotNull(p.locality, p.localadmin, p.region, p.neighbourhood) + p.label?.split(',').orEmpty()
        // Kadun nimet: street-kenttä sellaisenaan (nimessä voi olla numero: "3. linja") sekä nimen ja
        // labelin alku ilman talonumeroa, sulkeissa oleva kieliversio omana nimenään
        // ("Kuninkaankatu 10 (Kungsgatan 10)" → Kuninkaankatu, Kungsgatan).
        val streetNames = listOfNotNull(p.street) + listOfNotNull(p.name, p.label?.substringBefore(','))
            .flatMap { it.languageVersions() }
            .map { it.withoutHouseNumber(p.housenumber) }
        // OpenStreetMapista peräisin oleva osoite saa myös OSM-maininnan.
        val credit = if (placeId.startsWith("openstreetmap:")) "$source · © OpenStreetMap" else source
        return Resolved(
            suggestion = PlaceSuggestion(placeId, primary, cityLine.orEmpty(), credit, location),
            details = PlaceDetails(placeId, primary, listOfNotNull(primary, cityLine).joinToString(", "), location),
            confidence = p.confidence,
            layer = p.layer,
            distanceKm = p.distance,
            placeNames = placeNames,
            streetNames = streetNames,
        )
    }

    /** "Kuninkaankatu 10 (Kungsgatan 10)" → "Kuninkaankatu 10" ja "Kungsgatan 10". */
    private fun String.languageVersions(): List<String> =
        listOf(substringBefore('(')) + listOfNotNull(substringAfter('(', "").substringBefore(')').ifBlank { null })

    /**
     * Talonumero pois nimen lopusta ("Mannerheimvägen 10"). Ilman tunnettua numeroa poistetaan lopun numero
     * porraskirjaimineen ("Kauppakatu 5 A 3").
     */
    private fun String.withoutHouseNumber(housenumber: String?): String {
        val text = trim()
        val number = housenumber?.trim()?.ifBlank { null }
        return when {
            number != null && text.endsWith(" $number", ignoreCase = true) -> text.dropLast(number.length + 1)
            else -> text.replace(HOUSE_NUMBER_TAIL, "")
        }.trim()
    }

    private class Resolved(
        val suggestion: PlaceSuggestion,
        val details: PlaceDetails,
        val confidence: Double?,
        val layer: String?,
        val placeNames: List<String>,
        val streetNames: List<String>,
        /** Etäisyys hakupisteestä kilometreinä (`/reverse`). */
        val distanceKm: Double? = null,
    )

    private class Fetched(val parsed: PeliasParsedText?, val results: List<Resolved>)

    private companion object {
        /** Lyhin nimen alku, joka kelpaa kesken kirjoitetuksi hauksi. */
        const val MIN_PREFIX = 3

        /** Lopun talonumero ja sen perässä oleva kirjain, porras tai asunto ("10", "15b", "2-4", "5 A 3"). */
        val HOUSE_NUMBER_TAIL = Regex("""\s+\d+[^\s,]*(\s+[A-Za-z]{1,2}(\s+\d+)?)?$""")
    }
}

@Serializable
internal data class PeliasResponse(
    val features: List<PeliasFeature> = emptyList(),
    val geocoding: PeliasGeocoding? = null,
)

@Serializable
internal data class PeliasGeocoding(val query: PeliasQuery? = null)

@Serializable
internal data class PeliasQuery(@SerialName("parsed_text") val parsedText: PeliasParsedText? = null)

@Serializable
internal data class PeliasParsedText(val city: String? = null, val street: String? = null)

@Serializable
internal data class PeliasFeature(val properties: PeliasProperties? = null, val geometry: PeliasGeometry? = null)

@Serializable
internal data class PeliasGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class PeliasProperties(
    val gid: String? = null,
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val postalcode: String? = null,
    val locality: String? = null,
    val localadmin: String? = null,
    val region: String? = null,
    val neighbourhood: String? = null,
    val label: String? = null,
    val confidence: Double? = null,
    val layer: String? = null,
    val distance: Double? = null,
)
