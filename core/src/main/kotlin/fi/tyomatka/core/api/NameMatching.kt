package fi.tyomatka.core.api

/**
 * Sama nimi pienellä kirjoitusvirheellä: vähintään 4 merkin nimissä 1 virhe, vähintään 8 merkin nimissä
 * [maxTypos]. Vertailu ilman kirjainkokoa.
 */
internal fun similarName(candidate: String, wanted: String, maxTypos: Int): Boolean {
    val name = candidate.trim().lowercase()
    val target = wanted.trim().lowercase()
    val shorter = minOf(name.length, target.length)
    val allowed = when {
        shorter >= 8 -> maxTypos
        shorter >= 4 -> 1
        else -> 0
    }
    return name == target || (allowed > 0 && editDistance(name, target) <= allowed)
}

/**
 * Katujen tavalliset lyhenteet auki: "Kauppak." → Kauppakatu, "Koulut." → Koulutie, "Pihlajakj." → Pihlajakuja,
 * "Kungsg." → Kungsgatan, "Mannerheimv." → Mannerheimvägen. Pelkkä alku ei kelpaa vertailuun: "Kauppak." osuisi
 * myös Kauppakujaan. Alussa vähintään kolme kirjainta: lyhyet sanat ("rak.", "ent.") eivät ole katuja.
 */
internal fun expandStreetAbbreviations(text: String): String =
    ABBREVIATION.replace(text) { m -> m.groupValues[1] + STREET_SUFFIXES.getValue(m.groupValues[2].lowercase()) }

private val STREET_SUFFIXES = mapOf("kj" to "kuja", "k" to "katu", "t" to "tie", "g" to "gatan", "v" to "vägen")
private val ABBREVIATION = Regex("""(\p{L}{3,}?)(kj|k|t|g|v)\.(?=[\s,]|$)""", RegexOption.IGNORE_CASE)

/** Levenshteinin etäisyys: montako merkkiä on lisättävä, poistettava tai vaihdettava. */
internal fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val swap = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + swap)
        }
        previous = current
    }
    return previous[b.length]
}
