package fi.tyomatka.ui

import android.content.res.Configuration
import java.util.Locale

private val FINNISH = Locale.forLanguageTag("fi-FI")
private val SWEDISH = Locale.forLanguageTag("sv-FI")
private val ENGLISH = Locale.forLanguageTag("en-GB")

/**
 * Kieli, jonka Android valitsi teksteille. Kielilistalla [viro, suomi] tekstit tulevat suomeksi, vaikka
 * `Locale.getDefault()` on viro: luvut, päivät ja ääniohjeet seuraavat tekstejä. Ennen päivitystä käytetään oletuskieltä.
 */
object DisplayLanguage {
    @Volatile
    private var resolved: String? = null

    fun set(language: String?) {
        resolved = language
    }

    fun update(configuration: Configuration) = set(configuration.locales[0].language)

    internal fun current(): String = resolved ?: Locale.getDefault().language
}

/** Näytön kieli: suomi ja ruotsi omillaan, muut englanniksi kuten tekstit (`values` on englanti). */
fun appLocale(): Locale = when (DisplayLanguage.current()) {
    "fi" -> FINNISH
    "sv" -> SWEDISH
    else -> ENGLISH
}

/** Englanniksi desimaalipiste ja kellonajan kaksoispiste; suomeksi ja ruotsiksi pilkku ja piste. */
internal fun isEnglish(): Boolean = appLocale() == ENGLISH

/** Kellonajat valmiissa tekstissä: englanniksi "6.00–22.00" → "6:00–22:00". */
fun localClockText(text: String): String = if (isEnglish()) CLOCK_IN_TEXT.replace(text, "$1:$2") else text

/** Lyhyt aika "21" tai "6.30": englanniksi "21:00" ja "6:30". */
fun localShortClock(time: String): String = when {
    !isEnglish() -> time
    '.' in time -> time.replace('.', ':')
    else -> "$time:00"
}

private val CLOCK_IN_TEXT = Regex("""\b(\d{1,2})\.(\d{2})\b""")

/** Valmiiksi pisteellinen luku näytön kielellä: "25.25" → "25,25". */
fun localDecimal(text: String): String = if (isEnglish()) text else text.replace('.', ',')
