package fi.tyomatka.ui.poi

/** Yhden päiväryhmän aukiolo ilmoituksen aukiolovalinnassa; [Range]-ajat kirjoitetaan vapaasti ("6", "6.30", "2115"). */
sealed interface DayHours {
    data object Closed : DayHours
    data object AllDay : DayHours
    data class Range(val open: String, val close: String) : DayHours
}

/**
 * Aukiolo arkipäiville, lauantaille ja sunnuntaille. [toOsm] tuottaa palvelimen aukiolomuodon (`24/7` tai
 * `Mo-Fr 06:00-18:00; Sa 08:00-16:00`); tyhjä merkkijono, kun aika on kesken tai kaikki päivät suljettu.
 */
data class HoursForm(val weekdays: DayHours, val saturday: DayHours, val sunday: DayHours) {
    fun toOsm(): String {
        val groups = listOf(0 to 4, 5 to 5, 6 to 6).zip(listOf(weekdays, saturday, sunday))
        val spans = groups.map { (days, hours) -> days to (span(hours) ?: return "") }
        if (spans.all { it.second == ALL_DAY }) return "24/7"
        val merged = mutableListOf<Pair<Pair<Int, Int>, String>>()
        spans.forEach { (days, span) ->
            val last = merged.lastOrNull()
            if (last != null && last.second == span && last.first.second + 1 == days.first) {
                merged[merged.lastIndex] = (last.first.first to days.second) to span
            } else {
                merged += days to span
            }
        }
        return merged.filter { it.second != CLOSED }.joinToString("; ") { (days, span) ->
            val (first, last) = days
            (if (first == last) DAYS[first] else "${DAYS[first]}-${DAYS[last]}") + " " + span
        }
    }

    /** Ryhmän väli; [CLOSED] suljetulle, null keskeneräiselle ajalle. */
    private fun span(hours: DayHours): String? = when (hours) {
        DayHours.Closed -> CLOSED
        DayHours.AllDay -> ALL_DAY
        is DayHours.Range -> {
            val open = time(hours.open)
            val close = time(hours.close)
            if (open == null || close == null || open == "24:00" || open == close) null else "$open-$close"
        }
    }

    companion object {
        /** Tuntematon aukiolo alkaa tyhjänä: mitään ei lähetetä keksittynä, ennen kuin ajat on kirjoitettu. */
        val EMPTY = HoursForm(DayHours.Range("", ""), DayHours.Range("", ""), DayHours.Range("", ""))

        private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        private const val CLOSED = "-"
        private const val ALL_DAY = "00:00-24:00"
        private val ENTRY = Regex("""(Mo|Tu|We|Th|Fr|Sa|Su)(?:-(Mo|Tu|We|Th|Fr|Sa|Su))? (\d{2}:\d{2})-(\d{2}:\d{2})""")

        /** "6" → "06:00", "6.30", "6,30" ja "6:30" → "06:30", "2115" → "21:15"; muu → null. */
        fun time(text: String): String? {
            val t = text.trim()
            val match = Regex("""(\d{1,2})(?:[.:,](\d{2}))?""").matchEntire(t)
                ?: Regex("""(\d{1,2})(\d{2})""").matchEntire(t)
                ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour > 24 || minute > 59 || (hour == 24 && minute != 0)) return null
            return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)
        }

        /** Nykyinen aukiolo lomakkeen pohjaksi, kun se mahtuu arki/la/su-muotoon; muuten null. */
        fun from(osm: String): HoursForm? {
            if (osm.trim() == "24/7") return HoursForm(DayHours.AllDay, DayHours.AllDay, DayHours.AllDay)
            val week = arrayOfNulls<DayHours>(7)
            for (part in osm.split("; ")) {
                val m = ENTRY.matchEntire(part.trim()) ?: return null
                val first = DAYS.indexOf(m.groupValues[1])
                val last = m.groupValues[2].takeIf(String::isNotEmpty)?.let(DAYS::indexOf) ?: first
                if (last < first) return null
                val hours = if (m.groupValues[3] == "00:00" && m.groupValues[4] == "24:00") DayHours.AllDay else DayHours.Range(m.groupValues[3], m.groupValues[4])
                for (day in first..last) week[day] = hours
            }
            val weekday = week[0] ?: DayHours.Closed
            if ((0..4).any { (week[it] ?: DayHours.Closed) != weekday }) return null
            return HoursForm(weekday, week[5] ?: DayHours.Closed, week[6] ?: DayHours.Closed)
        }
    }
}
