package fi.tyomatka.ui

import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelUnit
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Epoch-sekunnit kellonajaksi, esim. "14.35", englanniksi "14:35". */
fun formatClock(epochSec: Long): String =
    DateTimeFormatter.ofPattern(if (isEnglish()) "HH:mm" else "HH.mm").format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/** Minuutit keskiyöstä kellonajaksi, esim. 900 → "15.00", englanniksi "15:00". */
fun formatMinutesOfDay(minutes: Int): String =
    String.format(Locale.ROOT, if (isEnglish()) "%d:%02d" else "%d.%02d", minutes / 60, minutes % 60)

/** Kesto sekunteina muotoon "2 h 15 min" / "35 min". */
fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val min = (totalSec % 3600) / 60
    return when {
        h > 0 -> "$h h $min min"
        min > 0 -> "$min min"
        else -> if (appLocale().language == "fi") "alle 1 min" else "under 1 min"
    }
}

/**
 * Kellonaikateksti minuuteiksi keskiyöstä, tai null jos ei kelpaa.
 * Hyväksyy "9.45", "09.45", "9:45" ja erotinta vailla olevan "945"/"0945".
 */
fun parseClockMinutes(text: String): Int? {
    val cleaned = text.trim().replace(':', '.').replace(',', '.')
    val (hourText, minuteText) = when {
        cleaned.count { it == '.' } == 1 -> {
            val parts = cleaned.split('.')
            parts[0] to parts[1]
        }
        cleaned.none { it == '.' } && cleaned.length in 3..4 ->
            cleaned.dropLast(2) to cleaned.takeLast(2)
        else -> return null
    }
    if (minuteText.length != 2) return null
    val hour = hourText.toIntOrNull() ?: return null
    val minute = minuteText.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** Metrit muotoon "850 m" / "12,4 km". */
fun formatKm(meters: Long): String = when {
    meters < 950 -> "$meters m"
    else -> String.format(appLocale(), "%.1f km", meters / 1000.0)
}

/** Desimaaliluku näytön kielellä, esim. 7.84 → "7,8", englanniksi "7.8". */
fun formatDecimal(value: Double, decimals: Int): String =
    String.format(appLocale(), "%.${decimals}f", value)

/**
 * Kokonaisluku kuten raportissa. Suomeksi ja ruotsiksi nelinumeroinen ilman väliä ("1100"), viidestä
 * numerosta alkaen tuhaterotin sitovalla välilyönnillä ("10 000", "475 279"), jottei luku katkea rivinvaihdossa.
 * Englanniksi pilkku neljästä numerosta alkaen ("1,100").
 */
fun formatWhole(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val english = isEnglish()
    val text = if (digits.length < if (english) 4 else 5) {
        digits
    } else {
        digits.reversed().chunked(3).joinToString(if (english) "," else "\u00A0").reversed()
    }
    return if (value < 0) "-$text" else text
}

/**
 * Päivämäärä muotoon "Pe 25.9.2026" (viikonpäivä isolla alkukirjaimella otsikoissa ja kentissä).
 * Lauseen sisällä [capitalize] = false: "(pe 25.9.2026 lopetus)". Englanniksi "Fri 25 Sep 2026".
 */
fun formatShortDate(epochDay: Long, capitalize: Boolean = true): String {
    val date = LocalDate.ofEpochDay(epochDay)
    if (isEnglish()) return DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).format(date)
    val text = DateTimeFormatter.ofPattern("EEE d.M.yyyy", appLocale()).format(date)
    return if (capitalize) text.replaceFirstChar { it.uppercase(appLocale()) } else text
}

/** Yksittäinen päivä: "25.9.2026", englanniksi "25 Sep 2026". */
fun formatDate(date: LocalDate): String =
    if (isEnglish()) DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(date) else "${date.dayOfMonth}.${date.monthValue}.${date.year}"

/** Kuukausi ja vuosi: "lokakuu 2026" lauseen sisällä, [capitalize] otsikoissa. */
fun formatMonth(month: YearMonth, capitalize: Boolean = false): String {
    val locale = appLocale()
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    return "${if (capitalize) name.replaceFirstChar { it.uppercase(locale) } else name} ${month.year}"
}

/**
 * Päiväväli Kielitoimiston ohjeen mukaan ilman välejä: "1.–18.10.2026", "1.9.–18.10.2026", "28.12.2025–3.1.2026";
 * sama päivä "16.10.2026". Englanniksi "1–18 Oct 2026", "1 Sep – 18 Oct 2026", "28 Dec 2025 – 3 Jan 2026".
 */
fun formatDayRange(fromDay: Long, toDay: Long): String {
    val from = LocalDate.ofEpochDay(minOf(fromDay, toDay))
    val to = LocalDate.ofEpochDay(maxOf(fromDay, toDay))
    if (isEnglish()) return englishDayRange(from, to)
    val end = "${to.dayOfMonth}.${to.monthValue}.${to.year}"
    val start = when {
        from == to -> return end
        from.year != to.year -> "${from.dayOfMonth}.${from.monthValue}.${from.year}"
        from.monthValue != to.monthValue -> "${from.dayOfMonth}.${from.monthValue}."
        else -> "${from.dayOfMonth}."
    }
    return "$start–$end"
}

private fun englishDayRange(from: LocalDate, to: LocalDate): String {
    fun month(date: LocalDate) = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    val end = "${to.dayOfMonth} ${month(to)} ${to.year}"
    return when {
        from == to -> end
        from.year != to.year -> "${from.dayOfMonth} ${month(from)} ${from.year} – $end"
        from.monthValue != to.monthValue -> "${from.dayOfMonth} ${month(from)} – $end"
        else -> "${from.dayOfMonth}–$end"
    }
}

/** Valuutan merkki: euro €, muut ISO-koodina (SEK, NOK, DKK). */
fun currencySymbol(currency: String): String = if (currency == "EUR") "€" else currency

/** Rahamäärä valuuttoineen; 0 = hintaa ei tiedetä → "–". */
fun formatMoney(amount: Double, currency: String): String =
    if (amount > 0) "${formatDecimal(amount, 2)} ${currencySymbol(currency)}" else "–"

/** Valuutoittaiset summat ilman muunnosta: "45,20 € + 512,00 SEK"; tyhjä → "–". */
fun formatMoneyByCurrency(costs: Map<String, Double>): String =
    costs.filterValues { it > 0 }.entries.joinToString(" + ") { formatMoney(it.value, it.key) }.ifEmpty { "–" }

/** Yksikköhinta valuutan ja määrän yksikön mukaan: "1,859 €/l", "0,250 €/kWh", "18,900 SEK/l"; 0 → "–". */
fun formatUnitPrice(price: Double, currency: String, unit: FuelUnit): String =
    if (price > 0) "${formatDecimal(price, 3)} ${currencySymbol(currency)}/${unitSymbol(unit)}" else "–"

/** Määrän yksikkö: litra, kaasun kilo tai sähkön kilowattitunti. */
fun unitSymbol(unit: FuelUnit): String = when (unit) {
    FuelUnit.LITER -> "l"
    FuelUnit.KILOGRAM -> "kg"
    FuelUnit.KILOWATT_HOUR -> "kWh"
}

/** Määrä lajin yksikössä: "45,2 l", "12,5 kg", "35,0 kWh". */
fun formatAmount(amount: Double, kind: FuelKind): String = "${formatDecimal(amount, 1)} ${unitSymbol(kind.unit)}"

/** Kulutus lajin yksikössä: polttoaineet /100 km ("9,1 l/100 km", "18,0 kWh/100 km"), AdBlue /1000 km. */
fun formatConsumption(value: Double, kind: FuelKind): String =
    "${formatDecimal(value, 1)} ${unitSymbol(kind.unit)}/${kind.perKm} km"

/** Hinta kilometriltä; ilman hintaa tai usean valuutan jaksolla (valuutta null) "–". */
fun formatCostPerKm(costPerKm: Double?, currency: String?): String =
    if (costPerKm != null && currency != null) "${formatDecimal(costPerKm, 2)} ${currencySymbol(currency)}/km" else "–"

fun formatPercent(value: Double): String = "${formatDecimal(value, 1)} %"
