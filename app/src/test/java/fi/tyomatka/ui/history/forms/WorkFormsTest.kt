package fi.tyomatka.ui.history.forms

import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkFormsTest {

    @Test
    fun `aloitus sallii tyhjän lukeman ja hyväksyy numeron`() {
        assertTrue(WorkStartForm(km = "", plate = "", prefillKm = null).canSave)
        assertTrue(WorkStartForm(km = "123", plate = "", prefillKm = null).canSave)
    }

    @Test
    fun `lisätiedot rajataan 500 merkkiin eivätkä vaikuta tallennukseen`() {
        val start = WorkStartForm(km = "", plate = "", prefillKm = null).withNote("x".repeat(600))
        assertEquals(500, start.note.length)
        assertTrue(start.canSave)
        val end = WorkEndForm(km = "", minKm = null).withNote(" muistiinpano ")
        assertEquals(" muistiinpano ", end.note)
        assertTrue(end.canSave)
    }

    @Test
    fun `aloitus kertoo kuinka paljon lukema ylittää esitäytön`() {
        val form = WorkStartForm(km = "1250", plate = "", prefillKm = 1200)
        assertEquals(50L, form.overPrefillBy)
        assertNull(WorkStartForm(km = "1100", plate = "", prefillKm = 1200).overPrefillBy)
        assertEquals("1200", form.usePrefill().km)
    }

    @Test
    fun `lopetus alle aloituslukeman estää tallennuksen`() {
        val form = WorkEndForm(km = "900", minKm = 1000)
        assertTrue(form.tooSmall)
        assertFalse(form.canSave)
        assertTrue(WorkEndForm(km = "", minKm = 1000).canSave)
        assertTrue(WorkEndForm(km = "1000", minKm = 1000).canSave)
    }

    @Test
    fun `autonvaihdossa molemmat lukemat ovat valinnaisia mutta vanhan auton lukema ei saa alittaa aloitusta`() {
        assertTrue(SwitchCarForm("", "", "", minEndKm = 500).canSave)
        assertFalse(SwitchCarForm("400", "", "", minEndKm = 500).canSave)
        assertTrue(SwitchCarForm("600", "10", "XYZ-1", minEndKm = 500).canSave)
    }

    @Test
    fun `polttoainetta kysytään vain autolta, jonka polttoainetta ei tiedetä`() {
        val known = setOf("ABC-123")
        val start = WorkStartForm(km = "", plate = "abc123", prefillKm = null)
        assertFalse(start.asksFuel(known))
        assertFalse(start.withPlate("", emptyMap()).asksFuel(known))

        val unknown = start.withPlate("XYZ-9", emptyMap()).withFuel(FuelKind.ELECTRIC)
        assertTrue(unknown.asksFuel(known))
        assertEquals(FuelKind.ELECTRIC, unknown.fuelToSave(known))
        // Rekkarin vaihto tunnettuun autoon ei siirrä valintaa sille.
        assertNull(unknown.withPlate("ABC-123", emptyMap()).fuelToSave(known))
        // AdBlue ei ole auton polttoaine.
        assertNull(unknown.withFuel(FuelKind.ADBLUE).fuel)

        val switch = SwitchCarForm("", "", "XYZ-9", minEndKm = null).withFuel(FuelKind.GAS)
        assertTrue(switch.asksFuel(known))
        assertEquals(FuelKind.GAS, switch.fuelToSave(known))
        assertNull(switch.withNewPlate("ABC-123", emptyMap()).fuelToSave(known))
    }

    // ---- Auton vaihto lomakkeella päivittää esitäytetyn lukeman ----

    private val readings = mapOf("TES-598" to 475_290L, "TES-393" to 120_250L)

    @Test
    fun `aloituksen auton vaihto vaihtaa koskemattoman esitäytön uuden auton lukemaksi`() {
        val form = WorkStartForm(km = "475290", plate = "TES-598", prefillKm = 475_290)
        val switched = form.withPlate("TES-393", readings)
        assertEquals("120250", switched.km)
        // Ylityshuomautus vertaa uuden auton lukemaan.
        assertEquals(120_250L, switched.prefillKm)
        assertEquals(50L, switched.withKm("120300").overPrefillBy)
        // Kirjoitusasu ei haittaa: kenttään kirjoitettu tunnettu auto löytyy.
        assertEquals("120250", form.withPlate("tes393", readings).km)
        // Autolle ilman lukemaa kenttä tyhjenee, ja takaisin vaihto täyttää sen taas.
        val unknown = form.withPlate("XYZ-9", readings)
        assertEquals("", unknown.km)
        assertNull(unknown.prefillKm)
        assertEquals("475290", unknown.withPlate("TES-598", readings).km)
        // Ilman esitäyttöä alkanut lomake täyttyy chipistä.
        assertEquals("475290", WorkStartForm(km = "", plate = "", prefillKm = null).withPlate("TES-598", readings).km)
    }

    @Test
    fun `aloituksen itse kirjoitettu lukema säilyy auton vaihdossa`() {
        val form = WorkStartForm(km = "475290", plate = "TES-598", prefillKm = 475_290).withKm("475300")
        val switched = form.withPlate("TES-393", readings)
        assertEquals("475300", switched.km)
        assertEquals(475_290L, switched.prefillKm)
        // Tyhjennetty kenttä on käyttäjän valinta eikä täyty.
        assertEquals("", form.withKm("").withPlate("TES-393", readings).km)
        // Esitäytön käyttö palauttaa esitäytetyn arvon, ja auton vaihto saa taas vaihtaa sen.
        assertEquals("120250", form.usePrefill().withPlate("TES-393", readings).km)
    }

    @Test
    fun `autonvaihdon uuden auton valinta vaihtaa koskemattoman aloituslukeman`() {
        val form = SwitchCarForm("", "120250", "TES-393", minEndKm = 475_000, prefillStartKm = 120_250)
        val switched = form.withNewPlate("TES-598", readings)
        assertEquals("475290", switched.startKm)
        assertEquals(475_290L, switched.prefillStartKm)
        assertEquals("", form.withNewPlate("XYZ-9", readings).startKm)
        assertNull(form.withNewPlate("XYZ-9", readings).prefillStartKm)
        // Itse kirjoitettu lukema säilyy; vanhan auton lopetuslukema ei vaikuta.
        val typed = form.withStartKm("120300").withEndKm("475100")
        assertEquals("120300", typed.withNewPlate("TES-598", readings).startKm)
        assertEquals("475100", typed.withNewPlate("TES-598", readings).endKm)
        // Tänään-kortista avattu lomake ilman esitäyttöä täyttyy chipistä.
        assertEquals("475290", SwitchCarForm("", "", "", minEndKm = null).withNewPlate("TES-598", readings).startKm)
    }
}
