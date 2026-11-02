package fi.tyomatka.core.poi

/** Kohdekortin siru; [value] on lajikohtainen lisätieto (esim. liitin "Type 2|4", teho "150", raja "4.2"). */
enum class PoiChipKind {
    HGV_DIESEL, ADBLUE, TRUCK_PARKING, TOILETS, SHOWER, FOOD, SHOP, OPEN_24H, CHARGING_SOCKET, CHARGING_POWER, GAS_KIND,
    MAX_HEIGHT, MAX_WEIGHT, OVERNIGHT, CAPACITY,
}

data class PoiChip(val kind: PoiChipKind, val value: String? = null, val known: Boolean = true, val corrected: Boolean = false)

/** Kuljettajan korjattavat kentät; [key] on kannan ja varmuuskopion tunniste. */
enum class PoiCorrectionField(val key: String) {
    ADBLUE("adblue"), SHOWER("shower"), TOILETS("toilets"), FOOD("food"), TRUCK_PARKING("truck_parking"), OVERNIGHT("overnight"),
    MAX_HEIGHT("maxheight"), MAX_WEIGHT("maxweight");

    companion object {
        fun of(key: String): PoiCorrectionField? = entries.firstOrNull { it.key == key }
    }
}

/**
 * OSM-tagien tulkinta kortin siruiksi ja muiksi kentiksi. Avaimet ovat pieniä ([fi.tyomatka.core.api.PoiClient] ja putki
 * pienentävät `fuel:`- ja `socket:`-avaimet). Huoltoasemalle ja lepopaikalle puuttuvat korkeus- ja painoraja sekä yöpyminen
 * näytetään tuntemattomina ("ei tietoa"), muille luokille ei.
 */
object PoiServices {
    const val SERVICE_SEC = 900L
    const val CHARGING_SERVICE_SEC = 1_800L

    private val socketNames = mapOf(
        "type2" to "Type 2", "type2_combo" to "CCS", "chademo" to "CHAdeMO", "tesla_supercharger" to "Tesla", "schuko" to "Schuko",
        "type2_cable" to "Type 2", "type1" to "Type 1", "type1_combo" to "CCS1", "nacs" to "NACS",
    )
    private val gasKinds = listOf("fuel:lpg" to "LPG", "fuel:cng" to "CNG", "fuel:lng" to "LNG", "fuel:h2" to "H2")
    private val foodKeys = listOf("food", "restaurant", "fast_food", "cafe")
    private val powerPattern = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(kw)?\s*$""", RegexOption.IGNORE_CASE)

    fun displayName(item: PoiItem): String? = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: item.brand?.trim()?.takeIf { it.isNotEmpty() }

    fun defaultServiceSec(category: PoiCategory): Long = if (category == PoiCategory.CHARGING) CHARGING_SERVICE_SEC else SERVICE_SEC

    fun website(item: PoiItem): String? =
        listOf("website", "contact:website", "brand:website").firstNotNullOfOrNull { key ->
            item.tags[key]?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

    fun phone(item: PoiItem): String? = listOf("phone", "contact:phone").firstNotNullOfOrNull { item.tags[it]?.trim()?.takeIf(String::isNotEmpty) }

    fun openingHours(item: PoiItem): String? = item.tags["opening_hours"]?.trim()?.takeIf(String::isNotEmpty)

    fun chips(item: PoiItem, corrections: Map<PoiCorrectionField, String> = emptyMap()): List<PoiChip> {
        val tags = item.tags
        val chips = mutableListOf<PoiChip>()
        if (tags["fuel:hgv_diesel"] == "yes") chips += PoiChip(PoiChipKind.HGV_DIESEL)
        adblue(tags)?.let { chips += PoiChip(PoiChipKind.ADBLUE, it.takeIf { v -> v != "yes" }) }
        val truckCapacity = tags["capacity:hgv"]?.trim()?.takeIf(String::isNotEmpty)
        if (truckCapacity != null) {
            chips += PoiChip(PoiChipKind.TRUCK_PARKING, truckCapacity)
        } else if (item.category == PoiCategory.REST && tags["hgv"] in setOf("yes", "designated")) {
            chips += PoiChip(PoiChipKind.TRUCK_PARKING)
        }
        gasKinds.forEach { (key, label) -> if (tags[key] == "yes") chips += PoiChip(PoiChipKind.GAS_KIND, label) }
        chips += sockets(tags)
        power(tags)?.let { chips += PoiChip(PoiChipKind.CHARGING_POWER, it) }
        if (tags["toilets"] == "yes") chips += PoiChip(PoiChipKind.TOILETS)
        if (tags["shower"] == "yes") chips += PoiChip(PoiChipKind.SHOWER)
        if (foodKeys.any { tags[it] == "yes" }) chips += PoiChip(PoiChipKind.FOOD)
        tags["shop"]?.takeIf { it.isNotBlank() && it != "no" }?.let { chips += PoiChip(PoiChipKind.SHOP) }
        if (tags["opening_hours"]?.trim() == "24/7") chips += PoiChip(PoiChipKind.OPEN_24H)
        tags["capacity"]?.trim()?.takeIf(String::isNotEmpty)?.let { chips += PoiChip(PoiChipKind.CAPACITY, it) }
        val limitsMatter = item.category == PoiCategory.FUEL || item.category == PoiCategory.REST
        limit(tags["maxheight"], PoiChipKind.MAX_HEIGHT, limitsMatter)?.let { chips += it }
        limit(tags["maxweight"], PoiChipKind.MAX_WEIGHT, limitsMatter)?.let { chips += it }
        tags["overnight"]?.trim()?.takeIf { it == "yes" || it == "no" }?.let { chips += PoiChip(PoiChipKind.OVERNIGHT, it) }
            ?: run { if (limitsMatter) chips += PoiChip(PoiChipKind.OVERNIGHT, null, known = false) }
        return applyCorrections(chips, corrections)
    }

    private fun adblue(tags: Map<String, String>): String? {
        val value = tags["fuel:adblue"]?.trim()?.lowercase() ?: return null
        if (value == "no") return null
        return when {
            tags["fuel:adblue:pump"] == "yes" || value == "pump" -> "pump"
            tags["fuel:adblue:canister"] == "yes" || value == "canister" -> "canister"
            else -> "yes"
        }
    }

    private fun sockets(tags: Map<String, String>): List<PoiChip> =
        tags.entries
            .filter { (key, _) -> key.startsWith("socket:") && key.count { it == ':' } == 1 }
            .mapNotNull { (key, value) ->
                val count = value.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val type = key.removePrefix("socket:")
                PoiChip(PoiChipKind.CHARGING_SOCKET, "${socketNames[type] ?: type}|$count")
            }
            .sortedBy { it.value }

    private fun power(tags: Map<String, String>): String? {
        val values = tags.entries
            .filter { (key, _) -> key == "charging_station:output" || (key.startsWith("socket:") && key.endsWith(":output")) }
            .mapNotNull { (_, value) -> powerPattern.find(value)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        val max = values.maxOrNull() ?: return null
        return if (max == Math.floor(max)) max.toLong().toString() else max.toString()
    }

    private fun limit(value: String?, kind: PoiChipKind, matters: Boolean): PoiChip? {
        val text = value?.trim()?.takeIf(String::isNotEmpty)
        return when {
            text != null -> PoiChip(kind, text)
            matters -> PoiChip(kind, null, known = false)
            else -> null
        }
    }

    private fun applyCorrections(chips: MutableList<PoiChip>, corrections: Map<PoiCorrectionField, String>): List<PoiChip> {
        if (corrections.isEmpty()) return chips
        val result = chips.toMutableList()
        fun replace(kind: PoiChipKind, chip: PoiChip?) {
            val at = result.indexOfFirst { it.kind == kind }
            when {
                chip == null -> if (at >= 0) result.removeAt(at)
                at >= 0 -> result[at] = chip
                else -> result += chip
            }
        }
        corrections.forEach { (field, value) ->
            val kind = when (field) {
                PoiCorrectionField.ADBLUE -> PoiChipKind.ADBLUE
                PoiCorrectionField.SHOWER -> PoiChipKind.SHOWER
                PoiCorrectionField.TOILETS -> PoiChipKind.TOILETS
                PoiCorrectionField.FOOD -> PoiChipKind.FOOD
                PoiCorrectionField.TRUCK_PARKING -> PoiChipKind.TRUCK_PARKING
                PoiCorrectionField.OVERNIGHT -> PoiChipKind.OVERNIGHT
                PoiCorrectionField.MAX_HEIGHT -> PoiChipKind.MAX_HEIGHT
                PoiCorrectionField.MAX_WEIGHT -> PoiChipKind.MAX_WEIGHT
            }
            when (field) {
                PoiCorrectionField.OVERNIGHT -> replace(kind, PoiChip(kind, value, corrected = true))
                PoiCorrectionField.MAX_HEIGHT, PoiCorrectionField.MAX_WEIGHT -> replace(kind, PoiChip(kind, value, corrected = true))
                else -> replace(kind, if (value == "yes") PoiChip(kind, null, corrected = true) else null)
            }
        }
        return result.sortedBy { it.kind.ordinal.let { ordinal -> ORDER.indexOf(it.kind).takeIf { i -> i >= 0 } ?: ordinal } }
    }

    /** Sirujen näyttöjärjestys. */
    private val ORDER = listOf(
        PoiChipKind.HGV_DIESEL, PoiChipKind.ADBLUE, PoiChipKind.TRUCK_PARKING, PoiChipKind.GAS_KIND, PoiChipKind.CHARGING_SOCKET,
        PoiChipKind.CHARGING_POWER, PoiChipKind.TOILETS, PoiChipKind.SHOWER, PoiChipKind.FOOD, PoiChipKind.SHOP, PoiChipKind.OPEN_24H,
        PoiChipKind.CAPACITY, PoiChipKind.MAX_HEIGHT, PoiChipKind.MAX_WEIGHT, PoiChipKind.OVERNIGHT,
    )
}
