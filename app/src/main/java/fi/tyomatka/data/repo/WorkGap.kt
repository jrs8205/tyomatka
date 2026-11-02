package fi.tyomatka.data.repo

/** Viimeisin kirjattu mittarilukema. */
data class LastReading(
    val epochDay: Long,
    val km: Long,
    /** true = lopetuslukema, false = aloituslukema (lopetus puuttui). */
    val isEnd: Boolean,
    val licensePlate: String? = null,
)

/**
 * Ehdotus unohtuneen välin kirjaamiseksi: uusi lukema on noussut edellisestä
 * ja väliin jää työpäiviä ilman kirjausta.
 */
data class GapSuggestion(
    val previousEpochDay: Long,
    val previousKm: Long,
    val previousWasEnd: Boolean,
    val newKm: Long,
    /** Puuttuvat työpäivät (epochDay, nousevassa järjestyksessä) → esitäytetty km-jako. */
    val perDay: Map<Long, Long>,
    val licensePlate: String? = null,
) {
    val diffKm: Long get() = newKm - previousKm
}

/**
 * Muistutetaanko kirjaamattomasta työpäivästä autoilun alkaessa?
 * Korkeintaan kerran päivässä; "Ohita tänään" vaientaa loppupäivän.
 */
fun shouldRemindWork(
    hasSegmentsToday: Boolean,
    todayEpochDay: Long,
    skippedEpochDay: Long?,
    notifiedEpochDay: Long?,
    notificationsEnabled: Boolean = true,
): Boolean =
    notificationsEnabled && !hasSegmentsToday && skippedEpochDay != todayEpochDay && notifiedEpochDay != todayEpochDay

/**
 * Tunnista kirjaamaton väli. Erotus jaetaan tasan puuttuville työpäiville
 * (jakojäännös vanhimmille päiville); käyttäjä voi muokata jakoa ennen
 * tallennusta, joten tarkkuus ei ole kriittistä — summa on.
 */
fun findUnrecordedGap(
    newReadingKm: Long,
    lastReading: LastReading?,
    missingWorkDays: List<Long>,
    newPlate: String? = lastReading?.licensePlate,
): GapSuggestion? {
    if (lastReading == null || missingWorkDays.isEmpty()) return null
    // Eri auto → ei ehdotusta. Tyhjä rekkari kummalla tahansa puolella ei estä:
    // vanhat segmentit (ennen v6) ovat ilman rekkaria, eikä rekkari ole pakollinen.
    val newKey = newPlate?.let(::normalizePlate)
    val lastKey = lastReading.licensePlate?.let(::normalizePlate)
    if (newKey != null && lastKey != null && newKey != lastKey) return null
    val diff = newReadingKm - lastReading.km
    if (diff <= 0) return null
    val sorted = missingWorkDays.distinct().sorted()
    val base = diff / sorted.size
    val remainder = diff % sorted.size
    val perDay = LinkedHashMap<Long, Long>()
    sorted.forEachIndexed { index, day ->
        perDay[day] = base + if (index < remainder) 1L else 0L
    }
    return GapSuggestion(
        previousEpochDay = lastReading.epochDay,
        previousKm = lastReading.km,
        previousWasEnd = lastReading.isEnd,
        newKm = newReadingKm,
        perDay = perDay,
        licensePlate = lastReading.licensePlate,
    )
}
