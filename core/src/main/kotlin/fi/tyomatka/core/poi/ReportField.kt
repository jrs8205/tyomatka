package fi.tyomatka.core.poi

/**
 * Kuljettajan ilmoituksen kenttä; [key] on palvelimen `field`. [accepts] noudattaa samoja sääntöjä kuin palvelimen
 * `REPORT_FIELDS` (server/src/reports.ts): kiinteät valinnat, ei vapaata tekstiä. Palvelin hyväksyy lisäksi vanhojen
 * versioiden `price:*`-ilmoitukset, joita sovellus ei enää tee eikä lue.
 */
enum class ReportField(val key: String) {
    CLOSED("closed"),
    HOURS_STATION("hours:station"),
    HOURS_RESTAURANT("hours:restaurant"),
    HOURS_LUNCH("hours:lunch"),
    HOURS_SHOP("hours:shop"),
    FUELS("fuels"),
    ADBLUE("adblue"),
    CHARGING("charging"),
    SHOP("shop"),
    RESTAURANT("restaurant"),
    LUNCH("lunch"),
    TRUCK_PARKING("truck_parking"),
    SHOWER("shower"),
    TOILETS("toilets"),
    SAUNA("sauna"),
    OVERNIGHT("overnight"),
    TRUCK_LENGTH("truck_length"),
    MAX_HEIGHT("maxheight"),
    MAX_WEIGHT("maxweight");

    fun accepts(value: String): Boolean {
        if (value.length > 200) return false
        return when (this) {
            HOURS_STATION, HOURS_RESTAURANT, HOURS_LUNCH, HOURS_SHOP -> OpeningHoursText.isSimple(value)
            FUELS -> value == "none" || value.split(';').let { parts ->
                parts.all { FuelType.of(it) != null } && parts.toSet().size == parts.size
            }
            ADBLUE -> value in setOf("pump", "canister", "no")
            TRUCK_LENGTH -> value in setOf("14", "25.25", "34.5")
            MAX_HEIGHT, MAX_WEIGHT -> LIMIT.matches(value) && value.toDouble() in 0.5..99.0
            else -> value == "yes" || value == "no"
        }
    }

    companion object {
        private val LIMIT = Regex("""[0-9]{1,2}(\.[0-9]{1,2})?""")

        fun of(key: String): ReportField? = entries.firstOrNull { it.key == key }
    }
}
