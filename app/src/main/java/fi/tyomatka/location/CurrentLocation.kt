package fi.tyomatka.location

import android.content.Context
import android.location.Location
import fi.tyomatka.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tuore sijainti ilman karttaa: tuore tunnettu piste heti, muuten uusi haku. Ilman lupaa tai sijaintia null. */
suspend fun currentLocation(context: Context): GeoPoint? =
    withContext(Dispatchers.Default) { DeviceLocation.of(context).current()?.toGeoPoint() }

/** Paras tunnettu sijainti ilman uutta hakua: hakujen painotus ei saa odottaa sijaintia. */
suspend fun lastLocation(context: Context): GeoPoint? =
    withContext(Dispatchers.Default) { DeviceLocation.of(context).last()?.toGeoPoint() }

private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
