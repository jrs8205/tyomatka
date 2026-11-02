package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.ZoneId

/**
 * Autot-näkymästä seuraavaan ajoon valittu auto ja valintahetki; säilyy [NextCarStore]ssa.
 * [latestSegmentId]: valintahetken viimeisin työjakso ([latestWorkSegment]) tai null, kun jaksoja ei ollut. Valinta
 * pätee vain, kun viimeisin jakso on yhä sama; [chosenEpochSec] on tiedoksi, ei vertailuun, koska
 * kellon siirto tai jakson alun kanssa samalle sekunnille osunut valinta hylkäisi kelvollisen valinnan.
 */
data class NextCarChoice(val plate: String, val chosenEpochSec: Long, val latestSegmentId: Long?)

/**
 * Oletusauto valinnan kanssa (työn aloituksen esitäyttö, Tänään-kortin nappi ja rekisterinumero, tankkauslomake):
 * valinta voittaa johdetun oletuksen ([defaultCarPlate]), jos viimeisin työjakso on yhä valintahetken jakso. Työn
 * aloitus tai autonvaihto tekee valintaa uudemman jakson ja ohittaa sen. Tämän päivän avoin jakso [openSegmentToday]
 * (sama kuin Työmatka-sivun käynnissä oleva jakso) voittaa valinnan; eilinen unohtunut tai yövuoron avoin jakso ei estä
 * valintaa. Valinta ohitetaan myös, kun autoa ei enää ole Autot-näkymässä (profiili poistettu);
 * auto, jolla on vain kirjauksia, on näkymässä ja kelpaa.
 */
fun defaultPlateWithChoice(
    choice: NextCarChoice?,
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
    cars: List<CarEntity>,
    openSegmentToday: WorkSegmentEntity?,
): String? {
    val derived = defaultCarPlate(segments, fillups, cars)
    if (choice == null || choice.plate !in suggestionPlates(knownPlates(segments, fillups), cars)) return derived
    if (openSegmentToday != null) return derived
    return if (latestWorkSegment(segments)?.id == choice.latestSegmentId) choice.plate else derived
}

/** Työn aloituksen esitäyttö: auto ja sen mittarilukema; kumpikin voi puuttua. */
data class StartPrefill(val plate: String?, val km: Long?)

/**
 * Työn aloituksen esitäyttö Työmatka-sivulle ja Ajo-välilehdelle: auto [defaultPlateWithChoice]n
 * säännöillä ja lukema **saman auton** viimeisin tunnettu lukema ([latestPlateReading]), jottei valitun auton
 * aloitukseen tule toisen auton lukemaa. Jos autolle ei tunneta lukemaa, lukema on tyhjä; jos autoa ei tiedetä,
 * lukema on viimeisimmän työjakson lukema kuten ennen ([latestWorkReading]). [zone] on sama kuin lomakkeen lukemissa
 * autoittain ([latestPlateReadings]), jotta esitäyttö ja auton vaihto laskevat päivän rajan samoin.
 */
fun startPrefill(
    choice: NextCarChoice?,
    segments: List<WorkSegmentEntity>,
    fillups: List<FuelFillupEntity>,
    cars: List<CarEntity>,
    openSegmentToday: WorkSegmentEntity?,
    zone: ZoneId,
): StartPrefill {
    val plate = defaultPlateWithChoice(choice, segments, fillups, cars, openSegmentToday)
    val km = if (plate == null) latestWorkReading(segments) else latestPlateReading(plate, segments, fillups, zone)
    return StartPrefill(plate, km)
}
