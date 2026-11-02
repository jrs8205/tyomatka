package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.PoiBox

/** Ilmoitushaku tietylle ikkunalle; [seq] kertoo aloitusjärjestyksen. */
class ReportFetch internal constructor(val box: PoiBox, internal val seq: Long)

/**
 * Kartan ilmoitushakujen vartija: tuloksen saa kirjoittaa vain haku, joka aloitettiin ikkunan viimeisimmän vaihdon
 * ([moveTo]) jälkeen ja jonka sisältö on vähintään yhtä uusi kuin näkyvä. Sisällön ikä on repositorion antama versio
 * ([fi.tyomatka.data.repo.RemoteReports.version], hakujen aloitusjärjestys), ei kellonaika eikä se, tuliko vastaus nyt
 * verkosta: välimuisti on yhteinen kartan ja ajonäkymän kesken, joten toisen näkymän hakema uudempi tieto kelpaa, vaikka
 * oma haku epäonnistui, eikä saman sekunnin tai kellon siirron yli myöhästynyt vanha vastaus syrjäytä uudempaa.
 * Sisällötön tulos (verkkovirhe ilman välimuistia) kelpaa vain uuteen ikkunaan, jossa mitään ei vielä näy.
 * Käytetään pääsäikeestä.
 */
class ReportWindowGuard {
    var box: PoiBox? = null
        private set
    private var started = 0L
    private var movedAt = 0L
    private var written = false
    private var shownVersion = Long.MIN_VALUE

    fun moveTo(box: PoiBox?) {
        this.box = box
        movedAt = started
        written = false
        shownVersion = Long.MIN_VALUE
    }

    fun begin(): ReportFetch? = box?.let { ReportFetch(it, ++started) }

    /** [version] on vastauksen sisällön versio tai null, kun sisältöä ei ole. */
    fun accept(fetch: ReportFetch, version: Long?): Boolean {
        if (fetch.seq <= movedAt) return false
        if (version == null) {
            if (written) return false
        } else {
            if (version < shownVersion) return false
            shownVersion = version
        }
        written = true
        return true
    }
}
