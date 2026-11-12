package fi.tyomatka.data.repo

import fi.tyomatka.core.geo.distanceKm
import fi.tyomatka.core.model.GeoPoint

/**
 * Saman talon pisteet eri hakupalveluista ja portaista: palvelut osuvat yleensä muutaman kymmenen metrin
 * sisään, mutta pitkän talon portaat voivat olla toisessa päässä. Samanniminen katu toisessa kunnassa on kauempana.
 */
private const val SAME_BUILDING_KM = 0.3

/** Pelkkä kohteen nimi ilman katuosoitetta riittää vain aivan vieressä: ketjun liikkeitä voi olla lähekkäin. */
private const val SAME_PLACE_KM = 0.1

/**
 * Sama osoite tai sama talo (samaan taloon riittää yksi stoppi, vaikka siellä olisi useampi
 * yritys tai asunto). Sama tunniste riittää. Muuten sama talo: sama katu ja talonumero ilman kirjainta, porrasta ja
 * asuntoa ("Kauppakatu 5 A 3" = "Kauppakatu 5 B 12" = "Kauppakatu 5a" = "Kauppakatu 5b") enintään
 * [SAME_BUILDING_KM] päässä. Osoitehaku käyttää useaa palvelua, joten sama kohde voi saada eri tunnisteen
 * ja eri muotoilun ("10 Rue de Rivoli" / "Rue de Rivoli 10"). Talossa olevan kohteen katuosoite on tekstin
 * toisessa osassa ("Studio 24, Hallituskatu 5" tai "Studio 24, 10 Rue de Rivoli"), ja se ratkaisee: kaksi
 * kohdetta eri katuosoitteissa ovat eri stopit, vaikka nimi olisi sama ja numeroitu. Muuten (kohteen nimi ja
 * postitoimipaikka) sama nimi kelpaa vain [SAME_PLACE_KM] päässä.
 */
internal fun isSameAddress(
    aPlaceId: String?,
    aAddress: String,
    aLocation: GeoPoint,
    bPlaceId: String?,
    bAddress: String,
    bLocation: GeoPoint,
): Boolean {
    if (!aPlaceId.isNullOrBlank() && aPlaceId == bPlaceId) return true
    val km = distanceKm(aLocation, bLocation)
    if (km > SAME_BUILDING_KM) return false
    val a = aAddress.parts()
    val b = bAddress.parts()
    if (a.isEmpty() || b.isEmpty()) return false
    // Samanniminen kohde eri kadulla on eri paikka, vaikka talonumero puuttuisi ("Studio 24, Hallituskatu, Oulu").
    // Toinen osa voi olla myös paikkakunnan tarkennus: numerolla alkava ensimmäinen osa on talon oma osoite
    // ("100 Main Street, Brooklyn"), ja toisen osoitteen toinen osa voi löytyä toisen muista osista
    // ("Kungsgatan 10, Inom Vallgraven, Göteborg" / "Kungsgatan 10, Göteborg, Västra Götaland"). Postinumero ei
    // erota paikkakuntaa ("411 19 Göteborg" = "Göteborg"), eikä pelkkä postinumero-osa kerro kadusta mitään, mutta
    // kadun nimen numero erottaa kadut ("East 23rd Street" ≠ "East 24th Street", "Calle 23 Norte" ≠ "Calle 24
    // Norte"). Kirjaimia sisältävä postinumero ("SW1A 2AA", "SE-411 19") jää paikkakunnan nimeen: silloin sama talo
    // voi jäädä kahdeksi stopiksi, mikä on parempi kuin eri kohteiden yhdistyminen.
    val aPlaces = a.map { it.withoutPostcode() }
    val bPlaces = b.map { it.withoutPostcode() }
    if (a.size >= 3 && b.size >= 3 && sameName(a.first(), b.first()) && numberFirst(a.first()) == null &&
        streetName(a[1]) != streetName(b[1]) && !bPlaces.hasPlace(aPlaces[1]) && !aPlaces.hasPlace(bPlaces[1])
    ) {
        return false
    }
    val aBuilding = building(a)
    if (aBuilding != null && aBuilding == building(b)) return true
    // Samanniminen liike eri katuosoitteessa on eri stoppi.
    if (namedStreet(a) != null && namedStreet(b) != null) return false
    return km <= SAME_PLACE_KM && a.first() == b.first()
}

/** Kohteen nimi on sama myös, kun vain numeron perässä oleva kirjain eroaa ("studio 24a" / "studio 24b"). */
private fun sameName(a: String, b: String): Boolean = (streetFirst(a) ?: a) == (streetFirst(b) ?: b)

private fun String.parts(): List<String> =
    split(',').map { it.trim().lowercase().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }

/**
 * Osa ilman alun ja lopun numerosanoja (numerot ja väliviivat: postinumero paikkakunnan edellä tai perässä):
 * "411 19 göteborg" → "göteborg", "00-950 warszawa" → "warszawa", pelkkä postinumero → "". Kadun nimen numerot jäävät
 * ("east 23rd street", "calle 23 norte"); osan lopun talonumero poistuu ("hallituskatu 5" → "hallituskatu").
 */
private fun String.withoutPostcode(): String =
    split(' ').dropWhile(::isNumberWord).dropLastWhile(::isNumberWord).joinToString(" ")

private fun isNumberWord(word: String): Boolean = word.all { it.isDigit() || it == '-' }

/** Tyhjä paikka (pelkkä postinumero) sopii mihin tahansa. */
private fun List<String>.hasPlace(place: String): Boolean = place.isEmpty() || place in this

/** Osan kadun nimi ilman talonumeroa ("hallituskatu 5" → "hallituskatu", "10 rue de rivoli" → "rue de rivoli"), muuten teksti. */
private fun streetName(part: String): String =
    STREET_FIRST.matchEntire(part)?.groupValues?.get(1)?.trim()
        ?: NUMBER_FIRST.matchEntire(part)?.groupValues?.get(2)?.trim()
        ?: part

/**
 * Kohteen nimen jälkeinen katuosoite: "studio 24, hallituskatu 5, 90100 oulu" → "hallituskatu 5". Numero kadun
 * edellä ("studio 24, 10 rue de rivoli, 75004 pariisi") kelpaa vain, kun postinumero tulee myöhemmin: muuten toinen
 * osa voi olla postinumero ja kaupunki ("damrak 1, 1012 lg amsterdam, nederland", jos maan nimi jäi osoitteeseen).
 */
private fun namedStreet(parts: List<String>): String? {
    val part = parts.getOrNull(1) ?: return null
    return streetFirst(part) ?: numberFirst(part)?.takeIf { parts.drop(2).any { later -> later.any(Char::isDigit) } }
}

/**
 * Talo: kohteen katuosoite tai ensimmäisen osan katu ja talonumero. Ilman kohteen katuosoitetta numeroitu nimi
 * ("studio 24") näyttää katuosoitteelta; kaksi samannimistä kohdetta ilman katuosoitetta erottaa vain etäisyys.
 */
private fun building(parts: List<String>): String? =
    namedStreet(parts) ?: parts.first().let { streetFirst(it) ?: numberFirst(it) }

/** "kauppakatu 5 a 3", "kauppakatu 5a", "kauppakatu 5b 3", "kauppakatu 5-7", "kungsgatan 10 lgh 1102" → "kauppakatu 5". */
private fun streetFirst(part: String): String? =
    STREET_FIRST.matchEntire(part)?.destructured?.let { (street, number) -> "${street.trim()} $number" }

private fun numberFirst(part: String): String? =
    NUMBER_FIRST.matchEntire(part)?.destructured?.let { (number, street) -> "${street.trim()} $number" }

/** Numeroon kiinni kirjoitettu kirjain ja sen perässä asunto: "a", "b 3". */
private const val LETTER = """(?:\p{L}(?:\s+\d+)?)?"""

/**
 * Väli, porras ja asunto talonumeron jälkeen: "-7", " A", " B 12", " lgh 1102". Asunnon numero vain kirjaimen tai
 * portaan perässä: muuten numeroon päättyvän kadun ("Valtatie 4 12") talonumero jäisi asunnoksi ja eri talot
 * yhdistyisivät.
 */
private const val TAIL = """(?:\s*[-–]\s*\d+\p{L}?)?(?:\s+\p{L}{1,3}(?:\s*\d+)?)?"""
private val STREET_FIRST = Regex("""(\p{L}.*?)\s+(\d+)$LETTER$TAIL""")
private val NUMBER_FIRST = Regex("""(\d+)\p{L}?\s+(\p{L}.*)""")
