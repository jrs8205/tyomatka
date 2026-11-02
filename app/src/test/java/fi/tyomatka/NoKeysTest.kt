package fi.tyomatka

import kotlin.test.Test
import kotlin.test.assertEquals

class NoKeysTest {
    @Test
    fun `sovelluksessa ei ole palveluiden avaimia`() {
        assertEquals(emptyList(), BuildConfig::class.java.fields.map { it.name }.filter { "KEY" in it || "TOKEN" in it })
    }
}
