package fi.tyomatka.core.poi

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Aukiolon tila hetkellä ([OpeningHoursText.openState]). */
sealed interface OpenState {
    /** Ei sulje seuraavan 7 vuorokauden aikana. */
    data object AllDay : OpenState

    /** Auki [time] asti; keskiyö on [LocalTime.MIDNIGHT] ja [day] silloin edellinen päivä ("pe klo 24"). [day] null = alle 24 h päästä. */
    data class OpenUntil(val time: LocalTime, val day: DayOfWeek?) : OpenState

    /** Kiinni, aukeaa [time]; [day] null = alle 24 h päästä. */
    data class ClosedUntil(val time: LocalTime, val day: DayOfWeek?) : OpenState
}

/**
 * Aukiolon muoto `24/7` tai `Mo-Fr 06:30-19:00; Sa 08:00-16:00` (putki, palvelin ja ilmoitukset). [format] näyttää sen
 * sovelluksen kielellä; muu OSM-aukiolo (esim. `PH off`, `sunrise-sunset`) näytetään sellaisenaan, jotta mitään ei katoa.
 */
object OpeningHoursText {
    /** Päivien lyhenteet maanantaista alkaen, 24 h -teksti ja usean välin sidesana sovelluksen kielellä. */
    data class Labels(val days: List<String>, val allDay: String, val and: String)

    val FI = Labels(days = listOf("ma", "ti", "ke", "to", "pe", "la", "su"), allDay = "Auki 24 h", and = "ja")

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private const val DAY = "(Mo|Tu|We|Th|Fr|Sa|Su)"
    private const val TIME = "(?:[01][0-9]|2[0-3]):[0-5][0-9]|24:00"
    private val simpleEntry = Regex("""$DAY(?:-$DAY)? (?:$TIME)-(?:$TIME)""")
    private val entry = Regex("""$DAY(?:-$DAY)? ((?:(?:$TIME)-(?:$TIME))(?:,(?:$TIME)-(?:$TIME))*)""")
    private val span = Regex("""($TIME)-($TIME)""")

    /** Tuottaako ilmoituksen aukiolovalinta tämän muodon (sama sääntö kuin palvelimella). */
    fun isSimple(osm: String): Boolean =
        osm.length <= 200 && (osm == "24/7" || osm.split("; ").all { simpleEntry.matches(it) })

    fun format(osm: String, labels: Labels = FI): String {
        val text = osm.trim()
        if (text == "24/7") return labels.allDay
        val parts = text.split("; ").map { entry.matchEntire(it) ?: return osm }
        val days = parts.flatMap { m -> dayRange(m.groupValues[1], m.groupValues[2]).map { it to m.groupValues[3] } }
        if (days.map { it.first }.toSet().size == 7 && days.all { it.second == "00:00-24:00" }) return labels.allDay
        return parts.joinToString(", ") { m ->
            val first = DAYS.indexOf(m.groupValues[1])
            val last = m.groupValues[2].takeIf(String::isNotEmpty)?.let(DAYS::indexOf)
            val dayText = if (last == null) labels.days[first] else "${labels.days[first]}–${labels.days[last]}"
            val spans = span.findAll(m.groupValues[3]).joinToString(" ${labels.and} ") { "${clock(it.groupValues[1])}–${clock(it.groupValues[2])}" }
            "$dayText $spans"
        }
    }

    private const val DAY_MIN = 24 * 60
    private const val WEEK_MIN = 7 * DAY_MIN

    /**
     * Aukiolon tila hetkellä [at] samasta kieliopista kuin [format]; null, kun muoto ei ole yksinkertainen. Välit ovat
     * viikon minuutteina: loppu ≤ alku jatkuu seuraavaan päivään, myöhempi sääntö korvaa päivän aiemman (kuten OSM:ssä),
     * ja peräkkäiset välit yhdistetään (24:00 ja seuraavan päivän 00:00 ovat sama hetki), joten sulkemisaika on todellinen.
     * Yön yli jatkuva väli, jonka päivän tai seuraavan päivän myöhempi sääntö korvaa, on null: OSM nollaa silloin myös
     * yön yli jatkuvan osan (`Sa 16:00-03:00; Su 16:00-23:00` on su aamuyöllä kiinni), ja väärä "auki" olisi pahin virhe.
     */
    fun openState(osm: String, at: LocalDateTime): OpenState? {
        val text = osm.trim()
        if (text == "24/7") return OpenState.AllDay
        val parsed = text.split("; ").map { part ->
            val m = entry.matchEntire(part) ?: return null
            val spans = span.findAll(m.groupValues[3]).map { minutes(it.groupValues[1]) to minutes(it.groupValues[2]) }.toList()
            // 24:00 on vain loppuaika.
            if (spans.any { (start, _) -> start >= DAY_MIN }) return null
            dayRange(m.groupValues[1], m.groupValues[2]) to spans
        }
        parsed.forEachIndexed { i, (days, spans) ->
            if (spans.any { (start, end) -> end in 1..start }) {
                val touched = days.flatMap { listOf(it, (it + 1) % 7) }.toSet()
                if (parsed.drop(i + 1).any { (later, _) -> later.any(touched::contains) }) return null
            }
        }
        val rules = arrayOfNulls<List<Pair<Int, Int>>>(7)
        parsed.forEach { (days, spans) -> days.forEach { rules[it] = spans } }
        val week = rules.withIndex().flatMap { (day, spans) ->
            spans.orEmpty().map { (start, end) -> day * DAY_MIN + start to day * DAY_MIN + if (end <= start) end + DAY_MIN else end }
        }
        // Kolme viikkoa peräkkäin: viikon vaihteen yli jatkuva väli ja seuraavan viikon avaus löytyvät.
        val merged = merge((-1..1).flatMap { w -> week.map { (start, end) -> start + w * WEEK_MIN to end + w * WEEK_MIN } })
        val now = at.dayOfWeek.ordinal * DAY_MIN + at.hour * 60 + at.minute
        val open = merged.firstOrNull { (start, end) -> now >= start && now < end }
        if (open != null) {
            val end = open.second
            if (end - now >= WEEK_MIN) return OpenState.AllDay
            // Keskiyön sulkeminen on edellisen päivän loppu: lauantain klo 0 on "pe klo 24".
            val dayEnd = if (Math.floorMod(end, DAY_MIN) == 0) end - 1 else end
            return OpenState.OpenUntil(timeOf(end), dayOf(dayEnd).takeIf { end - now >= DAY_MIN })
        }
        val next = merged.firstOrNull { it.first > now } ?: return null
        return OpenState.ClosedUntil(timeOf(next.first), dayOf(next.first).takeIf { next.first - now >= DAY_MIN })
    }

    /** Sulkemisaika lyhyesti: 21:00 → "21", 6:30 → "6.30", keskiyö → "24" (päivän loppu). */
    fun closesShort(time: LocalTime): String = if (time == LocalTime.MIDNIGHT) "24" else short(time)

    /** Avausaika lyhyesti: 6:00 → "6", 6:30 → "6.30", keskiyö → "0" (päivän alku). */
    fun opensShort(time: LocalTime): String = short(time)

    private fun short(time: LocalTime): String =
        if (time.minute == 0) "${time.hour}" else String.format(Locale.ROOT, "%d.%02d", time.hour, time.minute)

    private fun minutes(time: String): Int = time.substringBefore(':').toInt() * 60 + time.substringAfter(':').toInt()

    private fun timeOf(minute: Int): LocalTime = Math.floorMod(minute, DAY_MIN).let { LocalTime.of(it / 60, it % 60) }

    private fun dayOf(minute: Int): DayOfWeek = DayOfWeek.of(Math.floorMod(Math.floorDiv(minute, DAY_MIN), 7) + 1)

    private fun merge(spans: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
        spans.sortedBy { it.first }.fold(mutableListOf()) { out, (start, end) ->
            val last = out.lastOrNull()
            if (last != null && start <= last.second) out[out.lastIndex] = last.first to maxOf(last.second, end) else out += start to end
            out
        }

    private fun dayRange(first: String, last: String): List<Int> {
        val from = DAYS.indexOf(first)
        val to = if (last.isEmpty()) from else DAYS.indexOf(last)
        return generateSequence(from) { if (it == to) null else (it + 1) % 7 }.toList()
    }

    /** "06:30" → "6.30". */
    private fun clock(time: String): String = "${time.substringBefore(':').toInt()}.${time.substringAfter(':')}"
}
