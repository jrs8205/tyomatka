package fi.tyomatka.diag

/**
 * Kehitysvaiheen diagnostiikkatapahtumat. Pääkoodi kutsuu
 * [event]-funktiota; kirjoittimen asentaa vain debug-buildin DiagStartup (tiedostoon ja Androidin lokiin).
 * Ilman asennusta (julkaisuversio, JVM-testit) tapahtumat ohitetaan.
 */
object Diag {
    const val TAG = "Diag"

    @Volatile
    private var writer: ((tag: String, line: String) -> Unit)? = null

    /** Tosi, kun kirjoitin on asennettu: kalliit rivit (sijainti, muotoilu) rakennetaan vain silloin. */
    val enabled: Boolean get() = writer != null

    /** Harvennuksen kello; debug-build asentaa monotonisen kellon, JVM-testit käyttävät seinäkelloa. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** Kirjoitin saa tunnisteen ja rivin "kategoria: viesti"; null poistaa kirjoittimen. */
    fun install(writer: ((tag: String, line: String) -> Unit)?) {
        this.writer = writer
    }

    fun event(category: String, message: String) {
        writer?.invoke(TAG, "$category: $message")
    }
}
