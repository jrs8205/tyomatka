package fi.tyomatka.ui.history

import fi.tyomatka.ui.isEnglish
import fi.tyomatka.ui.localDecimal
import java.util.Locale

/** Mikä hintakenttä muuttui — määrää, mikä lasketaan. */
enum class FuelField { LITERS, TOTAL, PER_LITER }

/** Tankkausdialogin kolme hintakenttää teksteinä. */
data class FuelPrices(val liters: String, val total: String, val perLiter: String)

/** Desimaaliluku pilkulla tai pisteellä; muu → null. */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 }

/** Kenttään kirjoitettava desimaaliteksti näytön kielen erottimella. */
fun decimalText(value: Double, decimals: Int): String =
    localDecimal(String.format(Locale.ROOT, "%.${decimals}f", value))

/** Muokkaus ei saa pyöristää aiemmin hyväksyttyä määrää pelkän avaamisen vuoksi. */
fun editableDecimal(value: Double): String =
    localDecimal(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())

/** Rajaa syötteen numeroihin ja yhteen desimaalierottimeen (näytetään kielen erottimena). */
fun filterDecimalInput(raw: String): String {
    val out = StringBuilder()
    var separator = false
    raw.forEach { c ->
        when {
            c.isDigit() -> out.append(c)
            (c == ',' || c == '.') && !separator -> {
                out.append(if (isEnglish()) '.' else ',')
                separator = true
            }
        }
    }
    return out.toString()
}

/**
 * Kaksi kentistä määrää kolmannen: summa muuttui → €/l; €/l muuttui →
 * summa. Litrojen muuttuessa lasketaan se kenttä, jota käyttäjä EI
 * viimeksi kirjoittanut ([anchor] = viimeksi kirjoitettu hintakenttä) —
 * muuten merkki kerrallaan kirjoitetut litrat vääristäisivät juuri
 * syötetyn €/l:n. Ilman ankkuria summa on ensisijainen.
 * Tyhjä tai nollalitrainen syöte ei laske mitään.
 */
fun resolveFuelPrices(edited: FuelField, prices: FuelPrices, anchor: FuelField? = null): FuelPrices {
    // Tyhjennetty hinta: toinen kenttä tyhjenee vain, jos se oli laskettu tästä.
    // Käyttäjän itse kirjoittama (ankkuri)kenttä säilyy uudelleenkirjoitusta varten.
    if (edited == FuelField.TOTAL && prices.total.isBlank()) {
        return if (anchor == FuelField.PER_LITER) prices else prices.copy(perLiter = "")
    }
    if (edited == FuelField.PER_LITER && prices.perLiter.isBlank()) {
        return if (anchor == FuelField.TOTAL) prices else prices.copy(total = "")
    }
    val liters = parseDecimal(prices.liters)?.takeIf { it > 0 } ?: return prices
    val total = parseDecimal(prices.total)
    val perLiter = parseDecimal(prices.perLiter)
    return when (edited) {
        FuelField.LITERS -> when {
            anchor == FuelField.PER_LITER && perLiter != null ->
                prices.copy(total = calculatedPrice(liters * perLiter, 2))
            total != null -> prices.copy(perLiter = calculatedPrice(total / liters, 3))
            perLiter != null -> prices.copy(total = calculatedPrice(liters * perLiter, 2))
            else -> prices
        }
        FuelField.TOTAL ->
            if (total != null) prices.copy(perLiter = calculatedPrice(total / liters, 3)) else prices
        FuelField.PER_LITER ->
            if (perLiter != null) prices.copy(total = calculatedPrice(liters * perLiter, 2)) else prices
    }
}

private fun calculatedPrice(value: Double, decimals: Int): String =
    if (value.isFinite()) decimalText(value, decimals) else "–"
