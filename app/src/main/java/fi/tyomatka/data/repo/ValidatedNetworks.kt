package fi.tyomatka.data.repo

/**
 * Oletusverkon internetyhteyden tila ilmoitusjonon lähetystä varten: [update] on tosi, kun oletusverkko siirtyy
 * vahvistettuun tilaan (Androidin `NET_CAPABILITY_VALIDATED`). Uusi tai palannut oletusverkko, internetyhteyden
 * palautuminen samassa Wi-Fi-verkossa ja kirjautumissivun takaa vapautunut verkko käynnistävät siis lähetyksen, mutta
 * toistuvat ominaisuusmuutokset (signaali, kaista) eivät. Oletusverkon kuuntelija seuraa yhtä verkkoa kerrallaan eikä
 * saa onLost-kutsua korvatulle verkolle, joten tila nollataan aina verkon vaihtuessa.
 */
class ValidatedNetworks<K> {
    private var current: K? = null
    private var validated = false

    /** Verkko tuli oletukseksi; sen vahvistus tulee seuraavassa [update]-kutsussa. */
    @Synchronized
    fun available(network: K) {
        current = network
        validated = false
    }

    @Synchronized
    fun update(network: K, validated: Boolean): Boolean {
        if (network != current) available(network)
        val rising = validated && !this.validated
        this.validated = validated
        return rising
    }

    @Synchronized
    fun lost(network: K) {
        if (network == current) {
            current = null
            validated = false
        }
    }
}
