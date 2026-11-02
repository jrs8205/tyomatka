package fi.tyomatka.data.report

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.db.RouteDayEntity
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportSummaryTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val october = ExportPeriod.Month(YearMonth.of(2026, 10))

    private fun sec(d: Int, h: Int) = LocalDate.of(2026, 10, d).atTime(h, 0).atZone(zone).toEpochSecond()

    private fun fill(id: Long, d: Int, kind: FuelKind) = FuelFillupEntity(
        id = id, epochSec = sec(d, 12), licensePlate = "ABC-123", odometerKm = 1000 + id, liters = 30.0, totalCost = 60.0,
        pricePerLiter = 2.0, kind = kind, currency = "EUR",
    )

    @Test
    fun `määrät sanaryhmittäin, työpäivät, kuvat ja autot`() {
        val routeDays = listOf(RouteDayEntity(id = 1, dateEpochDay = LocalDate.of(2026, 10, 5).toEpochDay()))
        val segments = listOf(WorkSegmentEntity(id = 1, routeDayId = 1, startEpochSec = sec(5, 7), endEpochSec = sec(5, 15), odometerStartKm = 900, odometerEndKm = 1000))
        val fillups = listOf(fill(1, 5, FuelKind.DIESEL), fill(2, 6, FuelKind.ADBLUE), fill(3, 7, FuelKind.ELECTRIC), fill(4, 8, FuelKind.GAS))
        val photos = mapOf(1L to listOf(FuelPhotoEntity(id = 9, fillupId = 1, fileName = "a", addedAtEpochSec = 0)))
        val model = reportModel(october, segments, routeDays, fillups, photos, listOf(CarEntity("ABC-123", FuelKind.DIESEL, null)), zone)

        val summary = summarizeExport(model) { 300 * 1024L }

        assertEquals(1, summary.workDays)
        assertEquals(mapOf(FillGroup.FILL to 2, FillGroup.CHARGE to 1, FillGroup.GAS to 1), summary.counts)
        assertEquals(1, summary.photos)
        assertEquals(1, summary.cars)
        assertEquals(300 * 1024L * 4 / 3 + 30 * 1024 + 200 * 1024, summary.estimatedBytes)
        assertFalse(summary.isEmpty)
        assertFalse(summary.isLarge)
    }

    @Test
    fun `200 kuvaa à 300 kt ylittää 50 megatavun varoitusrajan`() {
        val fillup = fill(1, 5, FuelKind.DIESEL)
        val photos = mapOf(1L to List(200) { FuelPhotoEntity(id = it + 10L, fillupId = 1, fileName = "k$it", addedAtEpochSec = it.toLong()) })
        val model = reportModel(october, emptyList(), emptyList(), listOf(fillup), photos, emptyList(), zone)

        val summary = summarizeExport(model) { 300 * 1024L }

        assertTrue(summary.estimatedBytes > LARGE_EXPORT_BYTES)
        assertTrue(summary.isLarge)
    }

    @Test
    fun `tyhjä jakso on tyhjä`() {
        val model = reportModel(october, emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), zone)
        assertTrue(summarizeExport(model) { 0 }.isEmpty)
    }
}
