package fi.tyomatka.core.nav

/**
 * Milloin ajon alussa varoitetaan hiljaisesta media-äänestä: puheohjeet kulkevat mediavirrassa, ja
 * taso 12/15 voi kuulostaa hiljaiselta. Varoitetaan, kun taso on enintään 80 % maksimista.
 */
object VolumeHint {
    const val WARN_AT_OR_BELOW = 0.8

    fun shouldWarn(level: Int, max: Int): Boolean = max > 0 && level <= max * WARN_AT_OR_BELOW
}
