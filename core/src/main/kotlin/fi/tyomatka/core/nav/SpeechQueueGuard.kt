package fi.tyomatka.core.nav

/**
 * Päättää reitin vaihdossa, saako puhesynteesin jonon jättää rauhaan. Ferrostarin puhetoteutus jonottaa ohjeet
 * (`QUEUE_ADD`), eikä yksittäistä ohjetta voi poistaa jonosta: joko kaikki pysäytetään tai mikään ei. Kesken oleva
 * lause saa jatkua (puhe pätki), mutta jos edellinen lause ei ole ehtinyt valmistua ennen
 * seuraavaa, jonossa on todennäköisesti vanhan reitin ohje, ja silloin pysäytys on pienempi paha. Lauseen kestoa
 * ei tiedetä, joten se arvioidaan [maxUtteranceMs]:ksi. Ajat ovat laitteen käynnistyskellosta.
 */
class SpeechQueueGuard(private val maxUtteranceMs: Long = MAX_UTTERANCE_MS) {
    private var last: Long? = null
    private var previous: Long? = null

    @Synchronized
    fun onHandedToSpeech(nowMs: Long) {
        previous = last
        last = nowMs
    }

    /** Vain yksi, vielä kesken oleva lause: jono saa jäädä. */
    @Synchronized
    fun keepsSpeech(nowMs: Long): Boolean {
        val latest = last ?: return false
        if (nowMs - latest >= maxUtteranceMs) return false
        val before = previous ?: return true
        return latest - before >= maxUtteranceMs
    }

    companion object {
        const val MAX_UTTERANCE_MS = 4_000L
    }
}
