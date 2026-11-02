package fi.tyomatka.ui

import android.content.Context
import android.content.res.Configuration

/** Sovelluksen suurin fonttikoko: 150 %; suuremmalla tekstit eivät mahdu katkeamatta. */
const val MAX_FONT_SCALE = 1.5f

fun cappedFontScale(scale: Float): Float = scale.coerceAtMost(MAX_FONT_SCALE)

/**
 * Aktiviteetin konteksti, jonka fonttikoko on enintään [MAX_FONT_SCALE]. Dialogit ja alapaneelit käyttävät aktiviteetin
 * kontekstia, joten raja koskee myös niitä.
 */
fun Context.withCappedFontScale(): Context {
    val current = resources.configuration
    val capped = cappedFontScale(current.fontScale)
    if (capped == current.fontScale) return this
    // Vain fonttikoko ohitetaan: koko kopio jäädyttäisi myös näytön koon, jonka aktiviteetti käsittelee itse.
    return createConfigurationContext(Configuration().apply { fontScale = capped })
}
