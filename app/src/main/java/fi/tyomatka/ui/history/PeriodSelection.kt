package fi.tyomatka.ui.history

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields

/*
 * Työmatka-sivun valittu jakso. Rivit ovat uusimmasta vanhimpaan (workPeriodRows), ja valinta on rivin avain;
 * null tarkoittaa uusinta.
 */

/**
 * Valinta rivien muuttuessa: jos uusin oli valittuna (tai ei mitään), valinta seuraa uutta uusinta; muuten se pysyy
 * samassa avaimessa, ja jos se katoaa, siirtyy uusimpaan.
 */
fun followSelection(previousKeys: List<String>, newKeys: List<String>, selectedKey: String?): String? {
    val followsNewest = selectedKey == previousKeys.firstOrNull()
    return if (!followsNewest && selectedKey in newKeys) selectedKey else newKeys.firstOrNull()
}

/** Valittua vanhempi jakso tai null, jos valittu on vanhin tai tuntematon. */
fun olderKey(keys: List<String>, selectedKey: String?): String? {
    val index = indexOf(keys, selectedKey) ?: return null
    return keys.getOrNull(index + 1)
}

/** Valittua uudempi jakso tai null, jos valittu on uusin tai tuntematon. */
fun newerKey(keys: List<String>, selectedKey: String?): String? {
    val index = indexOf(keys, selectedKey) ?: return null
    return keys.getOrNull(index - 1)
}

private fun indexOf(keys: List<String>, selectedKey: String?): Int? {
    if (selectedKey == null) return 0
    return keys.indexOf(selectedKey).takeIf { it >= 0 }
}

/**
 * Jakson päivät rivin avaimesta ([workPeriodKey]): päivä, ISO-viikko maanantaista sunnuntaihin, kalenterikuukausi tai
 * -vuosi epochDay-väleinä. Vain sovelluksen oma avainmuoto kelpaa: väli tarkistetaan muodostamalla avain uudelleen sen
 * ensimmäisestä päivästä. Muu tai virheellinen avain antaa null.
 */
fun periodDayRange(key: String): LongRange? {
    if (key.length < 2) return null
    val body = key.substring(1)
    val period = when (key[0]) {
        'd' -> WorkPeriod.DAY
        'w' -> WorkPeriod.WEEK
        'm' -> WorkPeriod.MONTH
        'y' -> WorkPeriod.YEAR
        else -> return null
    }
    return runCatching {
        val range = when (period) {
            WorkPeriod.DAY -> body.toLong().let { it..it }
            WorkPeriod.WEEK -> {
                val (year, week) = body.split('-').let { it[0].toInt() to it[1].toInt() }
                val monday = LocalDate.of(year, 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                    .with(WeekFields.ISO.dayOfWeek(), 1)
                monday.toEpochDay()..monday.plusDays(6).toEpochDay()
            }
            WorkPeriod.MONTH -> YearMonth.parse(body).let { it.atDay(1).toEpochDay()..it.atEndOfMonth().toEpochDay() }
            WorkPeriod.YEAR -> body.toInt().let { LocalDate.of(it, 1, 1).toEpochDay()..LocalDate.of(it, 12, 31).toEpochDay() }
        }
        range.takeIf { workPeriodKey(period, LocalDate.ofEpochDay(it.first)) == key }
    }.getOrNull()
}
