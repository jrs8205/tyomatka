package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelPhotoEntity

/** Viennin paneelin varoitusraja: tätä suurempi tiedosto on hidas jakaa ja avata puhelimella. */
const val LARGE_EXPORT_BYTES = 50L * 1024 * 1024

/** Pienoiskuvan arvioitu koko raportissa. */
private const val THUMBNAIL_ESTIMATE = 30L * 1024

/** Tekstin, tyylien, skriptin ja varmuuskopion JSONin arvioitu koko. */
private const val BASE_ESTIMATE = 200L * 1024

/** Viennin yhteenveto paneeliin: työpäivät, kirjaukset sanaryhmittäin, kuvat, autoprofiilit ja arvioitu koko. */
data class ExportSummary(
    val workDays: Int,
    val counts: Map<FillGroup, Int>,
    val photos: Int,
    val estimatedBytes: Long,
    val cars: Int = 0,
    /** Tallennetut reitit; vain koko varmuuskopiossa, jakson yhteenvedossa 0. */
    val templates: Int = 0,
) {
    /** Jaksolla ei ole työpäiviä eikä kirjauksia. */
    val isEmpty: Boolean get() = workDays == 0 && counts.values.all { it == 0 }

    val isLarge: Boolean get() = estimatedBytes > LARGE_EXPORT_BYTES
}

/**
 * Yhteenveto raportin mallista. Koon arvio: kuvat base64-muodossa (× 4/3), pienoiskuvat noin 30 kt kuvaa kohden ja
 * noin 200 kt muuta.
 */
fun summarizeExport(model: ReportModel, templates: Int = 0, photoSizes: (FuelPhotoEntity) -> Long): ExportSummary {
    val photoBytes = model.photos.sumOf { photoSizes(it.photo) }
    return ExportSummary(
        workDays = model.days.count { it.totals != null },
        counts = model.entries.groupingBy { fillGroup(it.fillup.kind) }.eachCount(),
        photos = model.photos.size,
        estimatedBytes = photoBytes * 4 / 3 + model.photos.size * THUMBNAIL_ESTIMATE + BASE_ESTIMATE,
        cars = model.carProfiles.size,
        templates = templates,
    )
}
