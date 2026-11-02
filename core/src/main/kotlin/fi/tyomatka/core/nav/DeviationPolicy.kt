package fi.tyomatka.core.nav

/**
 * Milloin reitiltä poikkeaminen hakee uuden reitin. Ferrostarin oletus hakisi jokaisesta poikkeamasta, myös askeleesta
 * poikkeamisesta reitin sisällä, pysäytetyn opastuksen tai tunnelin vanhan sijainnin aikana ja heti opastuksen alussa,
 * jolloin ytimen aloitussijainti on edellisen legin viimeinen piste eikä vielä tuore. Ajat ovat laitteen
 * käynnistyskellosta (`elapsedRealtime`), eivät seinäkellosta.
 */
object DeviationPolicy {
    const val MAX_FIX_AGE_MS = 10_000L

    /** Käsittelyhetkeä uudempi sijainti on kellovirhe; sekunnin verran sallitaan. */
    const val MAX_FUTURE_MS = 1_000L

    /** Opastuksen ensimmäiset sekunnit: seuraava oikea sijainti korjaa vanhan aloituspisteen ilman uutta reittiä. */
    const val MIN_GUIDANCE_AGE_MS = 2_000L

    fun shouldReroute(guidanceActive: Boolean, offStepOnly: Boolean, fixAgeMs: Long, guidanceAgeMs: Long): Boolean =
        guidanceActive && !offStepOnly && fixAgeMs in -MAX_FUTURE_MS..MAX_FIX_AGE_MS && guidanceAgeMs >= MIN_GUIDANCE_AGE_MS
}
