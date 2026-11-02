package fi.tyomatka.ui.history.forms

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import fi.tyomatka.data.NOTE_MAX_LENGTH
import fi.tyomatka.data.normalizeNote
import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.repo.APP_CURRENCY
import fi.tyomatka.data.repo.carFuel
import fi.tyomatka.data.repo.fillupEpochSec
import fi.tyomatka.data.repo.fillupKmBounds
import fi.tyomatka.data.repo.fillEventOf
import fi.tyomatka.data.repo.fuelIntervals
import fi.tyomatka.data.repo.normalizePlate
import fi.tyomatka.ui.history.FuelEntry
import fi.tyomatka.ui.history.FuelField
import fi.tyomatka.ui.history.FuelPrices
import fi.tyomatka.ui.history.editableDecimal
import fi.tyomatka.ui.history.parseDecimal
import fi.tyomatka.ui.history.resolveFuelPrices
import java.time.Instant
import java.time.ZoneId

/**
 * Lomakkeen taustatiedot: kirjaukset, autot ja muokattavan kirjauksen tallennetut kuvat. Kiireinen lomake (tallennus
 * kesken tai valmis mutta vielä sulkeutumatta) pitää tallennusta edeltäneet tiedot: Room-virta toisi juuri tallennetun
 * rivin lomakkeelle, jolloin esikatselu ja lukemaraja vaihtuisivat (uudella kirjauksella "edellinen tankkaus" olisi
 * kirjaus itse) ja kuvarivillä välähtäisi tuplia.
 */
class FuelFormData(
    fillups: List<FuelFillupEntity>,
    cars: Map<String, CarEntity>,
    storedPhotos: List<FuelPhotoEntity>,
) {
    var fillups: List<FuelFillupEntity> = fillups
        private set
    var cars: Map<String, CarEntity> = cars
        private set
    var storedPhotos: List<FuelPhotoEntity> = storedPhotos
        private set

    /** Tuoreet tiedot otetaan käyttöön vain, kun lomake ei ole kiireinen. */
    fun refresh(busy: Boolean, fillups: List<FuelFillupEntity>, cars: Map<String, CarEntity>, storedPhotos: List<FuelPhotoEntity>): FuelFormData {
        if (!busy) {
            this.fillups = fillups
            this.cars = cars
            this.storedPhotos = storedPhotos
        }
        return this
    }
}

/** Lomakkeen ulkopuolinen tieto: muut tankkaukset rajoja varten, muokattava rivi, nykyhetki ja autot. */
data class FuelFormContext(
    val fillups: List<FuelFillupEntity>,
    val existing: FuelFillupEntity?,
    val nowEpochSec: Long,
    val zone: ZoneId,
    /** Autoprofiilit rekisterinumeron mukaan: lajin oletus. */
    val cars: Map<String, CarEntity>,
)

/** Kulutus edellisestä täydestä saman lajin tankkauksesta; välissä olevat vajaat mukana. */
data class FuelPreview(
    val kind: FuelKind,
    val consumption: Double,
    val costPerKm: Double?,
    val partialCount: Int,
    val currency: String?,
)

/**
 * Lomakkeen tankkauskerta ([fillEventOf]): [merged] samalla lukemalla on muitakin tankkauksia, [full] kerrassa on
 * täysi. Täysi kerta ilman väliä aloittaa ketjun: sen määrät ovat lähtötaso, ja kulutus lasketaan seuraavasta.
 */
data class FuelFormEvent(val merged: Boolean, val full: Boolean)

/**
 * Tankkauslomakkeen syötteet ja niistä johdetut arvot. Kaksi hintakenttää määrää
 * kolmannen ([resolveFuelPrices]); lukema ei saa alittaa saman auton saman lajin edellistä
 * eikä ylittää seuraavaa tankkausta ([fillupKmBounds]).
 */
data class FuelForm(
    val context: FuelFormContext,
    val plate: String,
    val epochDay: Long,
    val km: String,
    val prices: FuelPrices,
    /** Viimeksi kirjoitettu hintakenttä: litrojen muuttuessa lasketaan toinen. */
    val priceAnchor: FuelField? = null,
    val kind: FuelKind,
    val fullTank: Boolean = true,
    /** Uusi tankkaus [APP_CURRENCY]; muokattava säilyttää tallennetun valuuttansa. */
    val currency: String,
    /** Itse valittu laji ei seuraa autoa; muokattava tankkaus säilyttää omansa. */
    val kindChosen: Boolean = false,
    /** Kirjauksen lisätiedot sellaisenaan kentästä (enintään [NOTE_MAX_LENGTH]); tallennus normalisoi. */
    val note: String = "",
) {
    val normalizedPlate: String? get() = normalizePlate(plate)
    val kmValue: Long? get() = km.toLongOrNull()
    val liters: Double? get() = parseDecimal(prices.liters)
    val epochSec: Long get() = fillupEpochSec(epochDay, context.existing?.epochSec, context.nowEpochSec, context.zone)

    private val bounds
        get() = normalizedPlate?.let {
            fillupKmBounds(context.fillups, it, context.existing?.id, epochSec, kmValue, kind)
        }
    val previousKm: Long? get() = bounds?.previousKm
    /**
     * Saman auton saman lajin aiempi täysi tankkaus löytyy: esikatselu on mahdollinen heti, kun litrat ja lukema on
     * syötetty. Pelkistä vajaista ei voi laskea kulutusta.
     */
    val hasPrevious: Boolean
        get() {
            val plate = normalizedPlate ?: return false
            return context.fillups.any {
                it.id != context.existing?.id && it.licensePlate == plate && it.kind == kind && it.fullTank &&
                    it.epochSec <= epochSec
            }
        }
    val nextKm: Long? get() = bounds?.nextKm
    val tooSmall: Boolean
        get() {
            val value = kmValue ?: return false
            val previous = previousKm ?: return false
            return value < previous
        }
    val tooLarge: Boolean
        get() {
            val value = kmValue ?: return false
            val next = nextKm ?: return false
            return value > next
        }

    private val validPrices: Boolean
        get() = listOf(prices.total, prices.perLiter).all { it.isBlank() || parseDecimal(it) != null }

    val canSave: Boolean
        get() {
            val value = kmValue ?: return false
            val liters = liters ?: return false
            return normalizedPlate != null && value >= 0 && !tooSmall && !tooLarge && liters > 0 && validPrices
        }

    val isPartial: Boolean get() = !fullTank

    /**
     * Lomakkeen tankkauskerran kulutus edellisestä täydestä samalla laskennalla kuin kortit. Kertaan kuuluvat myös
     * saman lukeman vajaa ja aiempi täysi ([FuelInterval.eventIds]); välissä olevalle vajaalle ei esikatselua.
     */
    val preview: FuelPreview?
        get() {
            val candidate = candidate() ?: return null
            val others = context.fillups.filter { it.id != candidate.id }
            val interval = fuelIntervals(others + candidate).firstOrNull { candidate.id in it.eventIds } ?: return null
            return FuelPreview(interval.kind, interval.consumption, interval.costPerKm, interval.partialCount, interval.currency)
        }

    /** Tankkauskerta, johon lomakkeen tankkaus kuuluu; null, kun määrä tai lukema puuttuu. */
    val event: FuelFormEvent?
        get() {
            val candidate = candidate() ?: return null
            val rows = fillEventOf(context.fillups.filter { it.id != candidate.id } + candidate, candidate.id)
            return FuelFormEvent(merged = rows.size > 1, full = rows.any { it.fullTank })
        }

    /** Lomakkeen tankkaus laskentaa varten; uusi rivi saa tunnisteen, jota ei ole kannassa. */
    private fun candidate(): FuelFillupEntity? {
        val plate = normalizedPlate ?: return null
        val km = kmValue ?: return null
        val liters = liters?.takeIf { it > 0 } ?: return null
        return FuelFillupEntity(
            id = context.existing?.id ?: PREVIEW_ID,
            epochSec = epochSec,
            licensePlate = plate,
            odometerKm = km,
            liters = liters,
            totalCost = parseDecimal(prices.total) ?: 0.0,
            pricePerLiter = parseDecimal(prices.perLiter) ?: 0.0,
            kind = kind,
            fullTank = fullTank,
            currency = currency,
        )
    }

    /** Valitsematon laji seuraa autoa: profiili, auton viimeisin polttoainetankkaus, muuten diesel. */
    private fun withDefaults(): FuelForm {
        val car = normalizedPlate
        return copy(
            kind = if (kindChosen) kind else car?.let { carFuel(it, context.cars, context.fillups) } ?: FuelKind.DIESEL,
        )
    }

    /** Taustatiedot päivittyvät (lataus, tuonti) syötteitä nollaamatta. */
    fun withFillups(fillups: List<FuelFillupEntity>, cars: Map<String, CarEntity> = context.cars) =
        copy(context = context.copy(fillups = fillups, cars = cars)).withDefaults()
    /**
     * Muokatun kirjauksen Tallenna aukeaa vasta, kun jokin syöte eroaa avatusta. Tausta (nykyhetki,
     * kirjaukset, autot) ja viimeksi kirjoitetun hintakentän merkintä eivät ole syötteitä.
     */
    fun isUnchanged(original: FuelForm): Boolean =
        copy(context = original.context, priceAnchor = null) == original.copy(priceAnchor = null)

    fun withPlate(value: String) = copy(plate = value).withDefaults()
    fun withDay(value: Long) = copy(epochDay = value)
    fun withKm(value: String) = copy(km = value)
    fun withNote(value: String) = copy(note = value.take(NOTE_MAX_LENGTH))
    fun withKind(value: FuelKind) = copy(kind = value, kindChosen = true)
    fun withFullTank(value: Boolean) = copy(fullTank = value)
    fun withLiters(value: String) =
        copy(prices = resolveFuelPrices(FuelField.LITERS, prices.copy(liters = value), priceAnchor))

    fun withTotal(value: String): FuelForm {
        val anchor = if (value.isNotBlank()) FuelField.TOTAL else priceAnchor
        return copy(prices = resolveFuelPrices(FuelField.TOTAL, prices.copy(total = value), anchor), priceAnchor = anchor)
    }

    fun withPerLiter(value: String): FuelForm {
        val anchor = if (value.isNotBlank()) FuelField.PER_LITER else priceAnchor
        return copy(prices = resolveFuelPrices(FuelField.PER_LITER, prices.copy(perLiter = value), anchor), priceAnchor = anchor)
    }

    fun entry(): FuelEntry? {
        if (!canSave) return null
        return FuelEntry(
            id = context.existing?.id,
            epochDay = epochDay,
            plate = requireNotNull(normalizedPlate),
            odometerKm = requireNotNull(kmValue),
            liters = requireNotNull(liters),
            totalCost = parseDecimal(prices.total) ?: 0.0,
            pricePerLiter = parseDecimal(prices.perLiter) ?: 0.0,
            kind = kind,
            fullTank = fullTank,
            currency = currency,
            note = normalizeNote(note),
        )
    }

    companion object {
        /**
         * Esikatselun uuden rivin tunniste. Tallennettu rivi saa suurimman tunnisteen, joten esikatselukin lajittuu
         * samanhetkisten ja samalukemaisten tankkausten viimeiseksi, eikä esikatselu eroa kortista.
         */
        private const val PREVIEW_ID = Long.MAX_VALUE

        /** [initialEpochDay] esitäyttää uuden kirjauksen päivän (tankkaus jälkikäteen menneelle päivälle). */
        fun initial(context: FuelFormContext, defaultPlate: String?, todayEpochDay: Long, initialEpochDay: Long? = null): FuelForm {
            val existing = context.existing
            return FuelForm(
                context = context,
                plate = existing?.licensePlate ?: defaultPlate ?: "",
                epochDay = existing?.let { Instant.ofEpochSecond(it.epochSec).atZone(context.zone).toLocalDate().toEpochDay() }
                    ?: initialEpochDay ?: todayEpochDay,
                km = existing?.odometerKm?.toString() ?: "",
                prices = FuelPrices(
                    liters = existing?.let { editableDecimal(it.liters) } ?: "",
                    total = existing?.takeIf { it.totalCost > 0 }?.let { editableDecimal(it.totalCost) } ?: "",
                    perLiter = existing?.takeIf { it.pricePerLiter > 0 }?.let { editableDecimal(it.pricePerLiter) } ?: "",
                ),
                kind = existing?.kind ?: FuelKind.DIESEL,
                fullTank = existing?.fullTank ?: true,
                currency = existing?.currency ?: APP_CURRENCY,
                kindChosen = existing != null,
                note = existing?.note ?: "",
            ).withDefaults()
        }
    }
}

/** Tallennettujen syötteiden määrä ([fuelFormSaver]): vajaa tai muunlainen lista on toisen version jäämä. */
private const val SAVED_FORM_FIELDS = 12

/**
 * Syötteet prosessin yli (Android voi lopettaa prosessin kameran ollessa auki): vain käyttäjän
 * syötteet ja valintaliput, sillä konteksti (kirjaukset, autot, nykyhetki) tulee palautuksessa tuoreena
 * [context]-funktiosta. Kelvoton tallenne (toisen version jäämä) antaa null, jolloin lomake alkaa alusta.
 */
fun fuelFormSaver(context: () -> FuelFormContext): Saver<FuelForm, Any> = listSaver(
    save = {
        listOf(
            it.plate, it.epochDay, it.km, it.prices.liters, it.prices.total, it.prices.perLiter, it.priceAnchor?.name,
            it.kind.name, it.fullTank, it.currency, it.kindChosen, it.note,
        )
    },
    restore = { saved ->
        if (saved.size != SAVED_FORM_FIELDS) return@listSaver null
        runCatching {
            FuelForm(
                context = context(),
                plate = saved[0] as String,
                epochDay = saved[1] as Long,
                km = saved[2] as String,
                prices = FuelPrices(liters = saved[3] as String, total = saved[4] as String, perLiter = saved[5] as String),
                priceAnchor = (saved[6] as String?)?.let(FuelField::valueOf),
                kind = FuelKind.valueOf(saved[7] as String),
                fullTank = saved[8] as Boolean,
                currency = saved[9] as String,
                kindChosen = saved[10] as Boolean,
                note = saved[11] as String,
            )
        }.getOrNull()
    },
)
