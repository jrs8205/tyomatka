package fi.tyomatka.ui

import fi.tyomatka.ui.history.decimalText
import fi.tyomatka.ui.history.editableDecimal
import fi.tyomatka.ui.history.filterDecimalInput
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Luvut, päivät ja kellonajat näytön kielen mukaan. Muut kielet kuin suomi ja ruotsi saavat englannin kuten tekstitkin. */
class FormatLocaleTest {
    private val original = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(original)
        DisplayLanguage.set(null)
    }

    private fun use(tag: String) = Locale.setDefault(Locale.forLanguageTag(tag))

    private fun day(year: Int, month: Int, dayOfMonth: Int) = LocalDate.of(year, month, dayOfMonth).toEpochDay()

    @Test
    fun `luvut seuraavat kieltä jonka android valitsi teksteille`() {
        // Kielilistalla [viro, suomi] tekstit tulevat suomeksi, vaikka oletuskieli on viro.
        use("et-EE")
        assertEquals("en-GB", appLocale().toLanguageTag())
        DisplayLanguage.set("fi")
        assertEquals("fi-FI", appLocale().toLanguageTag())
        assertEquals("7,8", formatDecimal(7.84, 1))
        DisplayLanguage.set("sv")
        assertEquals("sv-FI", appLocale().toLanguageTag())
    }

    @Test
    fun `desimaalit ja matka`() {
        use("fi-FI")
        assertEquals("7,8", formatDecimal(7.84, 1))
        assertEquals("12,4 km", formatKm(12_400))
        use("sv-FI")
        assertEquals("7,8", formatDecimal(7.84, 1))
        assertEquals("12,4 km", formatKm(12_400))
        use("en-GB")
        assertEquals("7.8", formatDecimal(7.84, 1))
        assertEquals("12.4 km", formatKm(12_400))
    }

    @Test
    fun `muu puhelimen kieli saa englannin`() {
        use("pl-PL")
        assertEquals("7.8", formatDecimal(7.84, 1))
        assertEquals("Fri 25 Sep 2026", formatShortDate(day(2026, 9, 25)))
        assertEquals("en-GB", appLocale().toLanguageTag())
    }

    @Test
    fun `kellonaika`() {
        use("fi-FI")
        assertEquals("15.00", formatMinutesOfDay(900))
        use("sv-FI")
        assertEquals("15.00", formatMinutesOfDay(900))
        use("en-GB")
        assertEquals("15:00", formatMinutesOfDay(900))
    }

    @Test
    fun `alle minuutin kesto`() {
        use("fi-FI")
        assertEquals("alle 1 min", formatDuration(20))
        use("sv-FI")
        assertEquals("under 1 min", formatDuration(20))
        use("en-GB")
        assertEquals("under 1 min", formatDuration(20))
        assertEquals("2 h 15 min", formatDuration(8_100))
    }

    @Test
    fun `kokonaisluku`() {
        use("fi-FI")
        assertEquals("1100", formatWhole(1_100))
        assertEquals("475 279", formatWhole(475_279))
        use("sv-FI")
        assertEquals("475 279", formatWhole(475_279))
        use("en-GB")
        assertEquals("1,100", formatWhole(1_100))
        assertEquals("475,279", formatWhole(475_279))
        assertEquals("-475,279", formatWhole(-475_279))
        assertEquals("999", formatWhole(999))
    }

    @Test
    fun `lyhyt päivämäärä`() {
        val date = day(2026, 9, 25)
        use("fi-FI")
        assertEquals("Pe 25.9.2026", formatShortDate(date))
        assertEquals("pe 25.9.2026", formatShortDate(date, capitalize = false))
        use("sv-FI")
        assertEquals("Fre 25.9.2026", formatShortDate(date))
        assertEquals("fre 25.9.2026", formatShortDate(date, capitalize = false))
        use("en-GB")
        assertEquals("Fri 25 Sep 2026", formatShortDate(date))
        assertEquals("Fri 25 Sep 2026", formatShortDate(date, capitalize = false))
    }

    @Test
    fun `päiväväli`() {
        use("sv-FI")
        assertEquals("1.–18.10.2026", formatDayRange(day(2026, 10, 1), day(2026, 10, 18)))
        use("en-GB")
        assertEquals("1–18 Oct 2026", formatDayRange(day(2026, 10, 1), day(2026, 10, 18)))
        assertEquals("1 Sep – 18 Oct 2026", formatDayRange(day(2026, 9, 1), day(2026, 10, 18)))
        assertEquals("28 Dec 2025 – 3 Jan 2026", formatDayRange(day(2025, 12, 28), day(2026, 1, 3)))
        assertEquals("16 Oct 2026", formatDayRange(day(2026, 10, 16), day(2026, 10, 16)))
    }

    @Test
    fun `yksittäinen päivä`() {
        use("fi-FI")
        assertEquals("25.9.2026", formatDate(LocalDate.of(2026, 9, 25)))
        use("en-GB")
        assertEquals("25 Sep 2026", formatDate(LocalDate.of(2026, 9, 25)))
    }

    @Test
    fun `kuukausi ja vuosi`() {
        val month = YearMonth.of(2026, 10)
        use("fi-FI")
        assertEquals("lokakuu 2026", formatMonth(month))
        assertEquals("Lokakuu 2026", formatMonth(month, capitalize = true))
        use("sv-FI")
        assertEquals("oktober 2026", formatMonth(month))
        assertEquals("Oktober 2026", formatMonth(month, capitalize = true))
        use("en-GB")
        assertEquals("October 2026", formatMonth(month))
    }

    @Test
    fun `lomakkeen desimaalierotin`() {
        use("fi-FI")
        assertEquals("1,50", decimalText(1.5, 2))
        assertEquals("1,5", editableDecimal(1.5))
        assertEquals("12,5", filterDecimalInput("12.5"))
        use("en-GB")
        assertEquals("1.50", decimalText(1.5, 2))
        assertEquals("1.5", editableDecimal(1.5))
        assertEquals("12.5", filterDecimalInput("12,5"))
    }

    @Test
    fun `epoch-kellonaika ja takaisinluku`() {
        val noonish = LocalDate.of(2026, 10, 31).atTime(14, 35).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        use("fi-FI")
        assertEquals("14.35", formatClock(noonish))
        assertEquals(14 * 60 + 35, parseClockMinutes(formatClock(noonish)))
        use("en-GB")
        assertEquals("14:35", formatClock(noonish))
        assertEquals(14 * 60 + 35, parseClockMinutes(formatClock(noonish)))
    }

    @Test
    fun `lähellä-listan matka englanniksi`() {
        use("en-GB")
        assertEquals("0.9 km", fi.tyomatka.ui.plan.nearby.NearbyText.distance(0.9))
        use("sv-FI")
        assertEquals("0,9 km", fi.tyomatka.ui.plan.nearby.NearbyText.distance(0.9))
    }

    private val labels = fi.tyomatka.core.poi.OpeningHoursText.Labels(
        days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        allDay = "Open 24 h",
        and = "and",
    )
    private val open: (Int, Array<out Any>) -> String = { _, args -> "Open ${args[0]}" }

    @Test
    fun `jäsentämätön aukiolo säilyy sellaisenaan englanniksi`() {
        use("en-GB")
        val raw = "Mo-Fr 08:00-17:00; PH off \"Closed 31.12.\""
        assertEquals(raw, fi.tyomatka.ui.poi.PoiCardText.hours(raw, labels, open))
        assertEquals(raw, fi.tyomatka.ui.poi.PoiCardText.hoursText(raw, labels))
    }

    @Test
    fun `muotoiltu aukiolo saa kielen kellonajan`() {
        use("en-GB")
        assertEquals("Open Mon–Fri 6:00–22:00", fi.tyomatka.ui.poi.PoiCardText.hours("Mo-Fr 06:00-22:00", labels, open))
        assertEquals("Mon–Fri 6:00–22:00", fi.tyomatka.ui.poi.PoiCardText.hoursText("Mo-Fr 06:00-22:00", labels))
        use("fi-FI")
        assertEquals("Mon–Fri 6.00–22.00", fi.tyomatka.ui.poi.PoiCardText.hoursText("Mo-Fr 06:00-22:00", labels))
    }

    @Test
    fun `aukioloaikojen kellonajat`() {
        use("fi-FI")
        assertEquals("ma–pe 6.00–22.00", localClockText("ma–pe 6.00–22.00"))
        assertEquals("21", localShortClock("21"))
        assertEquals("6.30", localShortClock("6.30"))
        use("sv-FI")
        assertEquals("21", localShortClock("21"))
        use("en-GB")
        assertEquals("Mon–Fri 6:00–22:00", localClockText("Mon–Fri 6.00–22.00"))
        assertEquals("21:00", localShortClock("21"))
        assertEquals("6:30", localShortClock("6.30"))
        assertEquals("24:00", localShortClock("24"))
    }

    @Test
    fun `valmis pisteellinen luku näytetään kielen erottimella`() {
        use("sv-FI")
        assertEquals("25,25", localDecimal("25.25"))
        use("en-GB")
        assertEquals("25.25", localDecimal("25.25"))
    }
}
