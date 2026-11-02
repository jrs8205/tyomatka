package fi.tyomatka.core.poi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Samat säännöt kuin palvelimen `REPORT_FIELDS` (server/src/reports.ts). */
class ReportFieldTest {
    @Test
    fun `avaimet vastaavat palvelimen kenttiä`() {
        assertEquals(ReportField.HOURS_STATION, ReportField.of("hours:station"))
        // Palvelin säilyttää vanhat hintailmoitukset, mutta sovellus ei lue niitä.
        assertNull(ReportField.of("price:adblue"))
        assertNull(ReportField.of("phone"))
        assertEquals(19, ReportField.entries.size)
    }

    @Test
    fun `korkeus ja paino pisteellä, enintään kaksi desimaalia`() {
        assertTrue(ReportField.MAX_HEIGHT.accepts("4.15"))
        assertTrue(ReportField.MAX_WEIGHT.accepts("99"))
        assertFalse(ReportField.MAX_HEIGHT.accepts("1e9"))
        assertFalse(ReportField.MAX_HEIGHT.accepts("4,15"))
        assertFalse(ReportField.MAX_WEIGHT.accepts("0.4"))
        assertFalse(ReportField.MAX_WEIGHT.accepts("4.155"))
    }

    @Test
    fun `aukiolo vain editorin muodossa`() {
        assertTrue(ReportField.HOURS_SHOP.accepts("Mo-Fr 06:00-22:00"))
        assertTrue(ReportField.HOURS_STATION.accepts("24/7"))
        assertTrue(ReportField.HOURS_LUNCH.accepts("Mo-Fr 10:30-14:00; Sa 11:00-14:00"))
        assertFalse(ReportField.HOURS_SHOP.accepts("auki"))
        assertFalse(ReportField.HOURS_SHOP.accepts("Mo-Fr 06:00-22:00; PH off"))
        assertFalse(ReportField.HOURS_SHOP.accepts("Mo-Fr 25:00-26:00"))
    }

    @Test
    fun `kyllä ei, valinnat ja polttoaineet`() {
        assertTrue(ReportField.CLOSED.accepts("yes"))
        assertTrue(ReportField.LUNCH.accepts("no"))
        assertFalse(ReportField.SHOP.accepts("maybe"))
        assertTrue(ReportField.ADBLUE.accepts("canister"))
        assertFalse(ReportField.ADBLUE.accepts("yes"))
        assertTrue(ReportField.TRUCK_LENGTH.accepts("25.25"))
        assertFalse(ReportField.TRUCK_LENGTH.accepts("20"))
        assertTrue(ReportField.FUELS.accepts("diesel;hvo"))
        assertTrue(ReportField.FUELS.accepts("none"))
        assertFalse(ReportField.FUELS.accepts("diesel;diesel"))
        assertFalse(ReportField.FUELS.accepts(""))
    }
}
