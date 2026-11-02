package fi.tyomatka.ui.history

import fi.tyomatka.data.db.WorkSegmentEntity
import fi.tyomatka.data.repo.latestWorkSegment
import fi.tyomatka.ui.history.forms.WorkDayStatus
import fi.tyomatka.ui.history.forms.workDayStatus

/** Tänään-kortin luvut: työaika segmenteistä, kilometrit päättyneistä lukemista, autot ja mittarit. */
data class TodaySummary(
    val status: WorkDayStatus,
    val km: Long,
    val plates: List<String>,
    /** Mittarilukemat autoittain ensimmäisen jakson järjestyksessä; useampi alkio tarkoittaa autonvaihtoa. */
    val readings: List<CarReadings>,
    /** Jokin päättynyt segmentti on ilman lukemaa tai lukemat ovat ristiriidassa. */
    val missingReadings: Boolean,
    /**
     * Tilarivin auto: käynnissä avoimen jakson auto, joten rivi vaihtuu heti autonvaihdossa ja
     * rekisterinumeron korjauksessa; päättyneenä päivän viimeisen jakson auto; aloittamatta seuraavan ajon auto.
     * Null, kun autoa ei tiedetä tai jaksolla ei ole rekisterinumeroa.
     */
    val statusPlate: String? = null,
)

/** Tilarivin osien erotin: ainoa kohta, josta rivi saa vaihtua. */
private const val STATUS_SEPARATOR = " · "

/**
 * Tänään-kortin tilarivi: tilan teksti ("Käynnissä alkaen 7.12 · 2 h 10 min") ja sen perässä auton rekisterinumero.
 * Rivi saa vaihtua vain " · "-erottimen kohdalta: osien välilyönnit ovat sitovia (U+00A0) ja
 * yhdysmerkit sitovia (U+2011), ettei rekisterinumero katkea muotoon "ABC-" / "123".
 */
fun todayStatusLine(statusText: String, plate: String?): String =
    (statusText.split(STATUS_SEPARATOR) + listOfNotNull(plate)).joinToString(STATUS_SEPARATOR) { part ->
        part.replace(' ', '\u00A0').replace('-', '\u2011')
    }

/**
 * Yhden auton mittarilukemat päivältä: ensimmäisen jakson alku ja viimeisen päättyneen jakson loppu. Avoin jakso ei
 * anna loppulukemaa. Eri autojen lukemat eivät sekoitu autonvaihdon jälkeen.
 */
data class CarReadings(
    /** Null: jakso on kirjattu ilman rekisterinumeroa. */
    val plate: String?,
    val startKm: Long?,
    val endKm: Long?,
)

/** [nextPlate]: seuraavan ajon auto ([fi.tyomatka.data.repo.defaultPlateWithChoice]) tilariville, kun päivä on aloittamatta. */
fun todaySummary(segments: List<WorkSegmentEntity>, nowEpochSec: Long, nextPlate: String? = null): TodaySummary {
    val ordered = segments.sortedBy { it.startEpochSec }
    val closed = ordered.filter { it.endEpochSec != null }
    val km = closed.sumOf { seg ->
        val start = seg.odometerStartKm
        val end = seg.odometerEndKm
        if (start != null && end != null && end >= start) end - start else 0L
    }
    val missing = closed.any { seg ->
        val start = seg.odometerStartKm
        val end = seg.odometerEndKm
        start == null || end == null || end < start
    }
    return TodaySummary(
        status = workDayStatus(segments, nowEpochSec),
        km = km,
        plates = ordered.mapNotNull { it.licensePlate }.distinct(),
        readings = ordered.groupBy { it.licensePlate }.map { (plate, own) ->
            CarReadings(
                plate = plate,
                startKm = own.first().odometerStartKm,
                endKm = own.lastOrNull { it.endEpochSec != null }?.odometerEndKm,
            )
        },
        missingReadings = missing,
        statusPlate = when {
            segments.isEmpty() -> nextPlate
            else -> (segments.firstOrNull { it.endEpochSec == null } ?: latestWorkSegment(segments))?.licensePlate
        },
    )
}
