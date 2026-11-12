package fi.tyomatka.core.api

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Valitun yrityksen stoppi: nimi Overturesta, osoite ja sijainti ensisijaisesti osoitehausta. (1) Katuosoite
 * numeroineen geokoodataan ja hyväksytään, kun tulos on sama katu ja numero (Overturen piste voi olla satoja metrejä
 * sivussa); (2) muuten lähin osoite [MAX_ADDRESS_M]:n säteeltä Overturen pisteestä, paitsi kun geokoodaus antoi toisen
 * nimisen kadun samalla numerolla lähellä ja lähin osoite on samalla kadulla: silloin kyse on kieliversiosta
 * ("Kungsgatan 10" → "Kuninkaankatu 10") ja geokoodattu osoite kelpaa; (3) muuten Overturen oma osoite ja piste, ja
 * ilman osoitetietoja koordinaatit. Jokainen vaihe katkaistaan [stepTimeoutMs]:ssä, ja vaiheen virhe siirtää seuraavaan.
 */
class BusinessPlaceResolver(
    private val geocode: suspend (String, GeoPoint?) -> PlaceDetails?,
    private val reverse: ReverseGeocoder,
    private val stepTimeoutMs: Long = 3_000,
) {
    suspend fun resolve(business: Business): PlaceDetails {
        val placeId = BusinessClient.PREFIX + business.id
        val street = business.street
        // Toisen niminen katu samalla numerolla lähellä: kieliversio tai eri katu, ratkaistaan lähimmällä osoitteella.
        var otherName: PlaceDetails? = null
        if (street != null && streetKey(street) != null) {
            val found = step { geocode(geocodeLine(street, business), business.location) }
            if (found != null && sameStreetNumber(street, found.address)) {
                return PlaceDetails(placeId, business.name, found.address, found.location)
            }
            if (found != null && sameNumberNearby(street, found, business.location)) otherName = found
        }
        val nearest = step { reverse.reverse(business.location, MAX_ADDRESS_M) }?.takeIf { it.address.isNotBlank() }
        // Pelias sallii kadun nimeen kirjoitusvirheen ("Metsätie 4" → "Lintutie 4"), joten pelkkä numero ja etäisyys eivät
        // riitä: kieliversio kelpaa vain, kun Overturen pisteen lähin osoite on samalla kadulla kuin geokoodattu.
        if (otherName != null && nearest != null && sameStreet(otherName.address, nearest.address)) {
            return PlaceDetails(placeId, business.name, otherName.address, otherName.location)
        }
        if (nearest != null) return PlaceDetails(placeId, business.name, nearest.address, nearest.location)
        // Ilman katua ja paikkakuntaa koordinaatit kuten karttakohteen stopilla: tyhjä osoite sekoittaisi osoitemuistin.
        val address = business.addressLine.ifBlank {
            String.format(Locale.ROOT, "%.4f, %.4f", business.location.lat, business.location.lng)
        }
        return PlaceDetails(placeId, business.name, address, business.location)
    }

    /** Digitransit antaa kaksikielisellä alueella suomenkielisen nimen ("Kuninkaankatu 10" haulle "Kungsgatan 10"). */
    private fun sameNumberNearby(street: String, found: PlaceDetails, point: GeoPoint): Boolean {
        val number = streetKey(street)?.second ?: return false
        return number == streetKey(found.address)?.second && distanceKm(found.location, point) * 1000 <= MAX_ADDRESS_M
    }

    private fun geocodeLine(street: String, business: Business): String {
        val area = listOfNotNull(business.postcode, business.locality?.let(::capitalizeUpperWords)).joinToString(" ")
        return if (area.isEmpty()) street else "$street, $area"
    }

    private suspend fun <T : Any> step(block: suspend () -> T?): T? = try {
        withTimeoutOrNull(stepTimeoutMs) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Lähin osoite enintään tämän päässä Overturen pisteestä; kauempana osoite olisi todennäköisesti väärä rakennus. */
        const val MAX_ADDRESS_M = 300.0
    }
}

private val whitespace = Regex("\\s+")
private val houseNumber = Regex("^(\\d+)(\\p{L}?)")
/** Porras tai huoneiston, liiketilan tai kerroksen merkintä: 1–3 kirjainta, piste sallittu ("B", "as", "LH", "bst", "krs"). */
private val unitMark = Regex("^\\p{L}{1,3}\\.?$")
private val digits = Regex("^\\d+$")
private val ordinal = Regex("^\\d+\\.$")

/** Yhteen kirjoitettu merkintä ja numero ("A5", "B12", "as5"). */
private val joinedUnit = Regex("^\\p{L}{1,3}\\.?\\d+$")

/** Talonumero kirjaimineen ("3A"): sen perässä oleva numero on huoneisto ("3A 5"). */
private val numberWithLetter = Regex("^\\d+\\p{L}$")
private val rangeDashes = setOf("-", "–")

private fun startsWithDigit(word: String) = word.firstOrNull()?.isDigit() == true

/**
 * Kadun nimi ja talonumero kirjaimineen ("teollisuustie" ja "3a") osoitteen ensimmäisestä pilkkuosasta; ilman numeroa
 * null. Talonumero luetaan lopusta, jotta kadun nimen numero säilyy nimessä ("Linja 3 4", "3. linja 4"). Sitä ennen pois
 * talonumeron perässä oleva merkintä numeroineen ("12 B 5", "3 as 5", "5 bst 12", "4 A", yhteen kirjoitettuna "3 A5"),
 * huoneisto kirjaimellisen talonumeron perässä ("3A 5"), kerros ("10 3. krs") ja välin loppu ("2 - 6"; "2-6" on yksi
 * sana). Merkintä poistetaan vain talonumeron perästä, joten "Kustaa III 5" säilyy.
 */
private fun streetKey(address: String): Pair<String, String>? {
    var words = address.substringBefore(',').trim().lowercase(Locale.ROOT).split(whitespace).filter { it.isNotEmpty() }
    val size = words.size
    if (size >= 4 && digits.matches(words[size - 1]) && unitMark.matches(words[size - 2]) && startsWithDigit(words[size - 3])) {
        words = words.dropLast(2)
    } else if (size >= 3 && (unitMark.matches(words[size - 1]) || joinedUnit.matches(words[size - 1])) && startsWithDigit(words[size - 2])) {
        words = words.dropLast(1)
    } else if (size >= 3 && digits.matches(words[size - 1]) && numberWithLetter.matches(words[size - 2])) {
        words = words.dropLast(1)
    }
    if (words.size >= 3 && ordinal.matches(words.last()) && startsWithDigit(words[words.size - 2])) words = words.dropLast(1)
    if (words.size >= 3 && words[words.size - 2] in rangeDashes && digits.matches(words.last())) words = words.dropLast(2)
    if (words.size < 2) return null
    val number = houseNumber.find(words.last()) ?: return null
    return words.dropLast(1).joinToString(" ") to number.groupValues[1] + number.groupValues[2]
}

/** Sama kadun nimi talonumerosta riippumatta. */
private fun sameStreet(a: String, b: String): Boolean {
    val street = streetKey(a)?.first ?: return false
    return street == streetKey(b)?.first
}

/** Sama katu ja talonumero kirjaimineen; väli ja porras ohitetaan ("Teollisuustie 2-6" = "Teollisuustie 2"). */
internal fun sameStreetNumber(street: String, address: String): Boolean {
    val key = streetKey(street) ?: return false
    return key == streetKey(address)
}
