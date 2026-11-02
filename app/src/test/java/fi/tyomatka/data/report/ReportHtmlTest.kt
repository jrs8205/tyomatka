package fi.tyomatka.data.report

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raportin HTML merkkijonona: rakenne, sitovat lohkot, haku, kuvat ja escapointi ilman selainta. */
class ReportHtmlTest {
    private val nb = '\u00A0'
    private val zone = ZoneId.of("Europe/Helsinki")
    private val october = ExportPeriod.Month(YearMonth.of(2026, 10))
    private val now = LocalDate.of(2026, 10, 18).atTime(9, 40).atZone(zone).toEpochSecond()
    private val fi = ReportLocale(ReportLanguage.FI, finnishNumerals = true)
    private val sv = ReportLocale(ReportLanguage.SV, finnishNumerals = false)
    private val en = ReportLocale(ReportLanguage.EN, finnishNumerals = false)
    private var nextId = 1L
    private val routeDays = mutableListOf<RouteDayEntity>()
    private val segments = mutableListOf<WorkSegmentEntity>()
    private val fillups = mutableListOf<FuelFillupEntity>()
    private val photos = mutableListOf<FuelPhotoEntity>()
    private val cars = mutableListOf(
        CarEntity("ABC-123", FuelKind.DIESEL, null),
        CarEntity("EVO-456", FuelKind.ELECTRIC, null),
        CarEntity("TES-789", FuelKind.GAS, null),
    )

    private fun sec(m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDate.of(2026, m, d).atTime(h, min).atZone(zone).toEpochSecond()

    /** Työpäivä klo 7.01–14.20. */
    private fun work(m: Int, d: Int, plate: String, fromKm: Long, toKm: Long) {
        val routeDay = RouteDayEntity(id = nextId++, dateEpochDay = LocalDate.of(2026, m, d).toEpochDay())
        routeDays += routeDay
        segments += WorkSegmentEntity(
            id = nextId++,
            routeDayId = routeDay.id,
            startEpochSec = sec(m, d, 7, 1),
            endEpochSec = sec(m, d, 14, 20),
            odometerStartKm = fromKm,
            odometerEndKm = toKm,
            licensePlate = plate,
        )
    }

    private fun fill(m: Int, d: Int, plate: String, kind: FuelKind, km: Long, amount: Double, cost: Double) =
        FuelFillupEntity(
            id = nextId++,
            epochSec = sec(m, d, 12),
            licensePlate = plate,
            odometerKm = km,
            liters = amount,
            totalCost = cost,
            pricePerLiter = cost / amount,
            kind = kind,
            currency = "EUR",
        ).also { fillups += it }

    private fun photo(fillup: FuelFillupEntity, name: String) =
        FuelPhotoEntity(id = nextId++, fillupId = fillup.id, fileName = name, addedAtEpochSec = fillup.epochSec)
            .also { photos += it }

    /** Korvike: pienoiskuva ja täysikokoinen kuva tiedoston nimen mukaan; puuttuva nimi = null. */
    private class FakePhotos(private val thumbnails: Set<String>, private val files: Set<String>) : ReportPhotos {
        override fun thumbnail(photo: FuelPhotoEntity) = if (photo.fileName in thumbnails) byteArrayOf(1, 2, 3) else null
        override fun open(photo: FuelPhotoEntity) =
            if (photo.fileName in files) ByteArrayInputStream(byteArrayOf(4, 5, 6)) else null
    }

    private val allPhotos get() = photos.map { it.fileName }.toSet()

    @Test
    fun `tyhjän jakson kirjausotsikko seuraa jakson autojen käyttövoimaa`() {
        work(10, 15, "EVO-456", 23_400, 23_521)
        val html = render()
        assertTrue("Lataukset" in h2Titles(html), h2Titles(html).toString())
        assertFalse("Tankkaukset" in h2Titles(html))
    }

    @Test
    fun `tyhjän jakson kirjausotsikko ilman autoja on tankkaukset`() {
        assertTrue("Tankkaukset" in h2Titles(render()))
    }

    @Test
    fun `päivän ja kirjauksen lisätiedot näkyvät raportissa escapoituina ja kuuluvat hakusanoihin`() {
        october()
        val day15 = routeDays.indexOfFirst { it.dateEpochDay == LocalDate.of(2026, 10, 15).toEpochDay() }
        routeDays[day15] = routeDays[day15].copy(note = "Latauspiste rikki\nkorjattu 16.10.")
        val charge = fillups.indexOfFirst { it.kind == FuelKind.ELECTRIC }
        fillups[charge] = fillups[charge].copy(note = "<script>alert(1)</script> & \"kuitti\"")

        val html = render()

        assertTrue("<p class=\"memo\">Latauspiste rikki\nkorjattu 16.10.</p>" in html, "päivän teksti rivinvaihtoineen")
        assertTrue("<p class=\"memo\">&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;kuitti&quot;</p>" in html, "kirjauksen teksti escapoituna")
        assertFalse("<script>alert(1)" in html)
        assertTrue("<td class=\"memo\">&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;kuitti&quot;</td>" in html, "taulukon sarake")
        assertEquals(10, Regex("<th scope=\"col\"").findAll(html).count())
        val dayCard = Regex("<li class=\"card day\"[^>]*data-search=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(dayCard.any { "latauspiste rikki korjattu 16.10." in it }, "päivän hakusanat: $dayCard")
        assertTrue(dayCard.any { "alert(1)" in it }, "kirjauksen hakusanat päivällä: $dayCard")
        val entryCard = Regex("<li class=\"card entry\"[^>]*data-search=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(entryCard.any { "kuitti" in it }, "kirjauksen hakusanat: $entryCard")
    }

    @Test
    fun `ilman lisätietoja raportissa ei ole memo-kappaleita mutta sarake on`() {
        october()
        val html = render()
        assertFalse("<p class=\"memo\">" in html)
        assertTrue("<td class=\"memo\"></td>" in html)
    }

    private fun render(
        period: ExportPeriod = october,
        locale: ReportLocale = fi,
        reportPhotos: ReportPhotos = FakePhotos(allPhotos, allPhotos),
        transferJson: String = """{"version":6}""",
    ): String {
        val model = reportModel(period, segments, routeDays, fillups, photos.groupBy { it.fillupId }, cars, zone)
        val out = ByteArrayOutputStream()
        writeReport(out, model, reportStrings(locale.language), ReportFormat(locale, zone), reportPhotos, transferJson, now)
        return out.toString(Charsets.UTF_8.name())
    }

    /** Lokakuu: kaasuauton täyttö, sähköauton lataus ja dieselauton tankkaus kahdella kuvalla. */
    private fun october() {
        work(10, 14, "TES-789", 152_300, 152_510)
        work(10, 15, "EVO-456", 23_400, 23_521)
        work(10, 16, "ABC-123", 475_429, 475_658)
        fill(10, 14, "TES-789", FuelKind.GAS, 152_510, 16.9, 30.42)
        fill(10, 15, "EVO-456", FuelKind.ELECTRIC, 23_521, 30.0, 9.6)
        val diesel = fill(10, 16, "ABC-123", FuelKind.DIESEL, 475_658, 31.2, 75.45)
        photo(diesel, "kuitti")
        photo(diesel, "mittari")
    }

    private fun h2Titles(html: String): List<String> = Regex("<h2[^>]*>(.*?)</h2>").findAll(html).map {
        it.groupValues[1].replace(Regex("<span class=\"count\"[^>]*>.*?</span>"), "").replace(Regex("<[^>]+>"), "").trim()
    }.toList()

    private fun dayCard(html: String, date: String): String =
        Regex("<li class=\"card day\".*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.value }.first { date in it }

    private fun photoLineIds(html: String) =
        html.lines().filter { it.startsWith(PHOTO_LINE) }.map { it.removePrefix(PHOTO_LINE).substringBefore('"') }

    private fun thumbIds(html: String) = Regex("data-photo=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `rakenne - kieli, yksi h1, osiot järjestyksessä ja maamerkit`() {
        october()
        val html = render()
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("<html lang=\"fi\"" in html)
        assertEquals(1, Regex("<h1[ >]").findAll(html).count())
        assertEquals(
            listOf(
                "Yhteenveto", "Päivät", "Viikot, kuukaudet ja vuodet", "Tankkaukset, lataukset ja täytöt", "Kuvat",
                "Selitteet", "Varmuuskopio",
            ),
            h2Titles(html),
        )
        listOf("<header", "<search", "<main", "<nav").forEach { assertTrue(it in html, it) }
        assertTrue("<html lang=\"sv\"" in render(locale = sv))
        val english = render(locale = en)
        assertTrue("<html lang=\"en\"" in english)
        assertEquals("Summary", h2Titles(english).first())
    }

    @Test
    fun `ilman skriptiä kaikki päivät, jaksolistat ja kirjaukset ovat sivulla`() {
        // 40 työpäivää: enemmän kuin skriptin ensimmäinen 31 päivän erä.
        val first = LocalDate.of(2026, 9, 1)
        for (i in 0L until 40L) {
            val d = first.plusDays(i)
            work(d.monthValue, d.dayOfMonth, "ABC-123", 1000 + i * 100, 1100 + i * 100)
        }
        for (i in 0 until 5) fill(9, 2 + i * 7, "ABC-123", FuelKind.DIESEL, 1300L + i * 700, 40.0, 90.0)
        val html = render(period = ExportPeriod.Range(first, first.plusDays(39)))
        assertEquals(40, Regex("<li class=\"card day\"").findAll(html).count())
        listOf("p-weeks", "p-months", "p-years").forEach { assertTrue("id=\"$it\"" in html, it) }
        assertEquals(5, Regex("<li class=\"card entry\"").findAll(html).count())
        assertEquals(5, Regex("<tr id=\"r-").findAll(html).count())
        assertFalse(Regex("<section[^>]*hidden").containsMatchIn(html))
        assertFalse(Regex("<li class=\"card[^>]*hidden").containsMatchIn(html))
    }

    @Test
    fun `ei ulkoisia osoitteita`() {
        october()
        val html = render()
        val urls = Regex("(?:src|href)=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(it.startsWith("data:") || it.startsWith("#"), it) }
        assertFalse(html.contains("http", ignoreCase = true))
    }

    @Test
    fun `sitovat lohkot - mittarialue, rekisterinumero, raha, kellonajat ja kesto`() {
        october()
        val html = render()
        assertTrue("475${nb}429${nb}– 475${nb}658${nb}km" in html)
        assertTrue(Regex("class=\"nw\">ABC-123<").containsMatchIn(html))
        assertTrue("75,45${nb}€" in html)
        assertTrue("klo${nb}7.01–14.20" in html)
        assertTrue("7${nb}h${nb}19${nb}min" in html)
    }

    @Test
    fun `päivän rivi ja kirjausosion otsikko seuraavat käyttövoimaa`() {
        october()
        val html = render()
        assertTrue("Täytetty" in dayCard(html, "Ke${nb}14.10.2026"))
        assertTrue("Ladattu" in dayCard(html, "To${nb}15.10.2026"))
        assertTrue("Tankattu" in dayCard(html, "Pe${nb}16.10.2026"))
        val english = render(locale = en)
        assertTrue("Fill-ups, charges and refills" in h2Titles(english))
        assertTrue("Charged" in dayCard(english, "Thu${nb}15${nb}Oct${nb}2026"))
    }

    @Test
    fun `hakuteksti sisältää numeromuotoiset päivämäärät kaikilla kielillä`() {
        october()
        listOf(fi, sv, en).forEach { locale ->
            val html = render(locale = locale)
            val searches = Regex("<(?:li|tr) [^>]*data-search=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
            // Kolme päiväkorttia, kolme kirjauskorttia ja kolme taulukon riviä.
            assertEquals(9, searches.size, locale.toString())
            val diesel = searches.filter { "abc-123" in it }
            assertEquals(3, diesel.size, locale.toString())
            diesel.forEach { assertTrue("16.10.2026" in it && "2026-10-16" in it, it) }
        }
    }

    @Test
    fun `täysikokoiset kuvat ovat omilla riveillään piilossa ja pienoiskuvat erikseen`() {
        october()
        val html = render()
        val lines = html.lines()
        val full = lines.filter { it.startsWith(PHOTO_LINE) }
        assertEquals(2, full.size)
        val template = html.substringAfter("<template id=\"full-photos\">").substringBefore("</template>")
        full.forEach { assertTrue(it in template) }
        val thumbs = lines.filter { it.startsWith(THUMB_LINE) }
        assertEquals(2, thumbs.size)
        thumbs.forEach { assertEquals(BackupLine.THUMBNAIL, backupLine(it)) }
        full.forEach { assertEquals(BackupLine.PHOTO, backupLine(it)) }
        assertEquals(BackupLine.TEXT, backupLine("<li><button class=\"thumb\" type=\"button\">"))
        thumbs.forEach { assertFalse(it in template) }
        // Järjestys ja tunnisteet: kirjausten järjestys, kuvat lisäysjärjestyksessä.
        assertEquals(photos.map { it.backupId }, photoLineIds(html))
        assertEquals(photoLineIds(html), thumbIds(html))
        // Napin nimi alkaa näkyvällä tekstillä (WCAG 2.5.3).
        assertTrue(Regex("<button class=\"photo-btn[^\"]*\"[^>]*aria-label=\"2${nb}kuvaa: Pe${nb}16.10.2026").containsMatchIn(html))
        assertTrue(">2${nb}kuvaa</button>" in html)
    }

    @Test
    fun `leveä taulukko on vierityssäiliössä ja kuvanäkymällä on tilailmoitus`() {
        october()
        val html = render()
        // Säiliön nimi eroaa osion nimestä (maamerkit yksilöllisiä).
        assertTrue("<div class=\"table-scroll\" role=\"region\" tabindex=\"0\" aria-label=\"Tankkaukset, lataukset ja täytöt: taulukko\"><table" in html)
        assertTrue("id=\"lbStatus\"" in html)
        // Gallerian rekisterinumero saa rivittyä kapeassa ruudussa, ettei pitkä numero leikkaudu.
        assertTrue("<span class=\"plate\">ABC-123</span>" in html)
    }

    @Test
    fun `päiväkortti näyttää tankatun auton, kun päivällä on useampi auto tai ei työjaksoa`() {
        work(10, 14, "ABC-123", 1000, 1100)
        fill(10, 14, "XYZ-789", FuelKind.DIESEL, 5000, 30.0, 60.0)
        fill(10, 15, "ABC-123", FuelKind.DIESEL, 1150, 40.0, 80.0)
        val html = render()
        val mixed = dayCard(html, "Ke${nb}14.10.2026")
        assertTrue("<span class=\"nw\">XYZ-789</span>" in mixed, mixed)
        assertTrue("2${nb}autoa" in mixed, mixed)
        val fuelOnly = dayCard(html, "To${nb}15.10.2026")
        assertTrue("<span class=\"nw\">ABC-123</span>" in fuelOnly, fuelOnly)
    }

    @Test
    fun `pienoiskuvan puuttuessa paikkamerkki ja täysikokoinen kuva silti`() {
        october()
        val html = render(reportPhotos = FakePhotos(thumbnails = setOf("mittari"), files = allPhotos))
        assertEquals(1, html.lines().count { it.startsWith("<img class=\"tn\"") })
        assertTrue("class=\"tn ph\"" in html)
        assertTrue("Esikatselua ei voitu tehdä" in html)
        assertEquals(2, photoLineIds(html).size)
    }

    @Test
    fun `kesken viennin kadonnut kuva jää pois kokonaan ja vienti valmistuu`() {
        october()
        val html = render(reportPhotos = FakePhotos(allPhotos, files = setOf("mittari")))
        val kept = photos.single { it.fileName == "mittari" }.backupId
        assertEquals(listOf(kept), photoLineIds(html))
        assertEquals(listOf(kept), thumbIds(html))
        assertTrue(">1${nb}kuva</button>" in html)
        assertTrue("id=\"tyomatka-data\"" in html)
        assertTrue(html.trimEnd().endsWith("</script></body></html>"))
    }

    @Test
    fun `JSON-lohkon kulmasulkeet koodataan`() {
        october()
        val html = render(transferJson = """{"version":6,"plate":"</script><b>"}""")
        val data = html.substringAfter("id=\"tyomatka-data\">")
        assertTrue(data.startsWith("""{"version":6,"plate":"\u003c/script>\u003cb>"}</script>"""), data.take(80))
    }

    @Test
    fun `rekisterinumero escapoidaan kaikkialla`() {
        val plate = "A<B\"C"
        work(10, 16, plate, 1000, 1100)
        val entry = fill(10, 16, plate, FuelKind.DIESEL, 1100, 40.0, 90.0)
        photo(entry, "kuitti")
        val html = render()
        assertFalse("A<B" in html)
        assertFalse("B\"C" in html)
        assertTrue("A&lt;B&quot;C" in html)
        assertTrue("a&lt;b&quot;c" in html)
    }

    @Test
    fun `tyhjä jakso näyttää kaikki osiot tyhjän tilan teksteineen`() {
        october()
        val html = render(period = ExportPeriod.Month(YearMonth.of(2026, 11)))
        assertEquals(7, h2Titles(html).size)
        listOf("Ei työpäiviä.", "Ei kirjauksia.", "Ei kuvia.").forEach { assertTrue(it in html, it) }
        assertEquals(0, Regex("<li class=\"card (?:day|entry)\"").findAll(html).count())
        assertTrue(photoLineIds(html).isEmpty())
    }

    @Test
    fun `varmuuskopio-osio kertoo jakson tai koko varmuuskopion`() {
        october()
        assertTrue("varmuuskopio jakson 1.–31.10.2026 kirjauksista" in render())
        val full = render(period = ExportPeriod.Full)
        assertTrue("Tämä tiedosto on koko varmuuskopio" in full)
        assertFalse("varmuuskopio jakson" in full)
    }

    @Test
    fun `otsikkorivi kertoo jakson ja vientihetken`() {
        october()
        assertTrue("Lokakuu${nb}2026</span> · viety su${nb}18.10.2026 klo${nb}9.40" in render())
        assertTrue("October${nb}2026</span> · exported Sun${nb}18${nb}Oct${nb}2026 at${nb}09:40" in render(locale = en))
    }

    private companion object {
        const val PHOTO_LINE = "<img class=\"kuva\" id=\"kuva-"
    }
}
