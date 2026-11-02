package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.ReportField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoursFormTest {
    @Test
    fun `arki, lauantai ja suljettu sunnuntai`() {
        val form = HoursForm(DayHours.Range("06:00", "18:00"), DayHours.Range("08:00", "16:00"), DayHours.Closed)
        assertEquals("Mo-Fr 06:00-18:00; Sa 08:00-16:00", form.toOsm())
        assertTrue(ReportField.HOURS_SHOP.accepts(form.toOsm()))
    }

    @Test
    fun `ympäri vuorokauden`() {
        assertEquals("24/7", HoursForm(DayHours.AllDay, DayHours.AllDay, DayHours.AllDay).toOsm())
        assertEquals("24/7", HoursForm(DayHours.Range("0", "24"), DayHours.Range("00:00", "24:00"), DayHours.AllDay).toOsm(), "kirjoitettu ympärivuorokautinen")
        assertEquals("Mo-Fr 00:00-24:00; Su 10:00-18:00", HoursForm(DayHours.AllDay, DayHours.Closed, DayHours.Range("10:00", "18:00")).toOsm())
    }

    @Test
    fun `samat peräkkäiset ryhmät yhdistetään`() {
        val same = DayHours.Range("07:00", "21:00")
        assertEquals("Mo-Su 07:00-21:00", HoursForm(same, same, same).toOsm())
        assertEquals("Mo-Sa 07:00-21:00", HoursForm(same, same, DayHours.Closed).toOsm())
        assertEquals("Mo-Fr 06:00-22:00; Sa-Su 08:00-20:00", HoursForm(DayHours.Range("06:00", "22:00"), DayHours.Range("08:00", "20:00"), DayHours.Range("08:00", "20:00")).toOsm())
    }

    @Test
    fun `kaikki suljettu tai keskeneräinen aika ei kelpaa`() {
        assertEquals("", HoursForm(DayHours.Closed, DayHours.Closed, DayHours.Closed).toOsm())
        assertEquals("", HoursForm(DayHours.Range("6", ""), DayHours.Closed, DayHours.Closed).toOsm())
    }

    @Test
    fun `kellonaika kirjoitetaan vapaasti`() {
        assertEquals("06:00", HoursForm.time("6"))
        assertEquals("06:30", HoursForm.time("6.30"))
        assertEquals("06:30", HoursForm.time("6:30"))
        // Desimaalinäppäimistö antaa suomeksi pilkun.
        assertEquals("06:30", HoursForm.time("6,30"))
        assertEquals("21:15", HoursForm.time("2115"))
        assertEquals("24:00", HoursForm.time("24"))
        assertNull(HoursForm.time("25"))
        assertNull(HoursForm.time("6.75"))
        assertNull(HoursForm.time(""))
        assertEquals("Mo-Fr 06:30-21:00", HoursForm(DayHours.Range("6.30", "21"), DayHours.Closed, DayHours.Closed).toOsm())
    }

    @Test
    fun `nykyinen aukiolo esitäyttää lomakkeen`() {
        val form = HoursForm.from("Mo-Fr 06:00-18:00; Sa 08:00-16:00")
        assertEquals(HoursForm(DayHours.Range("06:00", "18:00"), DayHours.Range("08:00", "16:00"), DayHours.Closed), form)
        assertEquals(HoursForm(DayHours.AllDay, DayHours.AllDay, DayHours.AllDay), HoursForm.from("24/7"))
        assertEquals(HoursForm(DayHours.Range("07:00", "21:00"), DayHours.Range("07:00", "21:00"), DayHours.Range("07:00", "21:00")), HoursForm.from("Mo-Su 07:00-21:00"))
        assertNull(HoursForm.from("Mo-Th 06:00-18:00; Fr 06:00-20:00"))
        assertNull(HoursForm.from("Mo-Fr 06:00-22:00; PH off"))
    }
}
