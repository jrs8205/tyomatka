package fi.tyomatka.nav.ferrostar

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import fi.tyomatka.BuildConfig
import fi.tyomatka.core.nav.InstructionText
import fi.tyomatka.core.nav.SpeechGate
import fi.tyomatka.core.nav.SpeechQueueGuard
import fi.tyomatka.ui.settings.pickVoice
import fi.tyomatka.ui.settings.voiceOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.SpokenInstruction
import java.util.Locale

/**
 * Puheohjeet puhelimen kielellä Androidin puhesynteesillä. Kääre Ferrostarin [AndroidTtsObserver]in ympärille, joka
 * pyytää äänen kohdistuksen navigointiäänenä: musiikki hiljenee ohjeen ajaksi ja ääni kulkee sinne, minne Android
 * navigointiäänen ohjaa (kaiutin, kuulokkeet, auton Bluetooth). Teksti korjataan [InstructionText]illä ennen puhetta,
 * ja jokaisen puhutun ohjeen kanssa värisytetään kerran. [speechGate] päättää hetken mukaan, puhutaanko ohje heti,
 * lykätäänkö se (reitin vaihdon hiljainen jakso) vai hylätäänkö (pysäytetty opastus, poikkeama, uudelleenreititys).
 * Ilman kielen puheääntä ei puhuta eikä värisytetä, ja [missingVoice] kertoo sen käyttöliittymälle.
 */
class GuidanceVoice(context: Context, private val vibrate: () -> Unit) : SpokenInstructionObserver, AndroidTtsStatusListener {
    private val tts = AndroidTtsObserver(context.applicationContext, statusObserver = this)

    /** Ferrostar kutsuu tarkkailijaa omasta säikeestään; lykkäys ja tila elävät pääsäikeessä. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var languageTag = "en-US"

    @Volatile
    private var voiceReady = false
    private var initDone: CompletableDeferred<Unit>? = null
    private var pending: SpokenInstruction? = null
    private var pendingJob: Job? = null

    /** Oman ilmoituksen suoja: ytimen pysäytys kutsuu [stopAndClearQueue]a heti perään, eikä se saa katkaista lausetta. */
    @Volatile
    private var protectUntilMs = 0L

    /** Reitin vaihto: ytimen `replaceRoute` tyhjentää jonon, mutta kesken oleva lause saa jatkua (kerran). */
    @Volatile
    private var keepSpeechOnce = false

    /** Jonossa voi olla vanhan reitin ohjeita: silloin lause ei saa jatkua, vaan jono pysäytetään. */
    private val queueGuard = SpeechQueueGuard()

    fun keepSpeechOnNextStop() {
        keepSpeechOnce = true
    }

    var speechGate: () -> SpeechGate = { SpeechGate.Allow }

    private val _missingVoice = MutableStateFlow<String?>(null)

    /** Kielitunniste, jolle puhelimesta puuttuu puheääni; null, kun ääni on käytettävissä tai sitä ei ole vielä tutkittu. */
    val missingVoice: StateFlow<String?> = _missingVoice.asStateFlow()

    /**
     * Käynnistää puhesynteesin ja odottaa sen valmistumista enintään [INIT_TIMEOUT_MS], jotta ensimmäinen ohje ei
     * laukea ennen kuin moottori on valmis. Laitteella 19.10. ensimmäinen ohje katkesi, kun moottori oli vasta
     * käynnistynyt.
     */
    suspend fun start(languageTag: String) {
        this.languageTag = languageTag
        _missingVoice.value = null
        voiceReady = false
        val done = CompletableDeferred<Unit>()
        initDone = done
        tts.start()
        withTimeoutOrNull(INIT_TIMEOUT_MS) { done.await() }
    }

    fun shutdown() {
        voiceReady = false
        scope.launch { clearPending() }
        tts.shutdown()
    }

    override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
        try {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                _missingVoice.value = languageTag
                return
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val locale = Locale.forLanguageTag(languageTag)
            if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                _missingVoice.value = languageTag
                return
            }
            tts.language = locale
            // Puuttuva tai käyttökelvoton valittu ääni palautuu oletukseen.
            pickVoice(preferredVoice, tts.voiceOptions(locale.language))?.let { picked ->
                tts.voices.orEmpty().firstOrNull { it.name == picked.name }?.let { tts.voice = it }
            }
            voiceReady = true
            // Lämmittely äänettömällä lausahduksella: ensimmäinen synteesi lataa äänen, ja ilman tätä päivän
            // ensimmäinen ohje jäi laitteella kesken.
            val silent = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f) }
            tts.speak(WARMUP_TEXT, TextToSpeech.QUEUE_ADD, silent, WARMUP_ID)
        } finally {
            initDone?.complete(Unit)
        }
    }

    override fun onTtsShutdownAndRelease() = Unit

    override fun onTtsSpeakError(utteranceId: String, status: Int) {
        Log.w(TAG, "Puheohje epäonnistui: $status")
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        if (!voiceReady) return
        scope.launch { handle(spokenInstruction) }
    }

    private fun handle(instruction: SpokenInstruction) {
        when (val gate = speechGate()) {
            SpeechGate.Allow -> speak(instruction)
            is SpeechGate.Defer -> defer(instruction, gate.waitMs)
            SpeechGate.Drop -> Unit
        }
    }

    /**
     * Oma ilmoitus puhesäännöstä riippumatta (saapuminen): kesken oleva ohje keskeytetään, ja lause suojataan
     * [ANNOUNCE_PROTECT_MS]:n ajan ytimen pysäytyksen tyhjennykseltä. Ilman valmista ääntä ei tehdä mitään.
     */
    fun announce(text: String) {
        if (!voiceReady) return
        scope.launch {
            clearPending()
            tts.stopAndClearQueue()
            protectUntilMs = SystemClock.elapsedRealtime() + ANNOUNCE_PROTECT_MS
            queueGuard.onHandedToSpeech(SystemClock.elapsedRealtime())
            if (BuildConfig.DEBUG) Log.i(TIMING_TAG, "ilmoitus t=${SystemClock.elapsedRealtime()} teksti=$text")
            tts.onSpokenInstructionTrigger(SpokenInstruction(text, null, 0.0, java.util.UUID.randomUUID()))
            vibrate()
        }
    }

    private fun speak(instruction: SpokenInstruction) {
        clearPending()
        val text = InstructionText.forSpeech(instruction.text, languageTag)
        if (BuildConfig.DEBUG) {
            Log.i(TIMING_TAG, "puhe t=${SystemClock.elapsedRealtime()} laukaisumatka=${instruction.triggerDistanceBeforeManeuver} teksti=$text")
        }
        queueGuard.onHandedToSpeech(SystemClock.elapsedRealtime())
        tts.onSpokenInstructionTrigger(instruction.copy(text = text))
        vibrate()
    }

    /** Lykätty ohje puhutaan jakson päätyttyä, jos se on yhä viimeisin eikä tilanne ole muuttunut. */
    private fun defer(instruction: SpokenInstruction, waitMs: Long) {
        pendingJob?.cancel()
        pending = instruction
        pendingJob = scope.launch {
            delay(waitMs)
            val current = pending ?: return@launch
            if (current.utteranceId != instruction.utteranceId) return@launch
            handle(current)
        }
    }

    private fun clearPending() {
        pendingJob?.cancel()
        pendingJob = null
        pending = null
    }

    override fun stopAndClearQueue() {
        scope.launch { clearPending() }
        if (keepSpeechOnce) {
            keepSpeechOnce = false
            if (queueGuard.keepsSpeech(SystemClock.elapsedRealtime())) return
        }
        if (SystemClock.elapsedRealtime() < protectUntilMs) return
        tts.stopAndClearQueue()
    }

    override fun setMuted(isMuted: Boolean) = tts.setMuted(isMuted)

    override val muteState: StateFlow<Boolean>
        get() = tts.muteState

    companion object {
        private const val TAG = "GuidanceVoice"
        private const val TIMING_TAG = "GuidanceTiming"
        private const val VIBRATION_MS = 100L
        private const val INIT_TIMEOUT_MS = 3_000L
        private const val ANNOUNCE_PROTECT_MS = 6_000L
        private const val WARMUP_TEXT = "Ok"
        private const val WARMUP_ID = "lammittely"

        /** Asetuksista: ääni otetaan käyttöön seuraavassa ajossa, värinä heti. */
        @Volatile
        var preferredVoice: String? = null

        @Volatile
        var vibrationEnabled = true

        /** Lyhyt värinä puheohjeen kanssa, kuten Googlen navigoinnissa. */
        fun vibration(context: Context): () -> Unit {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val effect = VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            return { if (vibrationEnabled) vibrator.vibrate(effect) }
        }
    }
}
