package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cos

/** Lähialueen säde: sama kuin vanhan sovelluksen 30 km:n painotusympyrä. */
internal const val NEARBY_KM = 30.0

/** Suorakulmio, joka ulottuu [km] kilometriä joka suuntaan pisteestä. */
internal fun boxAround(center: GeoPoint, km: Double = NEARBY_KM): GeoBox {
    val dLat = km / KM_PER_DEGREE
    val dLon = km / (KM_PER_DEGREE * cos(Math.toRadians(center.lat)))
    return GeoBox(center.lng - dLon, center.lat - dLat, center.lng + dLon, center.lat + dLat)
}

/**
 * Hakee lähialueen ja koko alueen rinnakkain ja näyttää lähialueen osumat ensin.
 * Palvelut painottavat suuria kaupunkeja niin, ettei pelkkä sijaintipainotus nosta
 * lähintä osumaa kärkeen. Kummankin haun virhe jää huomiotta, jos toinen löysi jotain.
 */
internal suspend fun <T> nearbyFirst(
    limit: Int,
    key: (T) -> String,
    nearby: suspend () -> List<T>,
    wide: suspend () -> List<T>,
): List<T> = coroutineScope {
    val near = async {
        try {
            nearby()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val all = try {
        wide()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Lähiosumat näytetään, vaikka laaja haku epäonnistui.
        near.await().ifEmpty { throw e }
    }
    (near.await() + all).distinctBy(key).take(limit)
}

private const val KM_PER_DEGREE = 111.32
