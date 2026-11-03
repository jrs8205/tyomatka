package fi.tyomatka.ui.map

import java.util.Locale

/**
 * MapLibren mittakaavapalkki päättelee yksikkönsä oletuskielen maasta: Yhdysvalloissa, Liberiassa ja Myanmarissa se
 * näyttää mailit ja jalat, eikä yksikköä voi valita. Sovellus käyttää aina kilometrejä, joten palkki näytetään vain,
 * kun se on metrinen (esimerkiksi englanti-US-kielellä palkkia ei näytetä).
 */
internal fun scaleBarIsMetric(locale: Locale): Boolean = locale.country.uppercase(Locale.ROOT) !in IMPERIAL_COUNTRIES

/** Samat maat kuin MapLibren mittakaavapalkin omassa yksikköpäättelyssä. */
private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
