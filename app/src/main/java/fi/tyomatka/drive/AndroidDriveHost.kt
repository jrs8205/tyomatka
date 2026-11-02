package fi.tyomatka.drive

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import fi.tyomatka.R
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.nav.RouteFailure
import fi.tyomatka.location.currentLocation
import fi.tyomatka.service.NavigationForegroundService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDriveHost @Inject constructor(
    private val application: Application,
) : DriveHost {

    override fun message(kind: DriveMessage, detail: String): String = when (kind) {
        DriveMessage.NAV_START_FAILED -> application.getString(R.string.nav_error)
    }

    override fun routingFailureText(failure: RouteFailure): String {
        val reason = when (failure) {
            RouteFailure.Unavailable, is RouteFailure.RateLimited -> R.string.routing_reason_unavailable
            RouteFailure.NoRoute -> R.string.routing_reason_no_route
            RouteFailure.TooLong -> R.string.routing_reason_too_long
            RouteFailure.BadConfig -> R.string.routing_reason_bad_config
            RouteFailure.NoLocation -> R.string.routing_reason_no_location
        }
        return application.getString(R.string.routing_failed, application.getString(reason))
    }

    override fun startNavigationService() {
        ContextCompat.startForegroundService(
            application,
            Intent(application, NavigationForegroundService::class.java),
        )
    }

    override fun stopNavigationService() {
        application.stopService(Intent(application, NavigationForegroundService::class.java))
    }

    override suspend fun lastKnownLocation(): GeoPoint? = currentLocation(application)
}
