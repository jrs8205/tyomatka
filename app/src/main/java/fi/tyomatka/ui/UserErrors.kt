package fi.tyomatka.ui

import androidx.annotation.StringRes
import fi.tyomatka.R
import fi.tyomatka.core.api.ApiException
import fi.tyomatka.core.api.PoiUnavailableException
import fi.tyomatka.core.api.ReportsUnavailableException
import fi.tyomatka.core.api.RouteNotFoundException
import fi.tyomatka.core.api.StaleSuggestionException
import fi.tyomatka.data.repo.BackupRejectedException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Poikkeuksen teksti kuljettajalle hänen kielellään. Poikkeuksen oma viesti kuuluu diagnostiikkaan, ei ruudulle. */
object UserErrors {
    /** [online] = false tiedostotyössä, jossa IO-virhe ei tarkoita verkkoa. */
    @StringRes
    fun messageRes(error: Throwable, online: Boolean = true): Int = when {
        error is BackupRejectedException -> error.messageRes
        error is RouteNotFoundException -> R.string.route_not_found
        error is PoiUnavailableException -> R.string.poi_unavailable
        error is ReportsUnavailableException -> R.string.reports_unavailable
        error is StaleSuggestionException -> R.string.suggestion_stale
        // Kirjautumissivun HTML:ää ei voi jäsentää: vika on palvelussa, ei käyttäjän tiedoissa.
        online && (error is ApiException || error is SerializationException) -> R.string.error_service
        online && error is IOException -> R.string.search_network_error
        error is IOException -> R.string.error_file
        error is IllegalArgumentException -> R.string.error_invalid_data
        else -> R.string.error_generic
    }
}
