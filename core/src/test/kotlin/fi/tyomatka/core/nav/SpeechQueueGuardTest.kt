package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechQueueGuardTest {

    @Test
    fun `yksi tuore lause saa jatkua reitin vaihdossa`() {
        val guard = SpeechQueueGuard(maxUtteranceMs = 4_000)
        guard.onHandedToSpeech(nowMs = 10_000)
        assertTrue(guard.keepsSpeech(nowMs = 11_500))
    }

    @Test
    fun `vanha lause on jo puhuttu joten jonoa ei tarvitse säästää`() {
        val guard = SpeechQueueGuard(maxUtteranceMs = 4_000)
        guard.onHandedToSpeech(nowMs = 10_000)
        assertFalse(guard.keepsSpeech(nowMs = 15_000))
        assertFalse(SpeechQueueGuard().keepsSpeech(nowMs = 1_000))
    }

    @Test
    fun `jonossa on todennäköisesti vanhan reitin ohjeita kun lauseet tulivat peräkkäin`() {
        // Kaksi ohjetta 4 s:n sisällä: jälkimmäinen on QUEUE_ADD-jonossa, ja se on vanhan reitin ohje → pysäytetään.
        val guard = SpeechQueueGuard(maxUtteranceMs = 4_000)
        guard.onHandedToSpeech(nowMs = 10_000)
        guard.onHandedToSpeech(nowMs = 11_000)
        assertFalse(guard.keepsSpeech(nowMs = 12_000))
        // Edellinen ehti valmistua ennen seuraavaa: vain yksi lause kesken.
        val spaced = SpeechQueueGuard(maxUtteranceMs = 4_000)
        spaced.onHandedToSpeech(nowMs = 10_000)
        spaced.onHandedToSpeech(nowMs = 15_000)
        assertTrue(spaced.keepsSpeech(nowMs = 16_000))
    }
}
