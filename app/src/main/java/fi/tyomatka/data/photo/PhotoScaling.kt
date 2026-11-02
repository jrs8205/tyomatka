package fi.tyomatka.data.photo

/**
 * Tallennettavan kuvan pitkä sivu: kuitin teksti pysyy luettavana, ja kuva vie tyypillisesti 0,2–0,5 Mt.
 * Kuvakaappaus (1080 × 2400) pienenee vain vähän.
 */
const val PHOTO_MAX_SIDE = 2048

/** Tallennuksen JPEG-laatu. */
const val PHOTO_QUALITY = 85

/**
 * Purun pienennyskerroin (kahden potenssi): suurin, jolla pitkä sivu on vielä vähintään [maxSide]. Näin 50 Mpx:n
 * kamerakuva ei pura täyteen kokoonsa muistiin, eikä tallennettava kuva menetä tarkkuutta.
 */
fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
    val long = maxOf(width, height)
    var size = 1
    while (long / (size * 2) >= maxSide) size *= 2
    return size
}

/** Purun jälkeinen pienennys: pitkä sivu enintään [maxSide]; pienempää kuvaa ei suurenneta. */
fun photoScale(width: Int, height: Int, maxSide: Int): Float {
    val long = maxOf(width, height)
    return if (long <= maxSide) 1f else maxSide.toFloat() / long
}

/** EXIF-suunta kuvaan: kääntö myötäpäivään ja sen jälkeen vaakapeilaus. */
data class ExifTransform(val degrees: Int, val mirror: Boolean) {
    val isIdentity: Boolean get() = degrees == 0 && !mirror
}

/** EXIF-suunnan (1–8) muunnos; tuntematon tai puuttuva on kuva sellaisenaan. */
fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
    2 -> ExifTransform(0, true)
    3 -> ExifTransform(180, false)
    4 -> ExifTransform(180, true)
    5 -> ExifTransform(90, true)
    6 -> ExifTransform(90, false)
    7 -> ExifTransform(270, true)
    8 -> ExifTransform(270, false)
    else -> ExifTransform(0, false)
}
