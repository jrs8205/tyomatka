package fi.tyomatka.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateNamesTest {
    @Test
    fun `valilyonnit siistitaan`() {
        assertEquals("Vantaa kierros", normalizeTemplateName("  Vantaa   kierros  "))
        assertEquals("Örum", normalizeTemplateName("\tÖrum\n"))
    }

    @Test
    fun `tyhja ja null ovat tyhja nimi`() {
        assertEquals("", normalizeTemplateName(null))
        assertEquals("", normalizeTemplateName("   "))
    }

    @Test
    fun `nimi rajataan 60 merkkiin`() {
        assertEquals(60, TEMPLATE_NAME_MAX_LENGTH)
        assertEquals(60, normalizeTemplateName("a".repeat(61)).length)
        assertEquals("a".repeat(60), normalizeTemplateName("a".repeat(60)))
    }
}
