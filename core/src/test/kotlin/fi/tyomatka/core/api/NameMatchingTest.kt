package fi.tyomatka.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

class NameMatchingTest {
    @Test
    fun `kadun lyhenne avataan, muut pisteeseen päättyvät sanat jäävät ennalleen`() {
        assertEquals("Isokatu 10, Oulu", expandStreetAbbreviations("Isok. 10, Oulu"))
        assertEquals("Pihlajakuja 3", expandStreetAbbreviations("Pihlajakj. 3"))
        assertEquals("S:t Eriksgatan 5", expandStreetAbbreviations("S:t Eriksg. 5"))
        // Lyhyt alku ei ole kadunnimi: rakennus, entinen, Viron maantee ja puiestee.
        assertEquals("Rak. 5, ent. Tie 2", expandStreetAbbreviations("Rak. 5, ent. Tie 2"))
        assertEquals("Narva mnt. 5, Pärnu pst. 1", expandStreetAbbreviations("Narva mnt. 5, Pärnu pst. 1"))
        assertEquals("Oy Ab, esim. Hallituskatu 5", expandStreetAbbreviations("Oy Ab, esim. Hallituskatu 5"))
    }
}
