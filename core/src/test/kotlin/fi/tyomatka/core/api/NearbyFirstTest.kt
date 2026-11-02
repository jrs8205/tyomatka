package fi.tyomatka.core.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NearbyFirstTest {
    private fun merged(nearby: suspend () -> List<String>, wide: suspend () -> List<String>) =
        suspend { nearbyFirst(limit = 8, key = { it }, nearby = nearby, wide = wide) }

    @Test
    fun `lähiosumat ensin ilman toistoja`() = runTest {
        assertEquals(listOf("oulu", "hml", "hki"), merged({ listOf("oulu") }, { listOf("hml", "oulu", "hki") })())
    }

    @Test
    fun `laajan haun virhe ei hävitä lähiosumia`() = runTest {
        // Lähihaku 200, laaja haku 503: löydetyt lähiosoitteet näytetään silti.
        assertEquals(listOf("oulu"), merged({ listOf("oulu") }, { throw ApiException("HTTP 503", 503) })())
    }

    @Test
    fun `molempien virhe tai tyhjä lähihaku ja laajan virhe nousee`() = runTest {
        assertFailsWith<ApiException> { merged({ emptyList() }, { throw ApiException("HTTP 503", 503) })() }
        assertFailsWith<ApiException> { merged({ throw ApiException("HTTP 500", 500) }, { throw ApiException("HTTP 503", 503) })() }
    }
}
