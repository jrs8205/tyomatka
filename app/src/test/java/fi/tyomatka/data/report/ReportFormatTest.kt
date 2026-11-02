package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportFormatTest {
    private val nb = '\u00A0'
    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")
    private val fi = ReportFormat(ReportLocale(ReportLanguage.FI, finnishNumerals = true), zone)
    private val svSe = ReportFormat(ReportLocale(ReportLanguage.SV, finnishNumerals = false), zone)
    private val svFi = ReportFormat(ReportLocale(ReportLanguage.SV, finnishNumerals = true), zone)
    private val en = ReportFormat(ReportLocale(ReportLanguage.EN, finnishNumerals = false), zone)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun at(h: Int, min: Int) = ZonedDateTime.of(2026, 10, 16, h, min, 0, 0, zone).toEpochSecond()

    @Test
    fun `päivämäärä kielen mukaan sitovin välein`() {
        val d = day(2026, 10, 16)
        assertEquals("Pe${nb}16.10.2026", fi.date(d))
        assertEquals("Fre${nb}16${nb}okt.${nb}2026", svSe.date(d))
        assertEquals("Fre${nb}16.10.2026", svFi.date(d))
        assertEquals("Fri${nb}16${nb}Oct${nb}2026", en.date(d))
        assertEquals("pe${nb}16.10.2026", fi.date(d, capitalize = false))
    }

    @Test
    fun `lyhyt päivämäärä ilman viikonpäivää`() {
        val d = day(2026, 10, 16)
        assertEquals("16.10.2026", fi.shortDate(d))
        assertEquals("16${nb}Oct${nb}2026", en.shortDate(d))
        assertEquals("16${nb}okt.${nb}2026", svSe.shortDate(d))
        assertEquals("16.10.2026", svFi.shortDate(d))
    }

    @Test
    fun `kellonaikaväli kielen ja alueen mukaan`() {
        assertEquals("klo${nb}7.01–14.20", fi.clockRange(at(7, 1), at(14, 20)))
        assertEquals("kl.${nb}07:01–14:20", svSe.clockRange(at(7, 1), at(14, 20)))
        assertEquals("kl.${nb}7.01–14.20", svFi.clockRange(at(7, 1), at(14, 20)))
        assertEquals("07:01–14:20", en.clockRange(at(7, 1), at(14, 20)))
        assertEquals("9.40", fi.clock(at(9, 40)))
        assertEquals("klo${nb}7.01–", fi.clockFrom(at(7, 1)))
        assertEquals("07:01–", en.clockFrom(at(7, 1)))
        assertEquals("09:40", en.clock(at(9, 40)))
    }

    @Test
    fun `kesto tunteina ja minuutteina`() {
        assertEquals("7${nb}h${nb}19${nb}min", fi.duration(7 * 3600 + 19 * 60L))
        assertEquals("35${nb}min", en.duration(35 * 60L))
        assertEquals("alle${nb}1${nb}min", fi.duration(20))
        assertEquals("under${nb}1${nb}min", en.duration(20))
    }

    @Test
    fun `kokonaisluvut suomeksi ja ruotsiksi kymmenestätuhannesta alkaen välein, englanniksi pilkuin`() {
        assertEquals("1234", fi.whole(1234))
        assertEquals("10${nb}000", fi.whole(10_000))
        assertEquals("475${nb}279", svSe.whole(475_279))
        assertEquals("1,234", en.whole(1234))
        assertEquals("10,000", en.whole(10_000))
        assertEquals("209${nb}km", fi.km(209))
    }

    @Test
    fun `desimaalit ja rahat kielen mukaan`() {
        assertEquals("14,1", fi.decimal(14.06, 1))
        assertEquals("14.1", en.decimal(14.06, 1))
        assertEquals("75,45${nb}€", fi.money(75.45, "EUR"))
        assertEquals("€75.45", en.money(75.45, "EUR"))
        assertEquals("512,00${nb}SEK", svSe.money(512.0, "SEK"))
        assertEquals("SEK${nb}512.00", en.money(512.0, "SEK"))
        assertEquals("10${nb}234,50${nb}€", fi.money(10_234.5, "EUR"))
        assertEquals("€1,534.13", en.money(1534.13, "EUR"))
        assertEquals("45,20${nb}€ + 512,00${nb}SEK", fi.moneyByCurrency(linkedMapOf("EUR" to 45.2, "SEK" to 512.0, "NOK" to 0.0)))
        assertEquals("–", fi.moneyByCurrency(emptyMap()))
    }

    @Test
    fun `määrät, yksikköhinnat ja kulutus yhden yksikkömuotoilijan kautta`() {
        assertEquals("31,2${nb}l", fi.amount(31.2, FuelKind.DIESEL))
        assertEquals("45.0${nb}kWh", en.amount(45.0, FuelKind.ELECTRIC))
        assertEquals("16,9${nb}kg", fi.amount(16.9, FuelKind.GAS))
        assertEquals("2,416${nb}€/l", fi.unitPrice(2.416, "EUR", FuelKind.DIESEL))
        assertEquals("€0.320/kWh", en.unitPrice(0.32, "EUR", FuelKind.ELECTRIC))
        assertEquals("14,1${nb}l/100${nb}km", fi.consumption(14.1, FuelKind.DIESEL))
        assertEquals("2,1${nb}l/1000${nb}km", fi.consumption(2.1, FuelKind.ADBLUE))
        assertEquals("0,34${nb}€/km", fi.costPerKm(0.34, "EUR"))
        assertEquals("€0.34/km", en.costPerKm(0.34, "EUR"))
    }

    @Test
    fun `mittarialue aina välein, sitova väli ennen viivaa`() {
        assertEquals("475${nb}429${nb}– 475${nb}658${nb}km", fi.odometerRange(475_429, 475_658))
        assertEquals("475,429${nb}– 475,658${nb}km", en.odometerRange(475_429, 475_658))
    }

    @Test
    fun `päivämääräalue Kielitoimiston ja kielen mukaan`() {
        assertEquals("1.9.–18.10.2026", fi.dayRange(day(2026, 9, 1), day(2026, 10, 18)))
        assertEquals("12.–16.10.2026", fi.dayRange(day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("28.12.2025–3.1.2026", fi.dayRange(day(2025, 12, 28), day(2026, 1, 3)))
        assertEquals("1${nb}sep.${nb}– 18${nb}okt.${nb}2026", svSe.dayRange(day(2026, 9, 1), day(2026, 10, 18)))
        assertEquals("12.–16.10.2026", svFi.dayRange(day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("12–16${nb}Oct${nb}2026", en.dayRange(day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("28${nb}Dec${nb}2025${nb}– 3${nb}Jan${nb}2026", en.dayRange(day(2025, 12, 28), day(2026, 1, 3)))
        assertEquals("16.10.2026", fi.dayRange(day(2026, 10, 16), day(2026, 10, 16)))
    }

    @Test
    fun `viikon ja kuukauden otsikot`() {
        assertEquals("Viikko${nb}42 · 12.–16.10.2026", fi.weekTitle(42, day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("Week${nb}42 · 12–16${nb}Oct${nb}2026", en.weekTitle(42, day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("Vecka${nb}42 · 12–16${nb}okt.${nb}2026", svSe.weekTitle(42, day(2026, 10, 12), day(2026, 10, 16)))
        assertEquals("Lokakuu${nb}2026", fi.monthTitle(2026, 10))
        assertEquals("Oktober${nb}2026", svSe.monthTitle(2026, 10))
        assertEquals("October${nb}2026", en.monthTitle(2026, 10))
    }

    @Test
    fun `raportin kieli sovelluksen kielestä`() {
        assertEquals(ReportLocale(ReportLanguage.FI, finnishNumerals = true), reportLocale(Locale("fi", "FI")))
        assertEquals(ReportLocale(ReportLanguage.SV, finnishNumerals = true), reportLocale(Locale("sv", "FI")))
        assertEquals(ReportLocale(ReportLanguage.SV, finnishNumerals = false), reportLocale(Locale("sv", "SE")))
        assertEquals(ReportLocale(ReportLanguage.EN, finnishNumerals = false), reportLocale(Locale("en", "GB")))
        assertEquals(ReportLocale(ReportLanguage.EN, finnishNumerals = false), reportLocale(Locale("de", "DE")))
    }
}
