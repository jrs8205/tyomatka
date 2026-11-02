package fi.tyomatka.data.repo

import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus

/** Karttakohteen stopin paikka päivän listassa: loppuun (Kartta-välilehti) tai seuraavaksi pysähdykseksi (ajo). */
enum class ServiceStopPlacement { END, NEXT }

/**
 * Lisäyksen kirjoitukset: [orderIndex] uudelle stopille, [shiftUp] stopit, joiden järjestysnumero kasvaa yhdellä
 * (laskevassa järjestyksessä, jotta numerot eivät törmää), [demoteActive] ajossa oleva stoppi, joka palaa odottamaan.
 */
data class ServiceStopInsertion(val orderIndex: Int, val shiftUp: List<Long>, val demoteActive: Long?)

/**
 * NEXT: ensimmäisen tulevan (PENDING tai ACTIVE) stopin paikalle; ilman tulevia stoppeja kuten END. Stopilla seisottaessa
 * ARRIVED-stoppi pysyy edellä, joten "seuraavaksi" tarkoittaa heti lähdön jälkeen. END: suurin järjestysnumero + 1.
 */
fun serviceStopInsertion(stops: List<StopEntity>, placement: ServiceStopPlacement): ServiceStopInsertion {
    val ordered = stops.sortedBy { it.orderIndex }
    val end = ServiceStopInsertion((ordered.lastOrNull()?.orderIndex ?: -1) + 1, emptyList(), null)
    if (placement == ServiceStopPlacement.END) return end
    val next = ordered.firstOrNull { it.status == StopStatus.PENDING || it.status == StopStatus.ACTIVE } ?: return end
    return ServiceStopInsertion(
        orderIndex = next.orderIndex,
        shiftUp = ordered.filter { it.orderIndex >= next.orderIndex }.sortedByDescending { it.orderIndex }.map { it.id },
        demoteActive = next.id.takeIf { next.status == StopStatus.ACTIVE },
    )
}
