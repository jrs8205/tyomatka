package fi.tyomatka.core.api

import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.PlaceDetails
import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.SuggestionKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.round
import kotlin.coroutines.cancellation.CancellationException

/**
 * Osoitehaku ja Overturen yrityshaku rinnakkain (Google pois 28.10.). Kun toinen valmistuu tuloksin, toista odotetaan
 * enintään [graceMs]; tyhjä tai epäonnistunut vastaus ei käynnistä odotusta, ja myöhästynyt osio jää pois tältä haulta.
 * Yrityshaku katkaistaan aina [businessCapMs]:ssä. Numero hakusanassa tuo osoitteet ensin, muuten yritykset. Sovellus
 * hakee vain Suomesta, joten yrityksiä haetaan sijainnista riippumatta. Valitun yrityksen osoite haetaan [resolver]illa
 * muistissa olevista viimeisistä yrityksistä.
 */
class BusinessAddressSearch(
    private val addresses: PlaceSearch,
    private val businesses: BusinessSource,
    private val resolver: BusinessPlaceResolver,
    private val graceMs: Long = 1_000,
    private val businessCapMs: Long = 5_000,
) : PlaceSearch {
    /** Viimeiset [REMEMBERED] näytettyä yritystä tunnisteittain, vanhin ensin. */
    private val remembered = LinkedHashMap<String, Business>()

    override suspend fun autocomplete(input: String, biasLocation: GeoPoint?): List<PlaceSuggestion> {
        val near = biasLocation?.forSearch()
        var found: Result<List<PlaceSuggestion>>? = null
        var companies: Result<List<Business>>? = null
        coroutineScope {
            val hit = CompletableDeferred<Unit>()
            val addressJob = launch {
                found = attempt { addresses.autocomplete(input, near) }
                if (found?.getOrNull().orEmpty().isNotEmpty()) hit.complete(Unit)
            }
            val businessJob = launch {
                companies = attempt { withTimeoutOrNull(businessCapMs) { businesses.search(input, near) }.orEmpty() }
                if (companies?.getOrNull().orEmpty().isNotEmpty()) hit.complete(Unit)
            }
            val both = launch {
                addressJob.join()
                businessJob.join()
            }
            select<Unit> {
                hit.onAwait {}
                both.onJoin {}
            }
            withTimeoutOrNull(graceMs) { both.join() }
            // Myöhästynyt osio perutaan, jottei sen vastaus jää kesken eikä päädy myöhempään hakuun.
            addressJob.cancel()
            businessJob.cancel()
            both.cancel()
        }
        val addressList = found?.getOrNull().orEmpty()
        val businessList = companies?.getOrNull().orEmpty()
        if (addressList.isEmpty() && businessList.isEmpty()) found?.exceptionOrNull()?.let { throw it }
        val businessSuggestions = businessList.map { remember(it) }
        return if (input.any { it.isDigit() }) addressList + businessSuggestions else businessSuggestions + addressList
    }

    private fun remember(business: Business): PlaceSuggestion {
        synchronized(remembered) {
            remembered.remove(business.id)
            remembered[business.id] = business
            while (remembered.size > REMEMBERED) remembered.remove(remembered.keys.first())
        }
        return PlaceSuggestion(
            placeId = BusinessClient.PREFIX + business.id,
            primaryText = business.name,
            secondaryText = business.addressLine,
            source = BusinessClient.SOURCE,
            location = business.location,
            kind = SuggestionKind.BUSINESS,
        )
    }

    override suspend fun placeDetails(placeId: String): PlaceDetails {
        if (!BusinessClient.isBusiness(placeId)) return addresses.placeDetails(placeId)
        val business = synchronized(remembered) { remembered[placeId.removePrefix(BusinessClient.PREFIX)] }
            ?: throw StaleSuggestionException()
        return resolver.resolve(business)
    }

    override suspend fun geocodeText(addressLine: String, biasLocation: GeoPoint?): PlaceDetails? =
        addresses.geocodeText(addressLine, biasLocation?.forSearch())

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val REMEMBERED = 200
    }
}

/**
 * Tulosten painotukseen riittää noin kilometrin tarkkuus, joten tarkka sijainti ei lähde palveluille jokaisella
 * näppäilyllä. Sijainti on käyttäjän oma; valitun kohteen piste kulkee muuta kautta (`BusinessPlaceResolver`).
 */
private fun GeoPoint.forSearch() = GeoPoint(round(lat * 100) / 100, round(lng * 100) / 100)
