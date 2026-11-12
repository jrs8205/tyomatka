package fi.tyomatka.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchClearTest {
    @Test
    fun `tyhjennysnappi näkyy, kun haussa on tekstiä`() {
        assertTrue(showSearchClear("Kauppakatu"))
        assertTrue(showSearchClear(" "))
    }

    @Test
    fun `tyhjässä haussa ei ole tyhjennettävää`() {
        assertFalse(showSearchClear(""))
    }

    @Test
    fun `lukitussa kentässä tyhjennysnappia ei näytetä`() {
        assertFalse(showSearchClear("Kauppakatu 5, Oulu", enabled = false))
    }
}
