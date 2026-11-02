package fi.tyomatka.ui.plan.nearby

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.poi.PoiCategory
import fi.tyomatka.core.poi.PoiItem
import fi.tyomatka.core.poi.ReportField
import fi.tyomatka.core.poi.StationReport
import fi.tyomatka.data.repo.NearbyData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyListTest {
    private val here = GeoPoint(60.0, 25.0)

    /** Kohde [km]:n päässä pohjoiseen (isoympyrällä 1° leveyttä = 111,19493 km). */
    private fun item(
        id: String,
        km: Double,
        name: String? = id,
        category: PoiCategory = PoiCategory.FUEL,
        hgv: Boolean = false,
        brand: String? = null,
        address: String? = null,
    ) = PoiItem(id, category, GeoPoint(here.lat + km / 111.19492664, here.lng), name, brand, address, hgv, emptyMap())

    @Test
    fun `vyöhykkeen rajat kuuluvat enintään yhteen vyöhykkeeseen ja 50 km on vielä kaukana`() {
        val expected = mapOf(
            0.0 to NearbyBand.NEAR, 9.99 to NearbyBand.NEAR, 10.0 to NearbyBand.MID, 19.99 to NearbyBand.MID,
            20.0 to NearbyBand.FAR, 50.0 to NearbyBand.FAR, 50.01 to null,
        )
        expected.forEach { (km, band) ->
            assertEquals(listOfNotNull(band), NearbyBand.entries.filter { it.contains(km) }, "$km km")
        }
    }

    @Test
    fun `rivit vyöhykkeittäin lähin ensin, sitten nimen mukaan`() {
        // Tunnisteet vastakkaisessa järjestyksessä kuin nimet; nimetön ketjun mukaan, pienet ja isot kirjaimet suomalaisittain.
        val items = listOf(
            item("a", 5.0, name = "Shell"), item("b", 5.0, name = "neste"), item("g", 5.0, name = null, brand = "Pekka"),
            item("y", 5.0, name = "Teboil"), item("x", 5.0, name = "Teboil"),
            item("c", 2.0), item("d", 15.0), item("e", 49.0), item("f", 60.0),
        )

        assertEquals(listOf("c", "b", "g", "a", "x", "y"), nearbyRows(items, emptyList(), here, NearbyBand.NEAR, heavyOnly = false, query = "").map { it.item.id })
        assertEquals(listOf("d"), nearbyRows(items, emptyList(), here, NearbyBand.MID, heavyOnly = false, query = "").map { it.item.id })
        assertEquals(listOf("e"), nearbyRows(items, emptyList(), here, NearbyBand.FAR, heavyOnly = false, query = "").map { it.item.id })
        assertEquals(listOf("b"), nearbyRows(items, emptyList(), here, NearbyBand.NEAR, heavyOnly = false, query = "NESTE").map { it.item.id })
        val row = nearbyRows(items, emptyList(), here, NearbyBand.NEAR, heavyOnly = false, query = "").first()
        assertEquals(2.0, row.distanceKm, 0.001)
    }

    @Test
    fun `raskas-rajaus kuten kartalla`() {
        val items = listOf(
            item("tavallinen", 1.0),
            item("rekka", 2.0, hgv = true),
            item("teboil", 3.0, name = "Teboil Kivistö"),
            item("lataus", 4.0, category = PoiCategory.CHARGING),
            item("ilmoitettu", 5.0),
        )
        val reports = listOf(StationReport("ilmoitettu", ReportField.TRUCK_PARKING, "yes", 20_750))

        val heavy = nearbyRows(items, reports, here, NearbyBand.NEAR, heavyOnly = true, query = "").map { it.item.id }

        assertEquals(listOf("rekka", "teboil", "lataus", "ilmoitettu"), heavy)
        assertEquals(5, nearbyRows(items, reports, here, NearbyBand.NEAR, heavyOnly = false, query = "").size)
    }

    @Test
    fun `haku osuu nimeen, ketjuun ja osoitteeseen jokaisella sanalla`() {
        val neste = item("n", 1.0, name = "Neste Kivistö", brand = "Neste", address = "Kivitie 2, Vantaa")
        assertTrue(nearbyMatches(neste, "neste vantaa"))
        assertTrue(nearbyMatches(neste, "NESTE"))
        assertTrue(nearbyMatches(neste, "  kivistö  "))
        assertTrue(nearbyMatches(neste, ""))
        assertTrue(!nearbyMatches(neste, "neste espoo"))
        val brandOnly = item("b", 1.0, name = null, brand = "St1")
        assertTrue(nearbyMatches(brandOnly, "st1"))
        val aane = item("ä", 1.0, address = "Keskustie 1, Äänekoski")
        val aa = item("a", 1.0, address = "Keskustie 1, aanekoski")
        assertTrue(nearbyMatches(aane, "Äänekoski"))
        assertTrue(nearbyMatches(aane, "äänekoski"))
        assertTrue(!nearbyMatches(aa, "Äänekoski"))
    }

    @Test
    fun `vyöhykkeiden osumamäärät haulla`() {
        val items = listOf(
            item("1", 1.0, brand = "St1"), item("2", 8.0, name = "ST1 Kivistö"), item("3", 12.0, brand = "St1"),
            item("4", 30.0, brand = "St1"), item("5", 3.0, brand = "Neste"), item("6", 70.0, brand = "St1"),
        )

        assertEquals(
            mapOf(NearbyBand.NEAR to 2, NearbyBand.MID to 1, NearbyBand.FAR to 1),
            nearbyBandCounts(items, emptyList(), here, heavyOnly = false, query = "st1"),
        )
        assertEquals(3, nearbyBandCounts(items, emptyList(), here, heavyOnly = false, query = "").getValue(NearbyBand.NEAR))
    }

    @Test
    fun `vyöhykkeiden osumamäärät raskas-rajauksella`() {
        val items = listOf(item("tavallinen", 1.0), item("rekka", 2.0, hgv = true), item("ilmoitettu", 12.0), item("kaukana", 30.0))
        val reports = listOf(StationReport("ilmoitettu", ReportField.TRUCK_PARKING, "yes", 20_750))

        assertEquals(
            mapOf(NearbyBand.NEAR to 1, NearbyBand.MID to 1, NearbyBand.FAR to 0),
            nearbyBandCounts(items, reports, here, heavyOnly = true, query = ""),
        )
    }

    @Test
    fun `näkymä laskee valitun lajin rivit ja ladattujen lajien määrät samoilla rajauksilla`() {
        val items = mapOf(
            PoiCategory.FUEL to listOf(item("neste", 1.0, name = "Neste"), item("rekka", 2.0, name = "Shell", hgv = true), item("kaukana", 15.0, name = "Neste")),
            PoiCategory.CHARGING to listOf(item("ionity", 3.0, name = "Ionity", category = PoiCategory.CHARGING)),
        )

        val all = nearbyView(items, emptyList(), here, NearbyChoices())
        assertEquals(listOf("neste", "rekka"), all.rows.map { it.item.id })
        // Vain ladatut lajit; määrä valitulla vyöhykkeellä.
        assertEquals(mapOf(PoiCategory.FUEL to 2, PoiCategory.CHARGING to 1), all.categoryCounts)
        assertEquals(null, all.bandCounts)

        val search = nearbyView(items, emptyList(), here, NearbyChoices(query = "neste"))
        assertEquals(mapOf(PoiCategory.FUEL to 1, PoiCategory.CHARGING to 0), search.categoryCounts)
        assertEquals(mapOf(NearbyBand.NEAR to 1, NearbyBand.MID to 1, NearbyBand.FAR to 0), search.bandCounts)

        val heavy = nearbyView(items, emptyList(), here, NearbyChoices(heavyOnly = true, category = PoiCategory.CHARGING))
        assertEquals(listOf("ionity"), heavy.rows.map { it.item.id })
        assertEquals(mapOf(PoiCategory.FUEL to 1, PoiCategory.CHARGING to 1), heavy.categoryCounts)

        // Pelkät välilyönnit eivät ole hakua.
        assertEquals(null, nearbyView(items, emptyList(), here, NearbyChoices(query = "  ")).bandCounts)
        val mid = nearbyView(items, emptyList(), here, NearbyChoices(band = NearbyBand.MID))
        assertEquals(listOf("kaukana"), mid.rows.map { it.item.id })
        assertEquals(mapOf(PoiCategory.FUEL to 1, PoiCategory.CHARGING to 0), mid.categoryCounts)
    }

    private fun data(offline: Boolean = false, unavailable: Boolean = false, age: Int? = null, withItems: Boolean = true) = NearbyData(
        items = mapOf(PoiCategory.FUEL to if (withItems) listOf(item("n1", 1.0)) else emptyList(), PoiCategory.REST to emptyList()),
        reports = emptyList(),
        loading = false,
        offline = offline,
        unavailable = unavailable,
        cacheAgeDays = age,
        truncated = setOf(PoiCategory.CHARGING),
    )

    @Test
    fun `ilmoitukset listan alla`() {
        assertEquals(emptyList(), nearbyHints(data(), PoiCategory.FUEL))
        assertEquals(listOf(NearbyHint.Stale(3)), nearbyHints(data(offline = true, age = 3), PoiCategory.FUEL))
        // Alle vuorokauden välimuisti ilman ikää: rivejä on, joten ei "kohteita ei voi hakea".
        assertEquals(listOf(NearbyHint.Stale(0)), nearbyHints(data(offline = true), PoiCategory.FUEL))
        assertEquals(listOf(NearbyHint.Offline), nearbyHints(data(offline = true, withItems = false), PoiCategory.FUEL))
        assertEquals(listOf(NearbyHint.Unavailable), nearbyHints(data(unavailable = true), PoiCategory.FUEL))
        assertEquals(listOf(NearbyHint.Truncated), nearbyHints(data(), PoiCategory.CHARGING))
    }

    @Test
    fun `rivin kohde valitun lajin listasta, vaikka sama tunniste on toisessa lajissa`() {
        val asFuel = item("n1", 1.0, name = "Asema")
        val asGas = asFuel.copy(category = PoiCategory.GAS)
        val items = mapOf(PoiCategory.FUEL to listOf(asFuel), PoiCategory.GAS to listOf(asGas))

        assertEquals(PoiCategory.GAS, nearbyItem(items, PoiCategory.GAS, "n1")?.category)
        assertEquals(PoiCategory.FUEL, nearbyItem(items, PoiCategory.FUEL, "n1")?.category)
        assertEquals(PoiCategory.FUEL, nearbyItem(items, PoiCategory.REST, "n1")?.category)
        assertEquals(null, nearbyItem(items, PoiCategory.GAS, "ei"))
    }
}
