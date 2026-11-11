package fi.tyomatka.ui.history

import fi.tyomatka.data.repo.PeriodFuel
import fi.tyomatka.data.repo.PeriodFuels
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

/**
 * Nuolirivin ja pagerin valinta: rivien avaimet uusimmasta vanhimpaan ja valittu avain. Pagerin sivu on rivin indeksi.
 * Ilman valintaa (tai tuntemattomalla avaimella) valittuna on uusin.
 */
data class PeriodChoice(val keys: List<String>, private val selectedKey: String? = null) {
    val selected: String? get() = selectedKey?.takeIf { it in keys } ?: keys.firstOrNull()
    val page: Int get() = keys.indexOf(selected).coerceAtLeast(0)
    val older: String? get() = olderKey(keys, selected)
    val newer: String? get() = newerKey(keys, selected)

    /** Nuoli tai muiden jaksojen rivi; tuntematon avain ei muuta valintaa. */
    fun select(key: String): PeriodChoice = if (key in keys) copy(selectedKey = key) else this

    /** Pyyhkäisy asettui sivulle. */
    fun settled(page: Int): PeriodChoice = keys.getOrNull(page)?.let(::select) ?: this

    /** Rivit muuttuivat: [followSelection]. */
    fun rowsChanged(newKeys: List<String>): PeriodChoice =
        if (newKeys == keys) this else PeriodChoice(newKeys, followSelection(keys, newKeys, selected))
}

/** Muut jaksot: kaikki rivit valittua lukuun ottamatta, uusimmasta vanhimpaan. */
fun otherPeriodRows(rows: List<WorkPeriodRow>, selectedKey: String?): List<WorkPeriodRow> =
    rows.filter { it.key != selectedKey }

/** Muiden jaksojen rivin polttoaineet: lajit ilman AdBluea, tai AdBlue, jos muuta ei tankattu. */
fun listFuelLines(fuel: PeriodFuels?): List<PeriodFuel> {
    val tiles = periodFuelTiles(fuel)
    return tiles.main.ifEmpty { listOfNotNull(tiles.adBlue) }
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
