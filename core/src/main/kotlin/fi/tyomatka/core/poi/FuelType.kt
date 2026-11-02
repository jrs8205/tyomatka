package fi.tyomatka.core.poi

/** Polttoainelaji; [key] on palvelimen tunniste (kertatuonnin `k:fuels` ja ilmoituksen `fuels`). */
enum class FuelType(val key: String) {
    DIESEL("diesel"),
    HVO("hvo"),
    PETROL95("petrol95"),
    PETROL98("petrol98"),
    E85("e85"),
    MPO("mpo"),
    BIOGAS("biogas"),
    LPG("lpg"),
    CNG("cng"),
    LNG("lng");

    val isGas: Boolean get() = this == BIOGAS || this == LPG || this == CNG || this == LNG

    companion object {
        fun of(key: String): FuelType? = entries.firstOrNull { it.key == key }
    }
}

/** Myytävä polttoaine; [product] on ketjun tuotenimi (vain kertatuonnissa). */
data class FuelOffer(val type: FuelType, val product: String?)

/**
 * `diesel=Neste Futura Diesel;hvo=Neste MY` (kertatuonti) tai `diesel;hvo` (ilmoitus); `none` = ei polttoaineita.
 * Tuntematon laji ohitetaan, ja sama laji otetaan vain kerran.
 */
fun parseFuels(value: String): List<FuelOffer> {
    if (value.trim() == "none") return emptyList()
    val offers = mutableListOf<FuelOffer>()
    value.split(';').forEach { part ->
        val type = FuelType.of(part.substringBefore('=').trim()) ?: return@forEach
        if (offers.none { it.type == type }) {
            val product = if ('=' in part) part.substringAfter('=').trim().takeIf(String::isNotEmpty) else null
            offers += FuelOffer(type, product)
        }
    }
    return offers
}
