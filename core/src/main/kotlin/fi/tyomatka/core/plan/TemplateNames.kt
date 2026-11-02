package fi.tyomatka.core.plan

const val TEMPLATE_NAME_MAX_LENGTH = 60

private val whitespace = Regex("\\s+")

/** Tallennetun reitin nimi: reunavälit pois, välit yhdeksi, enintään [TEMPLATE_NAME_MAX_LENGTH] merkkiä; tyhjä = ei kelpaa. */
fun normalizeTemplateName(raw: String?): String =
    raw.orEmpty().trim().replace(whitespace, " ").take(TEMPLATE_NAME_MAX_LENGTH).trimEnd()
