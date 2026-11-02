package fi.tyomatka.core.api

/** Sovelluksen maat: Suomi ja Ahvenanmaa (ISO 3166 -koodit). */
object Finland {
    val COUNTRIES = setOf("FI", "AX")
}

/** Suorakulmio asteina. */
data class GeoBox(val west: Double, val south: Double, val east: Double, val north: Double)
