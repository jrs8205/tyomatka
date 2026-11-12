package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlateTest {
    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")

    /** Hetki annettuna päivänä ja kellonaikana Helsingin ajassa. */
    private fun at(day: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(day).atTime(hour, minute).atZone(zone).toEpochSecond()

    private fun segment(id: Long, start: Long, end: Long?, startKm: Long?, endKm: Long?, plate: String = "TES-598") =
        WorkSegmentEntity(
            id = id, routeDayId = 1, startEpochSec = start, endEpochSec = end, licensePlate = plate,
            odometerStartKm = startKm, odometerEndKm = endKm,
        )

    private fun fillup(at: Long, km: Long, plate: String = "TES-598") = FuelFillupEntity(
        id = at, epochSec = at, licensePlate = plate, odometerKm = km, liters = 40.0, totalCost = 80.0,
        pricePerLiter = 2.0, currency = "EUR",
    )

    @Test
    fun `samanhetkisistä lukemista viimeisin on suurin`() {
        val segments = listOf(segment(1, at("2026-10-17", 8), at("2026-10-17", 15), 475_000, 475_279))
        val fillups = listOf(fillup(at("2026-10-17", 15), 475_281))
        assertEquals(475_281, latestPlateReading("TES-598", segments, fillups, zone))
    }

    @Test
    fun `saman päivän lukemista viimeisin on suurin, vaikka sen kellonaika olisi aiempi`() {
        // Takautuva tankkaus saa aikaleimakseen klo 12 ([fillupEpochSec]); se on kirjattu jakson lopun jälkeen,
        // eikä saa hävitä saman päivän pienemmälle lukemalle.
        val segments = listOf(segment(1, at("2026-10-17", 8), at("2026-10-17", 15), 475_000, 475_279))
        val fillups = listOf(fillup(at("2026-10-17", 12), 475_290))
        assertEquals(475_290, latestPlateReading("TES-598", segments, fillups, zone))
        // Toisen auton saman päivän suurempi lukema ei vaikuta.
        assertEquals(475_290, latestPlateReading("TES-598", segments, fillups + fillup(at("2026-10-17", 14), 999_000, "ABC-123"), zone))
    }

    @Test
    fun `eri päivinä viimeisin on viimeisimmän päivän lukema, vaikka aiemman päivän lukema olisi suurempi`() {
        // Korjattu tai virheellinen vanhempi lukema ei saa peittää tuoreinta päivää.
        val segments = listOf(
            segment(1, at("2026-10-16", 8), at("2026-10-16", 16), 475_000, 476_100),
            segment(2, at("2026-10-17", 8), at("2026-10-17", 15), 475_200, 475_279),
        )
        assertEquals(475_279, latestPlateReading("TES-598", segments, emptyList(), zone))
        // Päivän raja menee paikallisen ajan mukaan: yöllä klo 1 kirjattu lukema on jo uuden päivän lukema.
        val night = fillup(at("2026-10-17", 1), 475_100)
        assertEquals(475_100, latestPlateReading("TES-598", listOf(segments[0]), listOf(night), zone))
        assertNull(latestPlateReading("XYZ-1", segments, listOf(night), zone))
    }

    @Test
    fun `lukemat autoittain kaikille autoille, joilla lukema on`() {
        val segments = listOf(
            segment(1, at("2026-10-17", 8), at("2026-10-17", 15), 475_000, 475_279),
            segment(2, at("2026-10-17", 16), null, null, null, plate = "TES-393"),
        )
        val fillups = listOf(fillup(at("2026-10-17", 12), 475_290), fillup(at("2026-10-10", 12), 120_000, "ABC-123"))
        assertEquals(mapOf("TES-598" to 475_290L, "ABC-123" to 120_000L), latestPlateReadings(segments, fillups, zone))
    }

    @Test
    fun `valilyonnit pois ja kirjaimet isoiksi`() {
        assertEquals("ABC-123", normalizePlate("abc 123"))
    }

    @Test
    fun `valiviivallinen sailyy`() {
        assertEquals("ABC-123", normalizePlate("ABC-123"))
    }

    @Test
    fun `tyhja on null`() {
        assertNull(normalizePlate("   "))
    }

    @Test
    fun `valiviivaton saa valiviivan`() {
        assertEquals("CAB-12", normalizePlate("cab12"))
        assertEquals("AB-1", normalizePlate("ab1"))
    }

    @Test
    fun `muu muoto sailyy sellaisenaan`() {
        assertEquals("XYZ", normalizePlate("xyz"))
        assertEquals("ABCD-1234", normalizePlate("abcd-1234"))
    }

    @Test
    fun `aakkoset kelpaavat`() {
        assertEquals("ÄÖÅ-1", normalizePlate("äöå1"))
    }

    private val known = listOf("ABC-123", "ABD-456", "XYZ-789")

    @Test
    fun `tyhja syote nayttaa kaikki`() {
        assertEquals(known, plateSuggestions("", known))
    }

    @Test
    fun `alku suodattaa`() {
        assertEquals(listOf("ABC-123", "ABD-456"), plateSuggestions("ab", known))
    }

    @Test
    fun `valiviiva ohitetaan vertailussa`() {
        assertEquals(listOf("ABC-123"), plateSuggestions("abc1", known))
        assertEquals(listOf("ABC-123"), plateSuggestions("ABC-1", known))
    }

    @Test
    fun `tarkka osuma piilottaa ehdotukset`() {
        assertTrue(plateSuggestions("abc-123", known).isEmpty())
        assertTrue(plateSuggestions("abc123", known).isEmpty())
    }

    @Test
    fun `enintaan max ehdotusta`() {
        val many = (1..12).map { "AB$it-1" }
        assertEquals(8, plateSuggestions("", many).size)
    }

    @Test
    fun `tunnetut rekkarit viimeisin kaytto ensin ilman tuplia`() {
        val segments = listOf(
            WorkSegmentEntity(id = 1, routeDayId = 1, startEpochSec = 100, licensePlate = "ABC-123"),
            WorkSegmentEntity(id = 2, routeDayId = 1, startEpochSec = 300, licensePlate = "XYZ-789"),
            WorkSegmentEntity(id = 3, routeDayId = 2, startEpochSec = 200, licensePlate = "ABC-123"),
            WorkSegmentEntity(id = 4, routeDayId = 2, startEpochSec = 400, licensePlate = null),
        )
        val fillups = listOf(
            FuelFillupEntity(
                id = 1, epochSec = 250, licensePlate = "QQQ-1", odometerKm = 1,
                liters = 1.0, totalCost = 0.0, pricePerLiter = 0.0, currency = "EUR",
            ),
        )
        assertEquals(listOf("XYZ-789", "QQQ-1", "ABC-123"), knownPlates(segments, fillups))
    }
}
