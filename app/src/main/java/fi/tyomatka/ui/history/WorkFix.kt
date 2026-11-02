package fi.tyomatka.ui.history

import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.normalizePlate
import fi.tyomatka.ui.formatClock
import fi.tyomatka.ui.parseClockMinutes
import java.time.Instant
import java.time.ZoneId

/** Korjausdialogin muokkaustila yhdelle segmentille (kenttien tekstit sellaisenaan). */
data class FixEdit(
    val startTime: String,
    val endTime: String,
    val startKm: String,
    val endKm: String,
    val plate: String = "",
) {
    companion object {
        /** Lomakkeen alkuarvot segmentistä: kellonajat "HH.mm", tyhjät kentät puuttuvista tiedoista. */
        fun of(seg: WorkSegmentEntity) = FixEdit(
            startTime = formatClock(seg.startEpochSec),
            endTime = seg.endEpochSec?.let(::formatClock) ?: "",
            startKm = seg.odometerStartKm?.toString() ?: "",
            endKm = seg.odometerEndKm?.toString() ?: "",
            plate = seg.licensePlate ?: "",
        )
    }
}

/** Jokin segmentin kenttä eroaa alkuarvoista: Tallenna aukeaa vasta muutoksesta. */
fun fixesChanged(segments: List<WorkSegmentEntity>, edits: Map<Long, FixEdit>): Boolean =
    segments.any { seg -> edits[seg.id] != FixEdit.of(seg) }

/** Korjausdialogin tulos yhdelle segmentille. */
data class SegmentFix(
    val startKm: Long?,
    val endKm: Long?,
    val startEpochSec: Long,
    /** Null = segmentti jää (tai pysyy) avoimeksi. */
    val endEpochSec: Long?,
    /** Normalisoitu rekkari tai null (tyhjä kenttä). */
    val licensePlate: String?,
)

/**
 * Jäsennä yhden segmentin korjaus kenttien teksteistä. Null = syöte ei
 * kelpaa, jolloin tallennus pysyy lukossa.
 *
 * Kellonajat pysyvät alkuperäisillä päivillään, myös kirjatuilla yövuoroilla. Päätetyllä
 * segmentillä lopetus on pakollinen. Avoimella segmentillä tyhjä lopetus
 * pitää päivän käynnissä ja täytetty lopetus päättää sen — näin unohtunut
 * "Lopeta työt" saadaan kirjattua jälkikäteen, vaikka päivä olisi jo
 * vaihtunut ja Tänään-kortti siirtynyt uuteen päivään.
 */
fun resolveSegmentFix(seg: WorkSegmentEntity, edit: FixEdit, zone: ZoneId): SegmentFix? {
    val start = parseClockMinutes(edit.startTime) ?: return null
    val endText = edit.endTime.trim()
    val end: Int? = when {
        endText.isEmpty() && seg.endEpochSec == null -> null
        else -> parseClockMinutes(endText) ?: return null
    }
    val startKm = edit.startKm.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    val endKm = edit.endKm.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    if (edit.startKm.isNotBlank() && (startKm == null || startKm < 0)) return null
    if (edit.endKm.isNotBlank() && (endKm == null || endKm < 0)) return null
    if (startKm != null && endKm != null && endKm < startKm) return null

    // Säilytä jo kirjattu yövuoron loppupäivä, sekunnit sekä kesäajan
    // toistetun tunnin offset, kun käyttäjä muuttaa vain lukemaa/rekkaria.
    fun atMinutes(minutes: Int, original: Long): Long? {
        val old = Instant.ofEpochSecond(original).atZone(zone)
        if (old.hour * 60 + old.minute == minutes) return original
        val local = old.toLocalDate().atTime(minutes / 60, minutes % 60)
        val resolved = java.time.ZonedDateTime.ofLocal(local, zone, old.offset)
        return resolved.takeIf { it.toLocalDateTime() == local }?.toEpochSecond()
    }
    val startAt = atMinutes(start, seg.startEpochSec) ?: return null
    val endAt = end?.let { atMinutes(it, seg.endEpochSec ?: seg.startEpochSec) ?: return null }
    if (endAt != null && endAt < startAt) return null
    return SegmentFix(
        startKm = startKm,
        endKm = endKm,
        startEpochSec = startAt,
        endEpochSec = endAt,
        licensePlate = normalizePlate(edit.plate),
    )
}
