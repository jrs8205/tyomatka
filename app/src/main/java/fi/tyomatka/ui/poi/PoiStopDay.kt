package fi.tyomatka.ui.poi

import fi.tyomatka.drive.DrivePhase
import fi.tyomatka.drive.DriveState

/**
 * Päivä, jonka listaan karttakohde lisätään stopiksi: ajon aikana ajossa oleva päivä, jottei yövuorossa puolenyön
 * jälkeen lisätty pysähdys päädy seuraavalle kalenteripäivälle ohi ajettavan reitin; muuten tämä päivä.
 */
suspend fun poiStopDayId(drive: DriveState, today: suspend () -> Long): Long {
    val driving = drive.phase == DrivePhase.NAVIGATING || drive.phase == DrivePhase.AT_STOP
    return drive.dayId?.takeIf { driving } ?: today()
}
