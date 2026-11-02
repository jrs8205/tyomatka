package fi.tyomatka.core.plan

import java.security.MessageDigest
import kotlin.math.roundToLong

/** Pohjan ja päivän vertailussa käytettävät stopin kentät; tilat ja ajat eivät kuulu mukaan. */
data class TemplateStopData(
    val label: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val deadlineMinutesOfDay: Int?,
    val serviceSec: Long?,
)

/** Päivän listan ero avattuun pohjaan nähden. */
data class TemplateChange(val added: Int, val removed: Int, val edited: Int, val reordered: Boolean) {
    val isChanged: Boolean get() = added > 0 || removed > 0 || edited > 0 || reordered

    companion object {
        val NONE = TemplateChange(0, 0, 0, false)
    }
}

/**
 * Vertaa päivän asiakasstoppeja tallennettuun pohjaan.
 * Stopin tunniste on osoite ja koordinaatit mikroasteen tarkkuudella; samat tunnisteet paritetaan
 * esiintymiskertojen mukaan, jotta tuplaosoite lasketaan oikein.
 */
object TemplateDiff {
    fun compare(template: List<TemplateStopData>, day: List<TemplateStopData>): TemplateChange {
        val templateKeys = template.map(::key)
        val dayKeys = day.map(::key)
        val templateCounts = templateKeys.groupingBy { it }.eachCount()
        val dayCounts = dayKeys.groupingBy { it }.eachCount()
        val allKeys = templateCounts.keys + dayCounts.keys
        val added = allKeys.sumOf { maxOf(0, (dayCounts[it] ?: 0) - (templateCounts[it] ?: 0)) }
        val removed = allKeys.sumOf { maxOf(0, (templateCounts[it] ?: 0) - (dayCounts[it] ?: 0)) }

        // Parita päivän stoppi pohjan samaan tunnisteeseen esiintymiskertojen mukaan.
        val occurrences = mutableMapOf<String, Int>()
        val pairs = day.mapIndexedNotNull { dayIndex, stop ->
            val k = dayKeys[dayIndex]
            val n = occurrences.getOrDefault(k, 0)
            occurrences[k] = n + 1
            val templateIndex = templateKeys.withIndex().filter { it.value == k }.getOrNull(n)?.index
            templateIndex?.let { it to stop }
        }
        val edited = pairs.count { (templateIndex, stop) ->
            val t = template[templateIndex]
            t.label != stop.label || t.deadlineMinutesOfDay != stop.deadlineMinutesOfDay || t.serviceSec != stop.serviceSec
        }
        val matchedTemplateOrder = pairs.map { it.first }
        val reordered = matchedTemplateOrder.zipWithNext().any { (a, b) -> a > b }
        return TemplateChange(added, removed, edited, reordered)
    }

    /** Päivän listan avain Myöhemmin-kuittaukselle: sama lista samassa järjestyksessä antaa saman avaimen. */
    fun ackKey(day: List<TemplateStopData>): String {
        val text = day.joinToString("\u001E") { stop ->
            listOf(stop.label, stop.address, micro(stop.lat), micro(stop.lng), stop.deadlineMinutesOfDay, stop.serviceSec)
                .joinToString("\u001F")
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun key(stop: TemplateStopData): String = "${stop.address}|${micro(stop.lat)}|${micro(stop.lng)}"

    private fun micro(degrees: Double): Long = (degrees * 1_000_000).roundToLong()
}
