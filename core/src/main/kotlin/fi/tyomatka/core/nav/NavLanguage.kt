package fi.tyomatka.core.nav

/**
 * Ajo-ohjeiden ja puheen kieli puhelimen kielestä: suomi ja ruotsi omillaan, muut englanniksi. Sama tunniste lähetetään
 * reitityspalvelimelle ja puhesynteesille, jotta ohjeen teksti ja ääni ovat samaa kieltä. Alueellinen osa ("fi-FI",
 * "sv_FI") ei vaikuta.
 */
fun navLanguageTag(languageCode: String): String =
    when (languageCode.substringBefore('-').substringBefore('_').lowercase()) {
        "fi" -> "fi-FI"
        "sv" -> "sv-SE"
        else -> "en-US"
    }
