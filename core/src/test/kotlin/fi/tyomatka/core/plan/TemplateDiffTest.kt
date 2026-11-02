package fi.tyomatka.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pohjan ja päivän vertailu. */
class TemplateDiffTest {
    private fun stop(
        address: String,
        label: String = address.substringBefore(","),
        lat: Double = 60.2,
        lng: Double = 24.9,
        deadline: Int? = null,
        service: Long? = null,
    ) = TemplateStopData(label, address, lat, lng, deadline, service)

    private val orum = stop("Läntinen teollisuuskatu 2, Espoo", "Örum", 60.2050, 24.6532)
    private val varasto = stop("Tikkurilantie 10, Vantaa", "Vantaan varasto", 60.2930, 25.0440)
    private val kerava = stop("Esimerkkitie 5, Kerava", "Keravan kone", 60.4010, 25.1050)
    private val template = listOf(orum, varasto, kerava)

    @Test
    fun `sama lista ei ole muuttunut`() {
        assertEquals(TemplateChange.NONE, TemplateDiff.compare(template, template))
        assertFalse(TemplateDiff.compare(template, template).isChanged)
    }

    @Test
    fun `peraan lisatty stoppi lasketaan lisatyksi`() {
        val change = TemplateDiff.compare(template, template + stop("Sibeliuksenkatu 1, Järvenpää", lat = 60.47, lng = 25.09))
        assertEquals(TemplateChange(added = 1, removed = 0, edited = 0, reordered = false), change)
        assertTrue(change.isChanged)
    }

    @Test
    fun `pohjan stoppi puuttuu paivasta`() {
        assertEquals(TemplateChange(0, 1, 0, false), TemplateDiff.compare(template, listOf(orum, kerava)))
    }

    @Test
    fun `kahden stopin paikanvaihto on jarjestyksen muutos`() {
        assertEquals(TemplateChange(0, 0, 0, true), TemplateDiff.compare(template, listOf(varasto, orum, kerava)))
    }

    @Test
    fun `sama osoite eri nimella tai aikarajalla on muokattu`() {
        assertEquals(TemplateChange(0, 0, 1, false), TemplateDiff.compare(template, listOf(orum, varasto.copy(label = "Varasto"), kerava)))
        assertEquals(TemplateChange(0, 0, 1, false), TemplateDiff.compare(template, listOf(orum, varasto, kerava.copy(deadlineMinutesOfDay = 900))))
        assertEquals(TemplateChange(0, 0, 1, false), TemplateDiff.compare(template, listOf(orum.copy(serviceSec = 600), varasto, kerava)))
    }

    @Test
    fun `koordinaatin ero alle mikroasteen ei ole muutos`() {
        val nudged = listOf(orum.copy(lat = orum.lat + 1e-7), varasto, kerava)
        assertEquals(TemplateChange.NONE, TemplateDiff.compare(template, nudged))
    }

    @Test
    fun `tuplaosoitteet lasketaan esiintymiskertojen mukaan`() {
        assertEquals(TemplateChange(1, 0, 0, false), TemplateDiff.compare(template, template + orum))
    }

    @Test
    fun `kuittausavain on sama samalle listalle ja eri eri jarjestykselle`() {
        assertEquals(TemplateDiff.ackKey(template), TemplateDiff.ackKey(listOf(orum, varasto, kerava)))
        assertNotEquals(TemplateDiff.ackKey(template), TemplateDiff.ackKey(listOf(varasto, orum, kerava)))
        assertNotEquals(TemplateDiff.ackKey(template), TemplateDiff.ackKey(listOf(orum, varasto, kerava.copy(label = "Kone"))))
        assertEquals(TemplateDiff.ackKey(emptyList()), TemplateDiff.ackKey(emptyList()))
        assertEquals(40, TemplateDiff.ackKey(template).length)
    }
}
