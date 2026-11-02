package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.WorkSegmentEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextCarTest {
    private val zone: ZoneId = ZoneId.of("Europe/Helsinki")
    private val abc = CarEntity("ABC-123", FuelKind.DIESEL, null)
    private val evo = CarEntity("EVO-456", FuelKind.ELECTRIC, null)
    private val cars = listOf(abc, evo)

    private fun segment(
        id: Long,
        start: Long,
        end: Long? = start + 3600,
        plate: String? = "ABC-123",
        startKm: Long? = null,
        endKm: Long? = null,
    ) = WorkSegmentEntity(
        id = id, routeDayId = 1, startEpochSec = start, endEpochSec = end, licensePlate = plate,
        odometerStartKm = startKm, odometerEndKm = endKm,
    )

    private fun fillup(plate: String, at: Long, km: Long = 1000) = FuelFillupEntity(
        id = at, epochSec = at, licensePlate = plate, odometerKm = km, liters = 40.0, totalCost = 80.0, pricePerLiter = 2.0,
        currency = "EUR", kind = FuelKind.DIESEL,
    )

    @Test
    fun `viimeisintä työjaksoa uudempi valinta voittaa johdetun oletuksen`() {
        val segments = listOf(segment(1, 1_000), segment(2, 10_000))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 2)
        assertEquals("EVO-456", defaultPlateWithChoice(choice, segments, emptyList(), cars, openSegmentToday = null))
        // Ilman työjaksoja valinta voittaa kirjauksista tai profiileista johdetun oletuksen.
        val noSegments = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = null)
        assertEquals("EVO-456", defaultPlateWithChoice(noSegments, emptyList(), listOf(fillup("ABC-123", 5_000)), cars, null))
        assertEquals("EVO-456", defaultPlateWithChoice(noSegments, emptyList(), emptyList(), cars, null))
    }

    @Test
    fun `valinta on voimassa aikaleimasta riippumatta, kun viimeisin jakso on sama`() {
        // Kellon siirto taaksepäin tai valinta samalla sekunnilla jakson alun kanssa: valintahetki voi olla jakson
        // alkua pienempi, mutta viimeisin jakso on yhä valintahetken jakso, joten valinta pätee.
        val segments = listOf(segment(1, 1_000), segment(2, 30_000))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 2)
        assertEquals("EVO-456", defaultPlateWithChoice(choice, segments, emptyList(), cars, null))
        assertEquals("EVO-456", defaultPlateWithChoice(NextCarChoice("EVO-456", 30_000, 2), segments, emptyList(), cars, null))
    }

    @Test
    fun `valinnan jälkeen alkanut jakso ohittaa valinnan, vaikka sen aikaleima olisi pienempi`() {
        // Kellon siirto: valinta tehtiin jakson 1 ollessa viimeisin, ja jakso 2 alkoi sen jälkeen mutta seinäkellon
        // mukaan aiemmin. Viimeisin jakso ei ole enää sama, joten vanha valinta ei herää.
        val segments = listOf(segment(1, 1_000), segment(2, 15_000))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 1)
        assertEquals("ABC-123", defaultPlateWithChoice(choice, segments, emptyList(), cars, null))
        // Ilman jaksoja tehty valinta ohittuu, kun ensimmäinen jakso alkaa.
        val first = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = null)
        assertEquals("ABC-123", defaultPlateWithChoice(first, listOf(segment(1, 15_000)), emptyList(), cars, null))
    }

    @Test
    fun `tämän päivän avoin työjakso voittaa valinnan`() {
        val open = segment(2, 10_000, end = null)
        val segments = listOf(segment(1, 1_000), open)
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 2)
        assertEquals("ABC-123", defaultPlateWithChoice(choice, segments, emptyList(), cars, openSegmentToday = open))
        // Avoin jakso ilman rekisterinumeroa: oletus on tyhjä, ei valinta.
        val openNoPlate = segment(2, 10_000, end = null, plate = null)
        assertNull(defaultPlateWithChoice(choice, listOf(segment(1, 1_000), openNoPlate), emptyList(), cars, openNoPlate))
    }

    @Test
    fun `eilinen avoin jakso ei estä tänään tehtyä valintaa`() {
        // Unohtunut Lopeta työt tai yövuoro: eilinen jakso on yhä auki, mutta käyttöliittymä ei pidä sitä käynnissä
        // olevana, joten valinta voittaa.
        val segments = listOf(segment(1, 1_000), segment(2, 10_000, end = null))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 2)
        assertEquals("EVO-456", defaultPlateWithChoice(choice, segments, emptyList(), cars, openSegmentToday = null))
    }

    @Test
    fun `poistetun auton valinta ohitetaan`() {
        val segments = listOf(segment(1, 1_000))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 1)
        assertEquals("ABC-123", defaultPlateWithChoice(choice, segments, emptyList(), listOf(abc), null))
        // Auto, jolla on vain kirjauksia (ei profiilia), on Autot-näkymässä ja kelpaa valinnaksi.
        assertEquals("EVO-456", defaultPlateWithChoice(choice, segments, listOf(fillup("EVO-456", 500)), listOf(abc), null))
    }

    @Test
    fun `ilman valintaa oletus on ennallaan`() {
        val segments = listOf(segment(1, 1_000), segment(2, 10_000, plate = "EVO-456"))
        val fillups = listOf(fillup("ABC-123", 5_000))
        assertEquals(defaultCarPlate(segments, emptyList(), cars), defaultPlateWithChoice(null, segments, emptyList(), cars, null))
        assertEquals(defaultCarPlate(emptyList(), fillups, cars), defaultPlateWithChoice(null, emptyList(), fillups, cars, null))
        assertEquals(defaultCarPlate(emptyList(), emptyList(), cars), defaultPlateWithChoice(null, emptyList(), emptyList(), cars, null))
        assertNull(defaultPlateWithChoice(null, emptyList(), emptyList(), emptyList(), null))
    }

    // ---- Työn aloituksen esitäyttö: auto ja saman auton lukema ----

    @Test
    fun `esitäytön lukema on valitun auton oma lukema, ei viimeisimmän jakson`() {
        // Laitteella 18.10.: TES-393 valittuna, mutta viimeisin jakso on TES-598:n. Lukema tulee TES-393:lta.
        val imb = CarEntity("TES-393", FuelKind.DIESEL, null)
        val jkj = CarEntity("TES-598", FuelKind.DIESEL, null)
        val segments = listOf(
            segment(1, 1_000, plate = "TES-393", startKm = 120_000, endKm = 120_250),
            segment(2, 10_000, plate = "TES-598", startKm = 475_000, endKm = 475_282),
        )
        val choice = NextCarChoice("TES-393", chosenEpochSec = 20_000, latestSegmentId = 2)
        assertEquals(StartPrefill("TES-393", 120_250), startPrefill(choice, segments, emptyList(), listOf(imb, jkj), null, zone))
    }

    @Test
    fun `autolle ilman lukemaa ei esitäytetä lukemaa`() {
        val segments = listOf(segment(1, 1_000, startKm = 120_000, endKm = 120_250))
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 20_000, latestSegmentId = 1)
        assertEquals(StartPrefill("EVO-456", null), startPrefill(choice, segments, emptyList(), cars, null, zone))
        // Auton oma jakso ilman lukemia ei myöskään anna lukemaa.
        val noReadings = segments + segment(2, 5_000, plate = "EVO-456")
        assertEquals(StartPrefill("EVO-456", null), startPrefill(choice.copy(latestSegmentId = 2), noReadings, emptyList(), cars, null, zone))
    }

    @Test
    fun `esitäytön lukema on ajallisesti viimeisin kirjauksesta tai jakson lopusta`() {
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 50_000, latestSegmentId = 1)
        val segment = segment(1, 1_000, end = 4_600, plate = "EVO-456", startKm = 10_000, endKm = 10_150)
        // Jakson jälkeinen lataus: kirjauksen lukema on viimeisin.
        val later = listOf(fillup("EVO-456", 9_000, km = 10_400))
        assertEquals(10_400, startPrefill(choice, listOf(segment), later, cars, null, zone).km)
        // Jakson aikainen kirjaus: jakson loppulukema on viimeisin.
        val during = listOf(fillup("EVO-456", 3_000, km = 10_080))
        assertEquals(10_150, startPrefill(choice, listOf(segment), during, cars, null, zone).km)
        // Toisen auton myöhempi kirjaus ei vaikuta.
        val other = listOf(fillup("ABC-123", 9_000, km = 99_000))
        assertEquals(10_150, startPrefill(choice, listOf(segment), other, cars, null, zone).km)
        // Jakso ilman loppulukemaa antaa alkulukeman.
        val noEnd = segment(1, 1_000, plate = "EVO-456", startKm = 10_000)
        assertEquals(10_000, startPrefill(choice, listOf(noEnd), emptyList(), cars, null, zone).km)
    }

    @Test
    fun `esitäytön lukema noudattaa annettua aikavyöhykettä`() {
        // Päivän raja menee annetun vyöhykkeen mukaan ([latestPlateReading]), jotta esitäyttö ja lomakkeen lukemat
        // autoittain laskevat saman lukeman. Jakso päättyy 16.10. klo 14 UTC; kirjaus klo 22.30
        // UTC on Helsingissä jo 17.10. klo 1.30, jolloin se on viimeisimmän päivän ainoa lukema.
        val day16 = LocalDate.of(2026, 10, 16)
        val choice = NextCarChoice("EVO-456", chosenEpochSec = 2_000_000_000, latestSegmentId = 1)
        val segment = segment(
            1, day16.atTime(6, 0).toEpochSecond(ZoneOffset.UTC), day16.atTime(14, 0).toEpochSecond(ZoneOffset.UTC),
            plate = "EVO-456", startKm = 475_000, endKm = 476_100,
        )
        val fillups = listOf(fillup("EVO-456", day16.atTime(22, 30).toEpochSecond(ZoneOffset.UTC), km = 475_100))
        assertEquals(476_100, startPrefill(choice, listOf(segment), fillups, cars, null, ZoneOffset.UTC).km)
        assertEquals(475_100, startPrefill(choice, listOf(segment), fillups, cars, null, zone).km)
    }

    @Test
    fun `ilman tiedettyä autoa lukema on viimeisimmän jakson lukema kuten ennen`() {
        // Viimeisin jakso ilman rekisterinumeroa: auto on tyhjä, ja lukema tulee viimeisimmästä jaksosta.
        val segments = listOf(
            segment(1, 1_000, plate = "ABC-123", startKm = 1_000, endKm = 1_100),
            segment(2, 10_000, plate = null, startKm = 2_000, endKm = 2_100),
        )
        assertEquals(StartPrefill(null, 2_100), startPrefill(null, segments, emptyList(), cars, null, zone))
        assertEquals(latestWorkReading(segments), startPrefill(null, segments, emptyList(), cars, null, zone).km)
        // Ei mitään tietoa: molemmat tyhjiä.
        assertEquals(StartPrefill(null, null), startPrefill(null, emptyList(), emptyList(), emptyList(), null, zone))
    }
}
