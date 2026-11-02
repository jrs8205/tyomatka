package fi.tyomatka.core.poi

import fi.tyomatka.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiServicesTest {
    private fun item(
        category: PoiCategory,
        tags: Map<String, String> = emptyMap(),
        name: String? = null,
        brand: String? = null,
        hgv: Boolean = false,
    ) = PoiItem("n1", category, GeoPoint(60.3, 25.0), name, brand, null, hgv, tags)

    private fun kinds(chips: List<PoiChip>) = chips.map { it.kind }

    @Test
    fun `rekkojen huoltoasema saa palvelusirut järjestyksessä ja puuttuvat rajat tuntemattomina`() {
        val station = item(
            PoiCategory.FUEL,
            mapOf(
                "fuel:hgv_diesel" to "yes", "fuel:adblue" to "yes", "fuel:adblue:pump" to "yes",
                "toilets" to "yes", "shower" to "yes", "opening_hours" to "24/7", "shop" to "kiosk",
            ),
            hgv = true,
        )
        val chips = PoiServices.chips(station)
        assertEquals(
            listOf(
                PoiChipKind.HGV_DIESEL, PoiChipKind.ADBLUE, PoiChipKind.TOILETS, PoiChipKind.SHOWER, PoiChipKind.SHOP,
                PoiChipKind.OPEN_24H, PoiChipKind.MAX_HEIGHT, PoiChipKind.MAX_WEIGHT, PoiChipKind.OVERNIGHT,
            ),
            kinds(chips),
        )
        assertEquals("pump", chips.first { it.kind == PoiChipKind.ADBLUE }.value)
        assertEquals(listOf(false, false, false), chips.takeLast(3).map { it.known })
        assertTrue(chips.dropLast(3).all { it.known })
    }

    @Test
    fun `AdBlue kanisterina ja ilman tarkennusta`() {
        assertEquals("canister", PoiServices.chips(item(PoiCategory.FUEL, mapOf("fuel:adblue" to "canister"))).first { it.kind == PoiChipKind.ADBLUE }.value)
        assertNull(PoiServices.chips(item(PoiCategory.FUEL, mapOf("fuel:adblue" to "yes"))).first { it.kind == PoiChipKind.ADBLUE }.value)
        assertFalse(PoiServices.chips(item(PoiCategory.FUEL, mapOf("fuel:adblue" to "no"))).any { it.kind == PoiChipKind.ADBLUE })
    }

    @Test
    fun `latauspiste saa liittimet, tehon ja paikkamäärän eikä tuntemattomia rajoja`() {
        val chips = PoiServices.chips(
            item(
                PoiCategory.CHARGING,
                mapOf(
                    "socket:type2" to "4", "socket:type2_combo" to "2", "socket:type2_combo:output" to "150 kW",
                    "socket:type2:output" to "22 kW", "capacity" to "6",
                ),
            ),
        )
        assertEquals(
            listOf(
                PoiChip(PoiChipKind.CHARGING_SOCKET, "CCS|2"), PoiChip(PoiChipKind.CHARGING_SOCKET, "Type 2|4"),
                PoiChip(PoiChipKind.CHARGING_POWER, "150"), PoiChip(PoiChipKind.CAPACITY, "6"),
            ),
            chips,
        )
    }

    @Test
    fun `latausteho luetaan myös aseman omasta tehosta ja epäkelpo teho ohitetaan`() {
        assertEquals("50", PoiServices.chips(item(PoiCategory.CHARGING, mapOf("charging_station:output" to "50 kW"))).single().value)
        assertTrue(PoiServices.chips(item(PoiCategory.CHARGING, mapOf("socket:chademo:output" to "nopea"))).isEmpty())
    }

    @Test
    fun `kaasuasema saa kaasun lajit`() {
        assertEquals(
            listOf(PoiChip(PoiChipKind.GAS_KIND, "LPG"), PoiChip(PoiChipKind.GAS_KIND, "CNG")),
            PoiServices.chips(item(PoiCategory.GAS, mapOf("fuel:lpg" to "yes", "fuel:cng" to "yes", "fuel:lng" to "no"))),
        )
    }

    @Test
    fun `lepopaikka ilman tietoja näyttää kolme tuntematonta rajaa`() {
        val chips = PoiServices.chips(item(PoiCategory.REST))
        assertEquals(listOf(PoiChipKind.MAX_HEIGHT, PoiChipKind.MAX_WEIGHT, PoiChipKind.OVERNIGHT), kinds(chips))
        assertTrue(chips.none { it.known })
    }

    @Test
    fun `rekkaparkki saa paikkamäärän, korkeusrajan ja yöpymiskiellon`() {
        val chips = PoiServices.chips(item(PoiCategory.REST, mapOf("hgv" to "designated", "capacity:hgv" to "12", "maxheight" to "4.2", "overnight" to "no")))
        assertEquals(
            listOf(
                PoiChip(PoiChipKind.TRUCK_PARKING, "12"), PoiChip(PoiChipKind.MAX_HEIGHT, "4.2"),
                PoiChip(PoiChipKind.MAX_WEIGHT, null, known = false), PoiChip(PoiChipKind.OVERNIGHT, "no"),
            ),
            chips,
        )
        assertEquals(listOf(PoiChipKind.TRUCK_PARKING), kinds(PoiServices.chips(item(PoiCategory.REST, mapOf("hgv" to "yes")))).take(1))
    }

    @Test
    fun `kuljettajan korjaus lisää, korvaa ja poistaa siruja`() {
        val corrected = PoiServices.chips(
            item(PoiCategory.REST, mapOf("toilets" to "yes")),
            mapOf(
                PoiCorrectionField.SHOWER to "yes", PoiCorrectionField.MAX_HEIGHT to "4.2",
                PoiCorrectionField.OVERNIGHT to "no", PoiCorrectionField.TOILETS to "no",
            ),
        )
        assertEquals(
            listOf(
                PoiChip(PoiChipKind.SHOWER, null, corrected = true), PoiChip(PoiChipKind.MAX_HEIGHT, "4.2", corrected = true),
                PoiChip(PoiChipKind.MAX_WEIGHT, null, known = false), PoiChip(PoiChipKind.OVERNIGHT, "no", corrected = true),
            ),
            corrected,
        )
    }

    @Test
    fun `nimi, varalla brändi, muuten ei mitään`() {
        assertEquals("Neste Truck", PoiServices.displayName(item(PoiCategory.FUEL, name = "Neste Truck", brand = "Neste")))
        assertEquals("Neste", PoiServices.displayName(item(PoiCategory.FUEL, name = " ", brand = "Neste")))
        assertNull(PoiServices.displayName(item(PoiCategory.FUEL)))
    }

    @Test
    fun `asemasivu vain http-osoitteena ja ensisijaisuusjärjestyksessä`() {
        assertEquals("https://x.fi", PoiServices.website(item(PoiCategory.FUEL, mapOf("website" to "https://x.fi", "brand:website" to "https://z.fi"))))
        assertEquals("http://y.fi", PoiServices.website(item(PoiCategory.FUEL, mapOf("contact:website" to "http://y.fi", "brand:website" to "https://z.fi"))))
        assertEquals("https://z.fi", PoiServices.website(item(PoiCategory.FUEL, mapOf("brand:website" to "https://z.fi"))))
        assertNull(PoiServices.website(item(PoiCategory.FUEL, mapOf("website" to "mailto:a@b.fi"))))
        assertNull(PoiServices.website(item(PoiCategory.FUEL)))
    }

    @Test
    fun `puhelin, aukiolo ja oletuskesto`() {
        assertEquals("+358 800 000 000", PoiServices.phone(item(PoiCategory.FUEL, mapOf("contact:phone" to "+358 800 000 000"))))
        assertEquals("24/7", PoiServices.openingHours(item(PoiCategory.FUEL, mapOf("opening_hours" to "24/7"))))
        assertEquals(1_800L, PoiServices.defaultServiceSec(PoiCategory.CHARGING))
        assertEquals(900L, PoiServices.defaultServiceSec(PoiCategory.REST))
    }
}
