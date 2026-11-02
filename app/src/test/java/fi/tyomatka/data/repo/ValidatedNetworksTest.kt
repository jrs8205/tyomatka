package fi.tyomatka.data.repo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatedNetworksTest {
    @Test
    fun `internetyhteyden palautuminen samassa verkossa käynnistää lähetyksen kerran`() {
        val networks = ValidatedNetworks<String>()
        networks.available("wifi")
        assertTrue(networks.update("wifi", validated = true))
        // Signaalin tai kaistan muutokset toistavat saman tilan: ei uutta lähetystä.
        assertFalse(networks.update("wifi", validated = true))
        assertFalse(networks.update("wifi", validated = false))
        assertFalse(networks.update("wifi", validated = false))
        assertTrue(networks.update("wifi", validated = true))
        assertFalse(networks.update("wifi", validated = true))
    }

    @Test
    fun `kirjautumissivun takana oleva verkko käynnistää lähetyksen vasta vapauduttuaan`() {
        val networks = ValidatedNetworks<String>()
        networks.available("hotelli")
        assertFalse(networks.update("hotelli", validated = false))
        assertTrue(networks.update("hotelli", validated = true))
    }

    @Test
    fun `oletusverkon vaihto takaisin aiempaan verkkoon käynnistää lähetyksen`() {
        // Oletusverkon kuuntelija ei saa onLost-kutsua edelliselle verkolle, kun korvaava verkko on olemassa.
        val networks = ValidatedNetworks<String>()
        networks.available("mobiili")
        assertTrue(networks.update("mobiili", validated = true))
        networks.available("wifi")
        assertTrue(networks.update("wifi", validated = true))
        networks.available("mobiili")
        assertTrue(networks.update("mobiili", validated = true))
    }

    @Test
    fun `ominaisuusmuutos ilman onAvailable-kutsua uudesta verkosta käynnistää lähetyksen`() {
        val networks = ValidatedNetworks<String>()
        assertTrue(networks.update("wifi", validated = true))
        assertTrue(networks.update("mobiili", validated = true))
        assertTrue(networks.update("wifi", validated = true))
    }

    @Test
    fun `kadonnut verkko unohtuu`() {
        val networks = ValidatedNetworks<String>()
        networks.available("wifi")
        assertTrue(networks.update("wifi", validated = true))
        networks.lost("wifi")
        assertTrue(networks.update("wifi", validated = true))
    }
}
