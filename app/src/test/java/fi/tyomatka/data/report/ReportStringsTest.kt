package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportStringsTest {
    private val nb = '\u00A0'
    private val fi = reportStrings(ReportLanguage.FI)
    private val sv = reportStrings(ReportLanguage.SV)
    private val en = reportStrings(ReportLanguage.EN)

    @Test
    fun `lajit kuuluvat sanaryhmiin`() {
        assertEquals(FillGroup.FILL, fillGroup(FuelKind.DIESEL))
        assertEquals(FillGroup.FILL, fillGroup(FuelKind.PETROL))
        assertEquals(FillGroup.FILL, fillGroup(FuelKind.ADBLUE))
        assertEquals(FillGroup.CHARGE, fillGroup(FuelKind.ELECTRIC))
        assertEquals(FillGroup.GAS, fillGroup(FuelKind.GAS))
    }

    @Test
    fun `kirjausosion otsikko luettelee sanaryhmät järjestyksessä tankkaus, lataus, täyttö`() {
        assertEquals("Tankkaukset", fi.entriesTitle(setOf(FuelKind.DIESEL)))
        assertEquals("Laddningar", sv.entriesTitle(setOf(FuelKind.ELECTRIC)))
        val all = listOf(FuelKind.GAS, FuelKind.ELECTRIC, FuelKind.DIESEL)
        assertEquals("Tankkaukset, lataukset ja täytöt", fi.entriesTitle(all))
        assertEquals("Tankningar, laddningar och påfyllningar", sv.entriesTitle(all))
        assertEquals("Fill-ups, charges and refills", en.entriesTitle(all))
        assertEquals("Tankkaukset ja lataukset", fi.entriesTitle(listOf(FuelKind.ADBLUE, FuelKind.ELECTRIC)))
        assertEquals("Täytöt", fi.entriesTitle(listOf(FuelKind.GAS)))
        assertEquals("Tankkaukset", fi.entriesTitle(emptyList()))
    }

    @Test
    fun `määrät sanaryhmittäin samassa järjestyksessä ja kielen sidesanalla`() {
        val counts = mapOf(FillGroup.GAS to 4, FillGroup.FILL to 12, FillGroup.CHARGE to 3)
        assertEquals("12${nb}tankkausta, 3${nb}latausta ja 4${nb}täyttöä", fi.joinList(fi.entryCounts(counts)))
        assertEquals("12${nb}tankningar, 3${nb}laddningar och 4${nb}påfyllningar", sv.joinList(sv.entryCounts(counts)))
        assertEquals(listOf("1${nb}täyttö"), fi.entryCounts(mapOf(FillGroup.GAS to 1)))
        assertEquals(listOf("1${nb}refill"), en.entryCounts(mapOf(FillGroup.GAS to 1)))
        assertEquals(listOf("1,234${nb}fill-ups"), en.entryCounts(mapOf(FillGroup.FILL to 1234)))
        assertEquals(emptyList(), fi.entryCounts(mapOf(FillGroup.FILL to 0)))
        assertEquals("", fi.joinList(emptyList()))
        assertEquals("a", fi.joinList(listOf("a")))
        assertEquals("a and b", en.joinList(listOf("a", "b")))
    }

    @Test
    fun `sanaryhmän sanat`() {
        assertEquals("Ladattu", fi.did(FillGroup.CHARGE))
        assertEquals("Påfyllt", sv.did(FillGroup.GAS))
        assertEquals("Fuelled", en.did(FillGroup.FILL))
        assertEquals("täyttö", fi.entryWord(FillGroup.GAS, 1))
        assertEquals("täyttöä", fi.entryWord(FillGroup.GAS, 2))
        assertEquals("charges", en.entryWord(FillGroup.CHARGE, 0))
        assertEquals("Latauksen kuva", fi.photoAlt(FillGroup.CHARGE))
    }

    @Test
    fun `lajien nimet kielittäin`() {
        assertEquals("Gas (CNG)", en.kindName(FuelKind.GAS))
        assertEquals("Gas", sv.kindName(FuelKind.GAS))
        assertEquals("Kaasu", fi.kindName(FuelKind.GAS))
        assertEquals("Petrol", en.kindName(FuelKind.PETROL))
        assertEquals("Bensin", sv.kindName(FuelKind.PETROL))
        assertEquals("El", sv.kindName(FuelKind.ELECTRIC))
        assertEquals("AdBlue", fi.kindName(FuelKind.ADBLUE))
    }

    @Test
    fun `lukumäärät taipuvat ja luku pysyy sanassa kiinni`() {
        assertEquals("1${nb}kuva", fi.count(1, fi.photo))
        assertEquals("2${nb}kuvaa", fi.count(2, fi.photo))
        assertEquals("0${nb}dagar", sv.count(0, sv.day))
        assertEquals("1${nb}working day", en.count(1, en.workDay))
        assertEquals("10${nb}000${nb}kirjausta", fi.count(10_000, fi.entry))
    }

    @Test
    fun `mallitekstit täyttyvät`() {
        assertEquals("viety su 18.10.2026 klo${nb}9.40", fi.exported("su 18.10.2026", "9.40"))
        assertEquals("exported Sun 18 Oct 2026 at${nb}09:40", en.exported("Sun 18 Oct 2026", "09:40"))
        assertEquals("Näytetään 31/45 päivää.", fi.shown(31, 45))
        assertEquals("Photo 1/2", en.photoOf(1, 2))
        assertEquals("Träffar: 3 dagar, 1 post", sv.hits("3 dagar", "1 post"))
        assertTrue(fi.backupText("1.9.–18.10.2026").contains("jakson 1.9.–18.10.2026 kirjauksista"))
        assertTrue(fi.backupText(null).startsWith("Tämä tiedosto on koko varmuuskopio"))
    }

    @Test
    fun `jokaisella kielellä on kaikki tekstit`() {
        val getters = ReportStrings::class.java.methods.filter {
            it.declaringClass == ReportStrings::class.java && it.name.startsWith("get") && it.parameterCount == 0
        }
        assertTrue(getters.size > 40, "tekstejä ${getters.size}")
        ReportLanguage.entries.forEach { language ->
            val strings = reportStrings(language)
            assertEquals(language, strings.language)
            getters.forEach { getter ->
                val name = "${language.tag}.${getter.name}"
                when (val value = getter.invoke(strings)) {
                    is String -> assertTrue(value.isNotBlank(), name)
                    is Plural -> assertTrue(value.one.isNotBlank() && value.many.isNotBlank(), name)
                    is List<*> -> assertTrue(value.isNotEmpty() && value.all { it is String && it.isNotBlank() }, name)
                }
            }
            FuelKind.entries.forEach { assertTrue(strings.kindName(it).isNotBlank()) }
            FillGroup.entries.forEach {
                assertTrue(strings.did(it).isNotBlank() && strings.photoAlt(it).isNotBlank())
                assertTrue(strings.entryWord(it, 1).isNotBlank() && strings.entryWord(it, 2).isNotBlank())
            }
            val texts = getters.map { it.invoke(strings) }.filterIsInstance<String>() + strings.explanations +
                strings.backupText(null) + strings.backupText("1.–31.10.2026")
            texts.forEach { assertFalse("→" in it, it) }
            assertEquals(fi.tableColumns.size, strings.tableColumns.size, language.tag)
            assertEquals(fi.explanations.size, strings.explanations.size, language.tag)
        }
        assertEquals(10, fi.tableColumns.size)
        // Lisätiedot-sarake kulutuksen jälkeen ennen kuvia (määrittely 20.10.).
        assertEquals(listOf("Lisätiedot", "Anteckningar", "Notes"), listOf(fi, sv, en).map { it.tableColumns[8] })
        assertEquals(listOf("Lisätiedot", "Anteckningar", "Notes"), listOf(fi, sv, en).map { it.notes })
    }
}
