package fi.tyomatka.ui.history

import fi.tyomatka.R
import fi.tyomatka.data.repo.WorkImportResult
import fi.tyomatka.ui.formatDayRange
import fi.tyomatka.ui.history.forms.importKindsWord

/**
 * Tuonnin ilmoitus: tekstin tunniste, kirjausten sanan tunniste ("tankkauksia", "latauksia ja täyttöjä"…) ja luvut
 * järjestyksessä. Teksti saa sanan ensimmäisenä argumenttina ja luvut sen perässä. [period]: jakson varmuuskopion
 * päiväväli ("1.–18.10.2026") lisälauseeseen; koko varmuuskopiolla null.
 */
data class ImportNotice(
    val textRes: Int,
    val wordRes: Int,
    val counts: List<Int>,
    val period: String? = null,
    /** Tuodut tallennetut reitit; mainitaan vain, kun niitä tuotiin (versio 8). */
    val templates: Int = 0,
    /** Kirjoitetut karttakohteiden korjaukset ja hinnat; mainitaan vain, kun niitä tuotiin (versio 8). */
    val poiEdits: Int = 0,
)

/**
 * Kuvamäärä mainitaan vain, kun kuvia tuotiin: vanha varmuuskopio (v1–v4) ei tunne kuvia, ja ilmoitus pysyy lyhyenä.
 * Kirjausten sana seuraa varmuuskopion kirjausten lajeja.
 */
fun importNotice(result: WorkImportResult): ImportNotice {
    val skipped = result.skipped + result.fuelSkipped
    val word = importKindsWord(result.fuelKinds)
    // Jakson varmuuskopio ei sisällä koko historiaa: käyttäjä näkee, ettei puhelimeen palautettu kaikkea.
    val period = result.period?.let { (from, to) -> formatDayRange(from, to) }
    return if (result.photosAdded > 0) {
        ImportNotice(R.string.work_import_done_photos, word, listOf(result.added, result.fuelAdded, result.photosAdded, skipped), period, result.templates, result.poiEdits)
    } else {
        ImportNotice(R.string.work_import_done, word, listOf(result.added, result.fuelAdded, skipped), period, result.templates, result.poiEdits)
    }
}
