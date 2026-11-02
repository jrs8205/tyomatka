package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Raportin kieli: suomi ja ruotsi sellaisenaan, muut kielet englanniksi. */
enum class ReportLanguage(val tag: String) { FI("fi"), SV("sv"), EN("en") }

/**
 * Raportin kieli ja numeromuodot. [finnishNumerals]: päivät ja kellonajat suomalaisittain ("16.10.2026", "7.01"),
 * kuten suomeksi ja Suomen ruotsiksi.
 */
data class ReportLocale(val language: ReportLanguage, val finnishNumerals: Boolean)

/** Sovelluksen kielestä raportin kieli: suomi, ruotsi (Suomessa suomalaisin numeroin) tai muuten englanti. */
fun reportLocale(locale: Locale): ReportLocale = when (locale.language) {
    "fi" -> ReportLocale(ReportLanguage.FI, finnishNumerals = true)
    "sv" -> ReportLocale(ReportLanguage.SV, finnishNumerals = locale.country == "FI")
    else -> ReportLocale(ReportLanguage.EN, finnishNumerals = false)
}

/** Sitova välilyönti: luku ja yksikkö, päivämäärä ja kesto eivät katkea rivinvaihdossa. */
internal const val NB = '\u00A0'

/** Kokonaisluku kielen tuhaterottimin: suomeksi ja ruotsiksi sitova väli 10 000:sta alkaen, englanniksi pilkku. */
internal fun wholeNumber(value: Long, language: ReportLanguage): String {
    val text = groupDigits(abs(value).toString(), language)
    return if (value < 0) "-$text" else text
}

private fun groupDigits(digits: String, language: ReportLanguage): String {
    val english = language == ReportLanguage.EN
    if (digits.length < if (english) 4 else 5) return digits
    return digits.reversed().chunked(3).joinToString(if (english) "," else NB.toString()).reversed()
}

/**
 * Raportin muotoilut kielen mukaan. Jokainen tulos on sitova lohko: luvun ja yksikön, päivämäärän ja keston välit
 * ovat sitovia (U+00A0), joten rivi katkeaa vain erottimien ja ajatusviivan jälkeisen välin kohdalta. Nimet tulevat
 * kiinteistä taulukoista, jotta tulos ei riipu JVM:n tai Androidin versiosta.
 */
class ReportFormat(val locale: ReportLocale, val zone: ZoneId) {
    private val language = locale.language
    private val numeric = locale.finnishNumerals || language == ReportLanguage.FI

    /** "Pe 16.10.2026", "Fre 16 okt. 2026", "Fri 16 Oct 2026"; lauseen sisällä [capitalize] = false: "pe 16.10.2026". */
    fun date(epochDay: Long, capitalize: Boolean = true): String {
        val weekday = WEEKDAYS.getValue(language)[LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1]
        val name = if (capitalize) weekday.replaceFirstChar { it.uppercaseChar() } else weekday
        return "$name$NB${shortDate(epochDay)}"
    }

    /** Päivä ilman viikonpäivää: "16.10.2026", "16 okt. 2026", "16 Oct 2026". */
    fun shortDate(epochDay: Long): String {
        val day = LocalDate.ofEpochDay(epochDay)
        return if (numeric) "${day.dayOfMonth}.${day.monthValue}.${day.year}" else "${dayAndMonth(day)}$NB${day.year}"
    }

    /** "klo 7.01–14.20", "kl. 07:01–14:20", "07:01–14:20". */
    fun clockRange(startSec: Long, endSec: Long): String = "${CLOCK_PREFIX.getValue(language)}${clock(startSec)}–${clock(endSec)}"

    /** Kesken olevan päivän alku: "klo 7.01–". */
    fun clockFrom(startSec: Long): String = "${CLOCK_PREFIX.getValue(language)}${clock(startSec)}–"

    /** Kellonaika ilman etuliitettä: "9.40" tai "09:40". */
    fun clock(epochSec: Long): String {
        val time = Instant.ofEpochSecond(epochSec).atZone(zone)
        val minute = time.minute.toString().padStart(2, '0')
        return if (numeric) "${time.hour}.$minute" else "${time.hour.toString().padStart(2, '0')}:$minute"
    }

    /** "7 h 19 min", "35 min"; alle minuutti "alle 1 min". */
    fun duration(totalSec: Long): String {
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        return when {
            hours > 0 -> "${whole(hours)}${NB}h$NB$minutes${NB}min"
            minutes > 0 -> "$minutes${NB}min"
            else -> UNDER_A_MINUTE.getValue(language)
        }
    }

    /** Suomeksi ja ruotsiksi tuhaterotin sitovana välinä 10 000:sta alkaen ("1234", "10 000"), englanniksi pilkku. */
    fun whole(value: Long): String = wholeNumber(value, language)

    /** Desimaaliluku kielen erottimin ja samalla tuhaterottimella kuin [whole]: "14,1", "10 234,50", "1,534.13". */
    fun decimal(value: Double, decimals: Int): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", abs(value))
        val fraction = text.substringAfter('.', "")
        val integer = groupDigits(text.substringBefore('.'), language)
        val body = integer + if (fraction.isEmpty()) "" else DECIMAL_SEPARATOR.getValue(language) + fraction
        return if (value < 0 && text.any { it in '1'..'9' }) "-$body" else body
    }

    fun km(value: Long): String = "${whole(value)}$NB$DISTANCE_UNIT"

    /** Mittarialue aina välein: "475 429 – 475 658 km". Sitova väli ennen viivaa, joten rivi katkeaa vain sen jälkeen. */
    fun odometerRange(fromKm: Long, toKm: Long): String = "${whole(fromKm)}$NB– ${km(toKm)}"

    /**
     * Päiväväli Kielitoimiston ohjeen mukaan: ilman välejä, kun päissä ei ole välilyöntejä ("1.9.–18.10.2026",
     * "12–16 Oct 2026"), muuten välein ("1 sep. – 18 okt. 2026").
     */
    fun dayRange(fromDay: Long, toDay: Long): String {
        val from = LocalDate.ofEpochDay(minOf(fromDay, toDay))
        val to = LocalDate.ofEpochDay(maxOf(fromDay, toDay))
        val end = shortDate(to.toEpochDay())
        if (from == to) return end
        val sameYear = from.year == to.year
        val sameMonth = sameYear && from.monthValue == to.monthValue
        return if (numeric) {
            val start = when {
                sameMonth -> "${from.dayOfMonth}."
                sameYear -> "${from.dayOfMonth}.${from.monthValue}."
                else -> shortDate(from.toEpochDay())
            }
            "$start–$end"
        } else when {
            sameMonth -> "${from.dayOfMonth}–$end"
            sameYear -> "${dayAndMonth(from)}$NB– $end"
            else -> "${shortDate(from.toEpochDay())}$NB– $end"
        }
    }

    /** "Viikko 42 · 12.–16.10.2026", "Week 42 · 12–16 Oct 2026". */
    fun weekTitle(isoWeek: Int, fromDay: Long, toDay: Long): String =
        "${WEEK.getValue(language)}$NB$isoWeek · ${dayRange(fromDay, toDay)}"

    /** "Lokakuu 2026", "Oktober 2026", "October 2026". */
    fun monthTitle(year: Int, month: Int): String =
        "${MONTHS.getValue(language)[month - 1].replaceFirstChar { it.uppercaseChar() }}$NB$year"

    /** Rahamäärä valuuttoineen: "75,45 €", "€75.45", "SEK 512.00"; 0 = hintaa ei tiedetä → "–". */
    fun money(amount: Double, currency: String): String = if (amount > 0) moneyText(amount, currency, 2) else "–"

    /** Valuutoittaiset summat ilman muunnosta: "45,20 € + 512,00 SEK"; tyhjä → "–". */
    fun moneyByCurrency(costs: Map<String, Double>): String =
        costs.filterValues { it > 0 }.entries.joinToString(" + ") { money(it.value, it.key) }.ifEmpty { "–" }

    /** Määrä lajin yksikössä: "31,2 l", "16,9 kg", "45.0 kWh". */
    fun amount(amount: Double, kind: FuelKind): String = "${decimal(amount, 1)}$NB${unitSymbol(kind.unit)}"

    /** Yksikköhinta: "2,416 €/l", "€0.320/kWh"; 0 → "–". */
    fun unitPrice(price: Double, currency: String, kind: FuelKind): String =
        if (price > 0) "${moneyText(price, currency, 3)}/${unitSymbol(kind.unit)}" else "–"

    /** Kulutus lajin yksikössä: "14,1 l/100 km", AdBlue "2,1 l/1000 km". */
    fun consumption(value: Double, kind: FuelKind): String = "${decimal(value, 1)}$NB${consumptionUnit(kind)}"

    /** Hinta kilometriltä: "0,34 €/km", "€0.34/km"; 0 → "–". */
    fun costPerKm(value: Double, currency: String): String =
        if (value > 0) "${moneyText(value, currency, 2)}/$DISTANCE_UNIT" else "–"

    private fun moneyText(amount: Double, currency: String, decimals: Int): String {
        val number = decimal(amount, decimals)
        return when {
            language != ReportLanguage.EN -> "$number$NB${currencySymbol(currency)}"
            currency == "EUR" -> "€$number"
            else -> "$currency$NB$number"
        }
    }

    private fun dayAndMonth(day: LocalDate): String =
        "${day.dayOfMonth}$NB${SHORT_MONTHS.getValue(language)[day.monthValue - 1]}"

    // Raportin ainoa yksikkömuotoilija: vaiheen 5 mailit ja gallonat lisätään näihin.
    private fun unitSymbol(unit: FuelUnit): String = when (unit) {
        FuelUnit.LITER -> "l"
        FuelUnit.KILOGRAM -> "kg"
        FuelUnit.KILOWATT_HOUR -> "kWh"
    }

    private fun consumptionUnit(kind: FuelKind): String = "${unitSymbol(kind.unit)}/${kind.perKm}$NB$DISTANCE_UNIT"

    private fun currencySymbol(currency: String): String = if (currency == "EUR") "€" else currency

    private companion object {
        const val DISTANCE_UNIT = "km"

        val WEEKDAYS = mapOf(
            ReportLanguage.FI to listOf("ma", "ti", "ke", "to", "pe", "la", "su"),
            ReportLanguage.SV to listOf("mån", "tis", "ons", "tors", "fre", "lör", "sön"),
            ReportLanguage.EN to listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        )
        val SHORT_MONTHS = mapOf(
            ReportLanguage.SV to listOf("jan.", "feb.", "mars", "apr.", "maj", "juni", "juli", "aug.", "sep.", "okt.", "nov.", "dec."),
            ReportLanguage.EN to listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
        )
        val MONTHS = mapOf(
            ReportLanguage.FI to listOf(
                "tammikuu", "helmikuu", "maaliskuu", "huhtikuu", "toukokuu", "kesäkuu",
                "heinäkuu", "elokuu", "syyskuu", "lokakuu", "marraskuu", "joulukuu",
            ),
            ReportLanguage.SV to listOf(
                "januari", "februari", "mars", "april", "maj", "juni",
                "juli", "augusti", "september", "oktober", "november", "december",
            ),
            ReportLanguage.EN to listOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December",
            ),
        )
        val WEEK = mapOf(ReportLanguage.FI to "Viikko", ReportLanguage.SV to "Vecka", ReportLanguage.EN to "Week")
        val CLOCK_PREFIX = mapOf(ReportLanguage.FI to "klo$NB", ReportLanguage.SV to "kl.$NB", ReportLanguage.EN to "")
        val UNDER_A_MINUTE = mapOf(
            ReportLanguage.FI to "alle${NB}1${NB}min",
            ReportLanguage.SV to "under${NB}1${NB}min",
            ReportLanguage.EN to "under${NB}1${NB}min",
        )
        val DECIMAL_SEPARATOR = mapOf(ReportLanguage.FI to ",", ReportLanguage.SV to ",", ReportLanguage.EN to ".")
    }
}
