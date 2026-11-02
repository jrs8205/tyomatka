package fi.tyomatka.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Yksi työpäivä / reitti. */
@Entity(
    tableName = "route_days",
    // Uniikkius lukitsee invariantin: päivällä on tasan yksi rivi, vaikka
    // kaksi luontipolkua (todayDay ja HTML-tuonti) kilpailisivat.
    indices = [Index("dateEpochDay", unique = true)],
)
data class RouteDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Päivämäärä (LocalDate.toEpochDay). */
    val dateEpochDay: Long,
    val startedAtEpochSec: Long? = null,
    val finishedAtEpochSec: Long? = null,
    /** Suunnittelun aikainen ennuste päivän valmistumisesta (raporttia varten). */
    val predictedFinishEpochSec: Long? = null,
    /** Päivän lisätiedot: kuljettajan vapaa muistiinpano ([fi.tyomatka.data.normalizeNote]); tyhjä = ei ole. */
    @androidx.room.ColumnInfo(defaultValue = "''")
    val note: String = "",
    /** Pohja, josta päivän lista avattiin ([RouteTemplateEntity]); null = ei pohjaa. */
    val templateId: Long? = null,
    /** Muutosrivin "Myöhemmin": kuitattu päivän asiakasstoppien avain (`TemplateDiff.ackKey`). */
    val templateAck: String? = null,
)

/** Stopin elinkaari. */
enum class StopStatus { PENDING, ACTIVE, ARRIVED, DONE, SKIPPED }

@Entity(
    tableName = "stops",
    indices = [Index("routeDayId")],
)
data class StopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeDayId: Long,
    val label: String,
    val address: String,
    val placeId: String? = null,
    val lat: Double,
    val lng: Double,
    /** Ajojärjestys (0-alkuinen). Kuljettajan oma järjestys — vain
     *  lisäys ja manuaalinen siirto kirjoittavat tämän. */
    val orderIndex: Int,
    /** "Perillä viimeistään" minuutteina keskiyöstä (esim. 15:00 = 900), tai null. */
    val deadlineMinutesOfDay: Int? = null,
    /** Stoppikohtainen kestoarvio sekunteina, null = käytetään opittua oletusta. */
    val serviceSec: Long? = null,
    val status: StopStatus = StopStatus.PENDING,
    val arrivedAtEpochSec: Long? = null,
    val departedAtEpochSec: Long? = null,
)

/** Toteutunut ajo yhdelle legille (ennuste vs. toteuma → oppiminen). */
@Entity(
    tableName = "leg_records",
    indices = [Index("routeDayId")],
)
data class LegRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeDayId: Long,
    val toStopId: Long,
    val predictedSec: Long,
    val actualSec: Long,
    val recordedAtEpochSec: Long,
)

/** Toteutunut stoppiin kulunut aika. */
@Entity(
    tableName = "service_records",
    indices = [Index("routeDayId")],
)
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeDayId: Long,
    val stopId: Long,
    val durationSec: Long,
    val recordedAtEpochSec: Long,
)

/**
 * Opittu ajoaika stoppiparille (EWMA toteumista). Kun kuljettaja ajaa saman
 * välin omaa reittiään, toteutunut aika syrjäyttää Googlen ennusteen
 * seuraavissa ETA-laskuissa.
 */
@Entity(tableName = "leg_stats", primaryKeys = ["fromKey", "toKey"])
data class LegStatEntity(
    val fromKey: String,
    val toKey: String,
    val ewmaSec: Long,
    val samples: Int,
    val updatedEpochSec: Long,
)

/**
 * Opittujen ajoaikojen paikka-avain: koordinaatit neljällä desimaalilla (~10 m). Sama funktio muodostaa avaimen
 * migraatiossa 7 → 8 ja ajon aikana: SQLiten printf pyöristää puolivälin arvot eri tavalla kuin String.format.
 */
internal fun legKey(lat: Double, lng: Double): String = String.format(java.util.Locale.ROOT, "c:%.4f,%.4f", lat, lng)

/** Aiemmin käytetty osoite: ennakoivan haun paikallinen muisti. */
@Entity(
    tableName = "saved_addresses",
    indices = [Index("placeId", unique = true), Index("searchKey")],
)
data class SavedAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: String? = null,
    val label: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    /** Pienaakkosiksi normalisoitu "label osoite" — LIKE-haku ei muuten osu ääkkösiin. */
    val searchKey: String,
    val useCount: Int = 1,
    val lastUsedEpochSec: Long,
)

/** Reittisuunnitelman välimuisti (JSON) — taulun nimi on historiallinen,
 *  sisältönä on nykyään RoutePlan-suunnitelma kuljettajan järjestykselle. */
@Entity(tableName = "matrix_cache")
data class MatrixCacheEntity(
    @PrimaryKey val routeDayId: Long,
    val json: String,
    val fetchedAtEpochSec: Long,
)

/**
 * Työpäivän autosegmentti: yhtäjaksoinen työrupeama yhdellä autolla.
 * Tavallinen päivä on yksi segmentti; autonvaihto sulkee edellisen ja avaa
 * uuden. Mittarilukemat ovat valinnaisia — pelkkä työaika kelpaa kirjaukseksi.
 */
@Entity(
    tableName = "work_segments",
    indices = [Index("routeDayId")],
)
data class WorkSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeDayId: Long,
    val startEpochSec: Long,
    /** Null = segmentti (ja työpäivä) on yhä käynnissä. */
    val endEpochSec: Long? = null,
    val odometerStartKm: Long? = null,
    val odometerEndKm: Long? = null,
    /** Auton rekisterinumero normalisoituna ("ABC-123"); null vanhoilla riveillä. */
    val licensePlate: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val backupId: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Tankkauksen tai latauksen laji ja sen yksikkö. Polttoaineiden kulutus lasketaan sadalle kilometrille, AdBluen
 * tuhannelle. Room tallentaa nimen tekstinä.
 */
enum class FuelKind(val unit: FuelUnit, val perKm: Int) {
    DIESEL(FuelUnit.LITER, 100),
    PETROL(FuelUnit.LITER, 100),
    GAS(FuelUnit.KILOGRAM, 100),
    ELECTRIC(FuelUnit.KILOWATT_HOUR, 100),
    ADBLUE(FuelUnit.LITER, 1000);

    /** Auton polttoaine (diesel, bensiini, kaasu tai sähkö); AdBlue on lisäaine. */
    val isMainFuel: Boolean get() = this != ADBLUE

    companion object {
        val mainFuels: List<FuelKind> get() = entries.filter { it.isMainFuel }
    }
}

/** Määrän yksikkö: litra, kilogramma (kaasu) tai kilowattitunti (sähkö). */
enum class FuelUnit { LITER, KILOGRAM, KILOWATT_HOUR }

/**
 * Yksi tankkaus: diesel tai AdBlue, täysi tai vajaa. Kulutus lasketaan saman auton saman lajin
 * täydestä tankkauksesta seuraavaan täyteen; välissä olevat vajaat kuuluvat väliin.
 */
@Entity(
    tableName = "fuel_fillups",
    indices = [Index("licensePlate"), Index("epochSec")],
)
data class FuelFillupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Tankkaushetki: tänään = tallennushetki, menneelle päivälle keskipäivä. */
    val epochSec: Long,
    /** Normalisoitu rekisterinumero — pakollinen, kulutus on autokohtainen. */
    val licensePlate: String,
    val odometerKm: Long,
    /** Määrä lajin yksikössä: litrat, kaasun kilot tai sähkön kilowattitunnit. */
    val liters: Double,
    /** Summa [currency]-valuutassa; 0 = hintaa ei tiedetä. */
    val totalCost: Double,
    /** Yksikköhinta [currency]-valuutassa; 0 = ei tiedetä. */
    val pricePerLiter: Double,
    /** Varmuuskopioissa säilyvä tunniste; paikallinen id voi vaihtua palautuksessa. */
    @androidx.room.ColumnInfo(defaultValue = "''")
    val backupId: String = java.util.UUID.randomUUID().toString(),
    /** Laji; vanhat rivit ovat dieseliä. Määrä ([liters]) on lajin yksikössä (l, kg tai kWh). */
    @androidx.room.ColumnInfo(defaultValue = "'DIESEL'")
    val kind: FuelKind = FuelKind.DIESEL,
    /** Kirjauksen lisätiedot: kuljettajan vapaa muistiinpano ([fi.tyomatka.data.normalizeNote]); tyhjä = ei ole. */
    @androidx.room.ColumnInfo(defaultValue = "''")
    val note: String = "",
    /** Täysi tankki; vajaa tankkaus kuuluu seuraavan täyden kulutusväliin. */
    @androidx.room.ColumnInfo(defaultValue = "1")
    val fullTank: Boolean = true,
    /** Summan ja yksikköhinnan valuutta (ISO 4217); ei Kotlin-oletusta, jotta jokainen kirjoittaja antaa sen. */
    @androidx.room.ColumnInfo(defaultValue = "'EUR'")
    val currency: String,
)

/**
 * Tankkauksen, latauksen tai täytön kuva todisteeksi: kuitti, mittarin näyttö tai latausapin kuvakaappaus (käyttäjä
 * 17.10.). Tiedosto on sovelluksen kuvakansiossa; kirjauksen poisto poistaa rivit viiteavaimella.
 */
@Entity(
    tableName = "fuel_photos",
    foreignKeys = [
        ForeignKey(entity = FuelFillupEntity::class, parentColumns = ["id"], childColumns = ["fillupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("fillupId")],
)
data class FuelPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fillupId: Long,
    /** Tiedoston nimi kuvakansiossa. */
    val fileName: String,
    /** Lisäyshetki: kuvat näytetään lisäysjärjestyksessä. */
    val addedAtEpochSec: Long,
    /** Varmuuskopioissa säilyvä tunniste. */
    val backupId: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Auton profiili: polttoaine (null = ei tiedossa; seuraava polttoainetankkaus kirjaa sen). Valuutta on vanhojen
 * versioiden tallentama tieto, joka säilyy varmuuskopiossa; sitä ei enää valita eikä käytetä oletuksena.
 */
@Entity(tableName = "cars")
data class CarEntity(
    @PrimaryKey val plate: String,
    val fuel: FuelKind?,
    val currency: String?,
)

/** Tallennettu reitti: nimetty stoppilista, joka avataan päivälle pohjaksi. */
@Entity(tableName = "route_templates", indices = [Index("name")])
data class RouteTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdEpochSec: Long,
    val updatedEpochSec: Long,
    val lastOpenedEpochSec: Long? = null,
)

/** Pohjan stoppi ajojärjestyksessä; tilat ja ajat eivät kuulu pohjaan. */
@Entity(
    tableName = "template_stops",
    foreignKeys = [
        ForeignKey(entity = RouteTemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("templateId")],
)
data class TemplateStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val orderIndex: Int,
    val label: String,
    val address: String,
    val placeId: String? = null,
    val lat: Double,
    val lng: Double,
    val deadlineMinutesOfDay: Int? = null,
    val serviceSec: Long? = null,
)

/** Tallennettujen lista: pohja ja sen stoppien määrä. */
data class TemplateListItem(@Embedded val template: RouteTemplateEntity, val stopCount: Int)

/**
 * Karttakohdepalvelimen vastaus pyöristetylle ikkunalle ja luokalle (`PoiGrid.cacheKey`); [json] on vastaus sellaisenaan.
 * Alle vuorokauden vanha käytetään ilman verkkoa, enintään 30 päivän vanha näytetään verkkokatkossa.
 */
@Entity(tableName = "poi_cache")
data class PoiCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedEpochSec: Long,
)

/** Kuljettajan korjaus kohteen tietoon; [field] on `PoiCorrectionField.key`, [value] "yes"/"no" tai raja-arvo. Vain laitteella. */
@Entity(tableName = "poi_corrections", primaryKeys = ["poiId", "field"])
data class PoiCorrectionEntity(
    val poiId: String,
    val field: String,
    val value: String,
    val epochSec: Long,
)

/**
 * Kuljettajan oma ilmoitus ([rid] = palvelimen kaksoiskappalesuoja, [field] = `ReportField.key`). Lähettämätön rivi on
 * jonossa ([sentEpochSec] null); kuitattu rivi säilyy viikon omana tietona, jottei ilmoitus katoa kortista, jos seuraava
 * ikkunahaku epäonnistuu. Pysyvästi hylätty rivi poistetaan. Ei kuulu varmuuskopioon.
 */
@Entity(tableName = "pending_reports")
data class PendingReportEntity(
    @PrimaryKey val rid: String,
    val poiId: String,
    val field: String,
    val value: String,
    val createdEpochSec: Long,
    val sentEpochSec: Long? = null,
)

/** Kuljettajan kirjaama hinta kohteella lajia kohti ([currency] ISO 4217, [epochDay] kirjauspäivä). Vain laitteella. */
@Entity(tableName = "poi_prices", primaryKeys = ["poiId", "fuel"])
data class PoiPriceEntity(
    val poiId: String,
    val fuel: FuelKind,
    val price: Double,
    val currency: String,
    val epochDay: Long,
)
