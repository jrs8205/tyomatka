package fi.tyomatka.core.plan

/**
 * Huoltostoppi on karttakohteesta (huoltoasema, lataus, kaasu, lepopaikka) lisätty stoppi. Se tunnistetaan
 * `placeId`-etuliitteestä `service:`, jota mikään osoitehaku ei tuota (Photonin osoitteet ovat `osm:W:123`, kierros
 * 19), ja se ei kuulu tallennettuun pohjaan eikä pohjan ja päivän vertailuun.
 */
object ServiceStops {
    const val PREFIX = "service:"

    /** Karttakohteen tunnuksen etuliite kokonaisuudessaan: `service:osm:n123`. */
    private const val POI_PREFIX = PREFIX + "osm:"

    fun isServiceStop(placeId: String?): Boolean = placeId?.startsWith(PREFIX) == true

    fun placeId(poiId: String): String = POI_PREFIX + poiId

    /** Karttakohteen tunnus stopin `placeId`stä; null, kun stoppi ei ole karttakohteesta. */
    fun poiId(placeId: String?): String? = placeId?.takeIf { it.startsWith(POI_PREFIX) }?.removePrefix(POI_PREFIX)?.takeIf { it.isNotEmpty() }
}
