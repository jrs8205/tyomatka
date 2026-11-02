package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.Instant
import java.time.ZoneId

/** Suomalainen perusmuoto ilman väliviivaa: 2–3 kirjainta + 1–3 numeroa. */
private val PLAIN_PLATE = Regex("^([A-ZÅÄÖ]{2,3})([0-9]{1,3})$")

/**
 * Rekisterinumero yhtenäiseen muotoon: isot kirjaimet, ei välilyöntejä,
 * "ABC123" → "ABC-123". Muut muodot (esim. ulkomainen) säilyvät sellaisenaan.
 * Tyhjä → null. Sama auto tunnistetaan näin kirjoitusasusta riippumatta.
 */
fun normalizePlate(text: String): String? {
    val cleaned = text.uppercase().filterNot(Char::isWhitespace)
    if (cleaned.isEmpty()) return null
    val match = PLAIN_PLATE.matchEntire(cleaned) ?: return cleaned
    return "${match.groupValues[1]}-${match.groupValues[2]}"
}

/** Vertailuavain: vain kirjaimet ja numerot ("AB-12345" = "AB12345"). */
internal fun plateKey(plate: String): String = plate.filter(Char::isLetterOrDigit).uppercase()

/** Autoprofiilin rekisterinumero: yhtenäinen muoto, ja siinä on vähintään yksi kirjain tai numero (ei pelkkä "-"). */
fun profilePlate(text: String): String? = normalizePlate(text)?.takeIf { plate -> plate.any(Char::isLetterOrDigit) }

/** Rekisteri ja esitäytettävä lukema kuuluvat aina samaan viimeisimpään autoon. */
fun latestWorkSegment(segments: List<WorkSegmentEntity>): WorkSegmentEntity? =
    segments.maxWithOrNull(compareBy({ it.startEpochSec }, { it.id }))

fun latestWorkReading(segments: List<WorkSegmentEntity>): Long? =
    latestWorkSegment(segments)?.let { it.odometerEndKm ?: it.odometerStartKm }

/**
 * Auton viimeisin tunnettu mittarilukema: työjaksojen loppu- ja alkulukemat sekä kirjausten lukemat, ajallisesti
 * viimeisimmän kalenteripäivän ([zone]) lukemista suurin. Takautuva kirjaus saa aikaleimakseen klo 12
 * ([fillupEpochSec]), eikä se saa hävitä saman päivän aiemmin kirjatulle pienemmälle lukemalle (laitteella 18.10.);
 * eri päivien kesken viimeisin päivä voittaa, vaikka vanhempi lukema olisi suurempi. Null, kun autolle ei tunneta
 * lukemaa. Toisen auton lukemat eivät vaikuta, jotta valitun auton aloitus ei saa edellisen auton lukemaa.
 */
fun latestPlateReading(
    plate: String,
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    val readings = segments.filter { it.licensePlate == plate }.flatMap { seg ->
        listOfNotNull(
            // Loppulukema ilman lopetushetkeä on epätavallinen, mutta kuuluu silti jakson aikaan.
            seg.odometerEndKm?.let { km -> (seg.endEpochSec ?: seg.startEpochSec) to km },
            seg.odometerStartKm?.let { km -> seg.startEpochSec to km },
        )
    } + fillups.filter { it.licensePlate == plate }.map { it.epochSec to it.odometerKm }
    val byDay = readings.groupBy({ (epochSec, _) -> Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate() }) { it.second }
    return byDay.maxByOrNull { it.key }?.value?.max()
}

/** Rekisterinumero → auton viimeisin tunnettu lukema ([latestPlateReading]) autoille, joille lukema tunnetaan. */
fun latestPlateReadings(
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<String, Long> = knownPlates(segments, fillups)
    .mapNotNull { plate -> latestPlateReading(plate, segments, fillups, zone)?.let { plate to it } }
    .toMap()

/**
 * Ehdotusnapit rekkarikentän alle: tyhjä syöte → kaikki tunnetut, muuten
 * syötteellä alkavat (väliviivat ohitetaan). Tarkka osuma → ei nappeja,
 * ettei kenttä tarjoa sitä mitä siinä jo on.
 */
fun plateSuggestions(input: String, known: List<String>, max: Int = 8): List<String> {
    val key = plateKey(input)
    if (key.isEmpty()) return known.take(max)
    val matches = known.filter { plateKey(it).startsWith(key) }
    if (matches.any { plateKey(it) == key }) return emptyList()
    return matches.take(max)
}

/** Tunnetut rekkarit segmenteistä ja tankkauksista, viimeisin käyttö ensin. */
fun knownPlates(
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
): List<String> {
    val lastUse = HashMap<String, Long>()
    fun note(plate: String?, epochSec: Long) {
        if (plate == null) return
        lastUse[plate] = maxOf(lastUse[plate] ?: Long.MIN_VALUE, epochSec)
    }
    segments.forEach { note(it.licensePlate, it.startEpochSec) }
    fillups.forEach { note(it.licensePlate, it.epochSec) }
    return lastUse.entries.sortedByDescending { it.value }.map { it.key }
}
