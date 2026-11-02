package fi.tyomatka.core.poi

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpeningHoursTextTest {
    @Test
    fun `ympäri vuorokauden`() {
        assertEquals("Auki 24 h", OpeningHoursText.format("24/7"))
        assertEquals("Auki 24 h", OpeningHoursText.format("Mo-Su 00:00-24:00"))
    }

    @Test
    fun `päiväryhmät suomeksi`() {
        assertEquals("ma–pe 6.30–19.00, la 8.00–16.00", OpeningHoursText.format("Mo-Fr 06:30-19:00; Sa 08:00-16:00"))
        assertEquals("ma–la 6.00–24.00, su 10.00–18.00", OpeningHoursText.format("Mo-Sa 06:00-24:00; Su 10:00-18:00"))
    }

    @Test
    fun `kertatuonnin useampi väli samana päivänä`() {
        assertEquals("ma–pe 6.00–10.00 ja 11.00–15.00", OpeningHoursText.format("Mo-Fr 06:00-10:00,11:00-15:00"))
    }

    @Test
    fun `muu OSM-aukiolo näytetään sellaisenaan`() {
        assertEquals("Mo-Fr 06:00-22:00; PH off", OpeningHoursText.format("Mo-Fr 06:00-22:00; PH off"))
        assertEquals("sunrise-sunset", OpeningHoursText.format("sunrise-sunset"))
    }

    @Test
    fun `kieli tulee sovelluksen teksteistä`() {
        val sv = OpeningHoursText.Labels(days = listOf("mån", "tis", "ons", "tor", "fre", "lör", "sön"), allDay = "Öppet dygnet runt", and = "och")
        assertEquals("mån–fre 6.30–19.00", OpeningHoursText.format("Mo-Fr 06:30-19:00", sv))
        assertEquals("Öppet dygnet runt", OpeningHoursText.format("24/7", sv))
    }

    @Test
    fun `editorin muoto on yksinkertainen`() {
        assertTrue(OpeningHoursText.isSimple("24/7"))
        assertTrue(OpeningHoursText.isSimple("Mo-Fr 06:00-18:00; Sa 08:00-16:00"))
        assertFalse(OpeningHoursText.isSimple("Mo-Fr 06:00-10:00,11:00-15:00"))
        assertFalse(OpeningHoursText.isSimple("Mo-Fr 06:00-22:00; PH off"))
        assertFalse(OpeningHoursText.isSimple("x".repeat(201)))
    }

    // Viikko alkaa maanantaista 26.10.2026.
    private fun at(day: DayOfWeek, hour: Int, minute: Int = 0) =
        LocalDate.of(2026, 10, 26).plusDays(day.ordinal.toLong()).atTime(hour, minute)

    private fun state(osm: String, day: DayOfWeek, hour: Int, minute: Int = 0) =
        OpeningHoursText.openState(osm, at(day, hour, minute))

    @Test
    fun `ympäri vuorokauden on aina auki`() {
        assertEquals(OpenState.AllDay, state("24/7", WEDNESDAY, 3))
        assertEquals(OpenState.AllDay, state("Mo-Su 00:00-24:00", SUNDAY, 23, 59))
        assertEquals(OpenState.AllDay, state(" 24/7 ", MONDAY, 0))
    }

    @Test
    fun `arkiaukiolo auki, illalla ja viikonloppuna kiinni`() {
        val osm = "Mo-Fr 06:00-21:00"
        assertEquals(OpenState.OpenUntil(LocalTime.of(21, 0), null), state(osm, FRIDAY, 12))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), MONDAY), state(osm, FRIDAY, 22))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), null), state(osm, THURSDAY, 22))
        // Sulkemishetkellä kiinni, avaushetkellä auki.
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), null), state(osm, THURSDAY, 21))
        assertEquals(OpenState.OpenUntil(LocalTime.of(21, 0), null), state(osm, THURSDAY, 6))
    }

    @Test
    fun `yön yli jatkuva väli`() {
        val osm = "Mo-Su 22:00-06:00"
        assertEquals(OpenState.OpenUntil(LocalTime.of(6, 0), null), state(osm, TUESDAY, 23, 30))
        assertEquals(OpenState.OpenUntil(LocalTime.of(6, 0), null), state(osm, TUESDAY, 3))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(22, 0), null), state(osm, TUESDAY, 12))
        // Maanantain aamuyö jatkuu sunnuntailta.
        assertEquals(OpenState.OpenUntil(LocalTime.of(6, 0), null), state(osm, MONDAY, 1))
    }

    @Test
    fun `keskiyön ylittävä jatko on yksi väli`() {
        assertEquals(OpenState.OpenUntil(LocalTime.of(2, 0), null), state("Fr 06:00-24:00; Sa 00:00-02:00", FRIDAY, 23))
    }

    @Test
    fun `keskiyöllä sulkeutuva on auki edellisen päivän loppuun`() {
        val osm = "Mo-Fr 00:00-24:00; Sa 06:00-22:00"
        assertEquals(OpenState.OpenUntil(LocalTime.MIDNIGHT, FRIDAY), state(osm, MONDAY, 12))
        assertEquals(OpenState.OpenUntil(LocalTime.MIDNIGHT, null), state(osm, FRIDAY, 12))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), null), state(osm, SATURDAY, 3))
        assertEquals(OpenState.OpenUntil(LocalTime.MIDNIGHT, null), state("Fr 06:00-24:00", FRIDAY, 12))
    }

    @Test
    fun `kaksi väliä päivässä`() {
        val osm = "Mo-Fr 06:30-11:00,12:00-19:00"
        assertEquals(OpenState.ClosedUntil(LocalTime.of(12, 0), null), state(osm, MONDAY, 11, 30))
        assertEquals(OpenState.OpenUntil(LocalTime.of(11, 0), null), state(osm, MONDAY, 7))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 30), null), state(osm, MONDAY, 5))
    }

    @Test
    fun `seuraava avaus yli vuorokauden päässä kertoo päivän`() {
        assertEquals(OpenState.ClosedUntil(LocalTime.of(8, 0), SATURDAY), state("Sa 08:00-16:00", MONDAY, 10))
        assertEquals(OpenState.ClosedUntil(LocalTime.MIDNIGHT, MONDAY), state("Mo 00:00-06:00", SATURDAY, 12))
        assertEquals(OpenState.ClosedUntil(LocalTime.MIDNIGHT, null), state("Mo 00:00-06:00", SUNDAY, 23))
        // Täsmälleen vuorokauden päässä päivä kerrotaan, jottei "aukeaa klo 6" näytä tarkoittavan nyt.
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), TUESDAY), state("Tu 06:00-07:00", MONDAY, 6))
    }

    @Test
    fun `sulkeutuminen täsmälleen vuorokauden päässä kertoo päivän`() {
        assertEquals(OpenState.OpenUntil(LocalTime.of(12, 0), TUESDAY), state("Mo 12:00-12:00", MONDAY, 12))
        assertEquals(OpenState.OpenUntil(LocalTime.of(12, 0), null), state("Mo 12:00-12:00", MONDAY, 12, 1))
    }

    @Test
    fun `myöhempi sääntö korvaa päivän aiemman säännön`() {
        val osm = "Mo-Su 06:00-22:00; Sa 08:00-20:00"
        assertEquals(OpenState.ClosedUntil(LocalTime.of(8, 0), null), state(osm, SATURDAY, 7))
        assertEquals(OpenState.OpenUntil(LocalTime.of(20, 0), null), state(osm, SATURDAY, 12))
        assertEquals(OpenState.OpenUntil(LocalTime.of(22, 0), null), state(osm, FRIDAY, 12))
    }

    @Test
    fun `muu kuin yksinkertainen muoto ei kerro tilaa`() {
        assertNull(state("Mo-Fr 06:00-22:00; PH off", MONDAY, 12))
        assertNull(state("sunrise-sunset", MONDAY, 12))
        assertNull(state("", MONDAY, 12))
    }

    @Test
    fun `yön yli jatkuva väli ja saman päivän myöhempi sääntö ei kerro tilaa, koska OSM tulkitsee sen toisin`() {
        // OSM: myöhempi sääntö nollaa päivän myös edellisen päivän yön yli jatkuvalta osalta.
        assertNull(state("Sa 16:00-03:00; Su 16:00-23:00", SUNDAY, 1))
        assertNull(state("Mo-Fr 06:00-02:00; Sa 08:00-02:00; Su 10:00-22:00", SATURDAY, 1))
        assertNull(state("Mo-Th 06:00-22:00; Fr-Sa 06:00-02:00; Su 08:00-22:00", SUNDAY, 1))
        assertNull(state("Mo-Su 22:00-06:00; Sa 08:00-20:00", SUNDAY, 3))
        assertNull(state("Mo-Su 06:00-02:00; Su 08:00-24:00", MONDAY, 1))
        assertNull(state("Mo-Su 06:00-02:00; Su 08:00-24:00", SUNDAY, 20))
        // Keskiyöhön päättyvä väli ei jatku seuraavaan päivään, joten myöhempi sääntö on yksiselitteinen.
        assertEquals(OpenState.OpenUntil(LocalTime.of(20, 0), null), state("Mo-Su 06:00-00:00; Sa 08:00-20:00", SATURDAY, 12))
    }

    @Test
    fun `alkuaika 24 ei ole aukiolo`() {
        assertNull(state("Fr 24:00-00:00", WEDNESDAY, 12))
        assertNull(state("Mo 24:00-02:00", TUESDAY, 1))
    }

    @Test
    fun `viikon vaihteen yli kiertävä päiväväli`() {
        assertEquals(OpenState.OpenUntil(LocalTime.of(16, 0), null), state("Sa-Mo 08:00-16:00", MONDAY, 12))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(8, 0), SATURDAY), state("Sa-Mo 08:00-16:00", TUESDAY, 12))
    }

    @Test
    fun `sekunnit eivät siirrä minuuttia`() {
        val osm = "Mo-Fr 06:00-21:00"
        assertEquals(OpenState.OpenUntil(LocalTime.of(21, 0), null), OpeningHoursText.openState(osm, at(THURSDAY, 20, 59).withSecond(59)))
        assertEquals(OpenState.ClosedUntil(LocalTime.of(6, 0), null), OpeningHoursText.openState(osm, at(THURSDAY, 5, 59).withSecond(59)))
    }

    @Test
    fun `koko vuorokauden välit ja minuutin tauko viikossa`() {
        assertEquals(OpenState.AllDay, state("Mo-Su 12:00-12:00", WEDNESDAY, 9))
        assertEquals(OpenState.OpenUntil(LocalTime.of(23, 59), SUNDAY), state("Mo-Sa 00:00-24:00; Su 00:00-23:59", MONDAY, 0))
    }

    @Test
    fun `lyhyet kellonajat`() {
        assertEquals("21", OpeningHoursText.closesShort(LocalTime.of(21, 0)))
        assertEquals("6.30", OpeningHoursText.closesShort(LocalTime.of(6, 30)))
        assertEquals("24", OpeningHoursText.closesShort(LocalTime.MIDNIGHT))
        assertEquals("6", OpeningHoursText.opensShort(LocalTime.of(6, 0)))
        assertEquals("6.30", OpeningHoursText.opensShort(LocalTime.of(6, 30)))
        assertEquals("0", OpeningHoursText.opensShort(LocalTime.MIDNIGHT))
    }
}
