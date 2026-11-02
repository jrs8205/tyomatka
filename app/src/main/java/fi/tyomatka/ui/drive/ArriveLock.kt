package fi.tyomatka.ui.drive

/** Etäisyys, jonka alittuessa "Perillä stopilla" aukeaa. */
internal const val ARRIVE_UNLOCK_DISTANCE_M = 300

/**
 * Vahinkoklikkauslukko: nappi aukeaa vasta kun navigoinnin etäisyystieto on
 * saapunut ja matkaa on alle rajan. Lähtö ja kohteenvaihto nollaavat sekä
 * `liveDistanceM`in että [fallbackDistanceM]in nulliksi, joten nappi on heti
 * edellisen stopin kuittauksen jälkeen aina lukossa eikä tuplanapautus voi
 * kirjata saapumista. Jos kohteen reittihaku epäonnistui (palvelinkatko),
 * linnuntie [fallbackDistanceM] avaa lukon samalla rajalla, jottei päivä jää
 * ohitusten varaan.
 */
internal fun isArriveUnlocked(liveDistanceM: Int?, fallbackDistanceM: Int?): Boolean {
    val distance = liveDistanceM ?: fallbackDistanceM
    return distance != null && distance <= ARRIVE_UNLOCK_DISTANCE_M
}
