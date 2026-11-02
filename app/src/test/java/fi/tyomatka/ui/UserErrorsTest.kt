package fi.tyomatka.ui

import fi.tyomatka.R
import fi.tyomatka.core.api.ApiException
import fi.tyomatka.core.api.PoiUnavailableException
import fi.tyomatka.core.api.ReportsUnavailableException
import fi.tyomatka.core.api.RouteNotFoundException
import fi.tyomatka.core.api.StaleSuggestionException
import fi.tyomatka.data.repo.BackupRejectedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ruudulle ei päädy poikkeuksen oma teksti: se on kehittäjän kieltä, usein englantia ja osoitteita. */
class UserErrorsTest {
    @Test
    fun `yhteysvirheet ovat verkkovirheitä`() {
        for (error in listOf(ConnectException("Failed to connect to x"), UnknownHostException("x"), SocketTimeoutException("t"), IOException("x"))) {
            assertEquals(R.string.search_network_error, UserErrors.messageRes(error))
        }
    }

    @Test
    fun `palvelimen virhe ei näytä vastauksen sisältöä`() {
        assertEquals(R.string.error_service, UserErrors.messageRes(ApiException("HTTP 500: raaka vastaus", 500)))
    }

    @Test
    fun `tunnetut syyt saavat omat tekstinsä`() {
        assertEquals(R.string.poi_unavailable, UserErrors.messageRes(PoiUnavailableException(503)))
        assertEquals(R.string.reports_unavailable, UserErrors.messageRes(ReportsUnavailableException()))
        assertEquals(R.string.suggestion_stale, UserErrors.messageRes(StaleSuggestionException()))
    }

    @Test
    fun `puuttuva reitti ei ole palvelukatko`() {
        assertEquals(R.string.route_not_found, UserErrors.messageRes(RouteNotFoundException("NoRoute", 400)))
    }

    @Test
    fun `varmuuskopion hylkäys kertoo syyn myös tiedostotyössä`() {
        val error = BackupRejectedException(R.string.import_version, "versio")
        assertEquals(R.string.import_version, UserErrors.messageRes(error))
        assertEquals(R.string.import_version, UserErrors.messageRes(error, online = false))
    }

    @Test
    fun `jäsentymätön verkkovastaus on palvelun vika`() {
        assertEquals(R.string.error_service, UserErrors.messageRes(SerializationException("html")))
        assertEquals(R.string.error_invalid_data, UserErrors.messageRes(SerializationException("html"), online = false))
    }

    @Test
    fun `kelvoton syöte ja muut virheet`() {
        assertEquals(R.string.error_invalid_data, UserErrors.messageRes(IllegalArgumentException("Tiedoston kirjaukset eivät kelpaa")))
        assertEquals(R.string.error_generic, UserErrors.messageRes(IllegalStateException("sisäinen")))
        assertEquals(R.string.error_generic, UserErrors.messageRes(RuntimeException()))
    }

    @Test
    fun `tiedostotyössä IO-virhe ei ole verkkovirhe`() {
        assertEquals(R.string.error_file, UserErrors.messageRes(IOException("Kuvaa ei voitu lukea."), online = false))
        assertEquals(R.string.error_invalid_data, UserErrors.messageRes(IllegalArgumentException("x"), online = false))
    }
}
