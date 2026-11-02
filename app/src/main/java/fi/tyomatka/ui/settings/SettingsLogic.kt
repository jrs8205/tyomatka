package fi.tyomatka.ui.settings

import fi.tyomatka.core.api.Defaults

/** Julkinen yhteystieto: tietosuojaseloste, asetukset ja FOSSGISin ehtojen vaatima operaattorin osoite. */
const val CONTACT_EMAIL = "jrs8205@pm.me"

/** Seloste on aina oletuspalvelimella, koska se kuvaa tätä sovellusta eikä käyttäjän omaa palvelinta. */
fun privacyUrl(language: String): String =
    "${Defaults.POI_URL}/privacy?lang=${language.takeIf { it == "fi" || it == "sv" } ?: "en"}"

/** Laitteen puheääni. Käyttökelvoton vaatii verkon tai latauksen, joten se voisi vaieta kesken ajon. */
data class VoiceOption(val name: String, val usable: Boolean)

/** Puuttuva tai käyttökelvoton ääni palautuu järjestelmän oletukseen (null) ilman virhettä. */
fun pickVoice(wanted: String?, available: List<VoiceOption>): VoiceOption? =
    available.firstOrNull { it.name == wanted && it.usable }

/** Vakaa järjestys, jotta "Ääni 2" on sama ääni myös seuraavalla kerralla. */
fun selectableVoices(available: List<VoiceOption>): List<VoiceOption> = available.filter { it.usable }.sortedBy { it.name }

fun bottomBarVisible(hide: Boolean, route: String?, driving: Boolean): Boolean = !(hide && driving && route == "drive")
