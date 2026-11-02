package fi.tyomatka.ui.history

import androidx.compose.runtime.saveable.Saver
import fi.tyomatka.data.db.FuelFillupEntity
import java.util.concurrent.atomic.AtomicLong

/**
 * Tankkausdialogin kohde: uusi tai olemassa oleva tankkaus (tunniste; kirjaus itse haetaan tuoreena tilasta) ja
 * avauksen tunniste. Lomake koostetaan kohteen avaimella (`key(target)`), jotta lomakkeiden tallenteet eivät sekoitu
 * palautuksessa: data classin hashCode tulee kentistä, joten palautettu avaus löytää oman tallenteensa. Jokainen avaus
 * (napautus, jaettu kuva, Tänään-kortti) saa oman tunnisteen, joten uusi avaus ei koskaan kuluta aiemman avauksen
 * tallennetta: latausodotuksessa peruttu palautus jättää lomakkeen syötteet, kuvat ja kameran käynnistimen avaimen
 * rekistereihin kuluttamatta, ja sama avain avaisi lomakkeen myöhemmin hylätyin tiedoin.
 */
internal sealed interface FuelDialogTarget {
    val token: Long

    /** Uusi kirjaus; [epochDay] esitäyttää päivän, kun tankkaus kirjataan jälkikäteen päiväkortista. */
    data class New(val epochDay: Long? = null, override val token: Long = newDialogToken()) : FuelDialogTarget
    data class Edit(val id: Long, override val token: Long = newDialogToken()) : FuelDialogTarget
}

/** Avaustunnisteet nousevina; alku kellosta, ettei edellisen prosessin palautettu avaus osu samaan tunnisteeseen. */
private val dialogTokens = AtomicLong(System.nanoTime())

private fun newDialogToken(): Long = dialogTokens.incrementAndGet()

/** Mitä kohteelle tehdään: lomake avataan, kirjausten latautumista odotetaan tai kohde nollataan. */
internal sealed interface FuelDialogStep {
    data class Open(val existing: FuelFillupEntity?, val epochDay: Long? = null) : FuelDialogStep
    data object Wait : FuelDialogStep
    data object Reset : FuelDialogStep
}

/**
 * Palautuksessa muokattava kirjaus latautuu vasta hetken päästä: sillä välin odotetaan, ettei lomake avaudu uutena
 * eikä sivu ole käytettävissä. Kun kirjaukset on ladattu ([loaded]) eikä kirjausta löydy, kohde nollataan, ettei
 * lomake jää odottamaan pysyvästi.
 */
internal fun fuelDialogStep(target: FuelDialogTarget, fillups: List<FuelFillupEntity>, loaded: Boolean): FuelDialogStep =
    when (target) {
        is FuelDialogTarget.New -> FuelDialogStep.Open(null, target.epochDay)
        is FuelDialogTarget.Edit -> {
            val existing = fillups.firstOrNull { it.id == target.id }
            when {
                existing != null -> FuelDialogStep.Open(existing)
                loaded -> FuelDialogStep.Reset
                else -> FuelDialogStep.Wait
            }
        }
    }

/** Uuden kirjauksen merkki tallennuksessa: Room antaa riveille tunnisteet yhdestä alkaen. */
private const val NEW_FILLUP = 0L

/** Uuden kirjauksen tallenne ilman esitäytettyä päivää. */
private const val NO_DAY = Long.MIN_VALUE

/**
 * Dialogin kohde prosessin yli (Android voi lopettaa prosessin kameran ollessa auki): avoin lomake
 * palautuu, ja muokattavasta kirjauksesta tallennetaan vain tunniste, jotta lomake ei palaudu uuden kirjauksen
 * lomakkeeksi, vaikka kirjaukset latautuvat vasta myöhemmin. Avauksen tunniste tallennetaan mukana, jotta palautettu
 * avaus saa saman avaimen kuin ennen. Kelvoton tallenne (toisen version jäämä) antaa null: lomake on silloin kiinni.
 */
internal val FuelDialogTargetSaver: Saver<FuelDialogTarget?, Any> = Saver(
    save = { target ->
        when (target) {
            null -> null
            is FuelDialogTarget.New -> arrayListOf(NEW_FILLUP, target.token, target.epochDay ?: NO_DAY)
            is FuelDialogTarget.Edit -> arrayListOf(target.id, target.token)
        }
    },
    restore = { saved ->
        val list = saved as? List<*>
        val id = list?.getOrNull(0) as? Long
        val token = list?.getOrNull(1) as? Long
        val day = (list?.getOrNull(2) as? Long)?.takeIf { it != NO_DAY }
        when {
            id == null || token == null -> null
            id == NEW_FILLUP -> FuelDialogTarget.New(day, token)
            else -> FuelDialogTarget.Edit(id, token)
        }
    },
)
