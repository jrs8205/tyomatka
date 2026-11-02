package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelKind

/** Käyttövoiman sanaryhmä: tankkaus (diesel, bensiini ja AdBlue), lataus (sähkö) tai täyttö (kaasu). */
enum class FillGroup { FILL, CHARGE, GAS }

fun fillGroup(kind: FuelKind): FillGroup = when (kind) {
    FuelKind.DIESEL, FuelKind.PETROL, FuelKind.ADBLUE -> FillGroup.FILL
    FuelKind.ELECTRIC -> FillGroup.CHARGE
    FuelKind.GAS -> FillGroup.GAS
}

/** Lukumäärän sana: yksikkö ja muut määrät ("1 kuva", "2 kuvaa"). */
data class Plural(val one: String, val many: String) {
    fun of(count: Int): String = if (count == 1) one else many
}

/** Sanaryhmän sanat yhdellä kielellä. */
class GroupWords(val did: String, val word: Plural, val title: String, val photoAlt: String)

/**
 * Raportin tekstit yhdellä kielellä. Mallitekstien paikat ovat {0} ja {1}; samat mallit kulkevat raportin skriptille,
 * joka täyttää ne haun ja kuvanäkymän tiloissa.
 */
class ReportStrings internal constructor(
    val language: ReportLanguage,
    val appName: String,
    val title: String,
    /** "viety {0} klo {1}": päivä pienellä viikonpäivällä ja kellonaika. */
    val exportedTemplate: String,
    val fullBackup: String,
    val skipToContent: String,
    val menu: String,
    val closeMenu: String,
    val sections: String,
    val searchLabel: String,
    val searchPlaceholder: String,
    val clearSearch: String,
    /** "Osumat: {0}, {1}": päivien ja kirjausten määrät. */
    val hitsTemplate: String,
    val summary: String,
    val days: String,
    val periods: String,
    val photos: String,
    val explain: String,
    val backup: String,
    val daysNote: String,
    val moreDays: String,
    /** "Näytetään {0}/{1} päivää." */
    val shownTemplate: String,
    val noHits: String,
    val weeks: String,
    val months: String,
    val years: String,
    val periodLength: String,
    val day: Plural,
    val entry: Plural,
    val workDay: Plural,
    val photo: Plural,
    val car: Plural,
    val workTime: String,
    val kilometres: String,
    val odometer: String,
    val partial: String,
    val consumption: String,
    val backdated: String,
    val inProgress: String,
    val noWorkEntry: String,
    val missingReadings: String,
    /** "Kuva {0}/{1}" */
    val photoOfTemplate: String,
    val closePhoto: String,
    val previousPhoto: String,
    val nextPhoto: String,
    val goToEntry: String,
    val photosNote: String,
    val explainTitle: String,
    /** Selitteiden kappaleet; kaikilla kielillä sama määrä. */
    val explanations: List<String>,
    /** "… jakson {0} kirjauksista …": jakson varmuuskopion selitys. */
    val backupPeriodTemplate: String,
    val backupFullText: String,
    val noDays: String,
    val noEntries: String,
    val noPhotos: String,
    /** Galleriassa pienoiskuvan paikalla, kun sitä ei voitu tehdä. */
    val noPreview: String,
    /** Leveän taulukon sarakkeet: päivä, auto, laji, mittari, määrä, hinta, yksikköhinta, kulutus, lisätiedot ja kuvat. */
    val tableColumns: List<String>,
    /** Lisätiedot-kentän nimi (päivän ja kirjauksen vapaa teksti). */
    val notes: String,
    /** Leveän taulukon vierityssäiliön nimen loppu: "Tankkaukset: taulukko". */
    val table: String,
    private val and: String,
    private val kindNames: Map<FuelKind, String>,
    private val groups: Map<FillGroup, GroupWords>,
) {
    fun kindName(kind: FuelKind): String = kindNames.getValue(kind)

    /** Päivärivin teko: "Tankattu", "Ladattu", "Täytetty". */
    fun did(group: FillGroup): String = groups.getValue(group).did

    /** Kirjauksen sana määrän mukaan ilman lukua: "täyttö", "täyttöä". */
    fun entryWord(group: FillGroup, count: Int): String = groups.getValue(group).word.of(count)

    fun photoAlt(group: FillGroup): String = groups.getValue(group).photoAlt

    /** Kirjausosion otsikko järjestyksessä tankkaus, lataus, täyttö: "Tankkaukset, lataukset ja täytöt". */
    fun entriesTitle(kinds: Collection<FuelKind>): String {
        val titles = FillGroup.entries.filter { group -> kinds.any { fillGroup(it) == group } }
            .map { groups.getValue(it).title }
        if (titles.isEmpty()) return groups.getValue(FillGroup.FILL).title
        return joinList(listOf(titles.first()) + titles.drop(1).map { title -> title.replaceFirstChar { it.lowercaseChar() } })
    }

    /** Määrät sanaryhmittäin samassa järjestyksessä: ["12 tankkausta", "3 latausta"]; nollat jäävät pois. */
    fun entryCounts(counts: Map<FillGroup, Int>): List<String> =
        FillGroup.entries.filter { (counts[it] ?: 0) > 0 }.map { count(counts.getValue(it), groups.getValue(it).word) }

    /** Luettelo kielen sidesanalla: "a, b ja c". */
    fun joinList(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts.single()
        else -> parts.dropLast(1).joinToString(", ") + " $and " + parts.last()
    }

    /** Luku ja sana sitovalla välillä: "1 kuva", "10 000 kirjausta". */
    fun count(n: Int, word: Plural): String = "${wholeNumber(n.toLong(), language)}$NB${word.of(n)}"

    fun exported(date: String, time: String): String = fill(exportedTemplate, date, time)

    fun hits(days: String, entries: String): String = fill(hitsTemplate, days, entries)

    fun shown(shown: Int, total: Int): String = fill(shownTemplate, shown.toString(), total.toString())

    fun photoOf(index: Int, count: Int): String = fill(photoOfTemplate, index.toString(), count.toString())

    /** Varmuuskopio-osion teksti: jakson varmuuskopio tai koko varmuuskopio ([period] = null). */
    fun backupText(period: String?): String = if (period == null) backupFullText else fill(backupPeriodTemplate, period)

    private fun fill(template: String, vararg args: String): String =
        args.foldIndexed(template) { i, text, arg -> text.replace("{$i}", arg) }
}

fun reportStrings(language: ReportLanguage): ReportStrings = when (language) {
    ReportLanguage.FI -> FINNISH
    ReportLanguage.SV -> SWEDISH
    ReportLanguage.EN -> ENGLISH
}

private val FINNISH = ReportStrings(
    language = ReportLanguage.FI,
    appName = "Työmatka",
    title = "Työmatka-raportti",
    exportedTemplate = "viety {0} klo$NB{1}",
    fullBackup = "Koko varmuuskopio",
    skipToContent = "Siirry sisältöön",
    menu = "Valikko",
    closeMenu = "Sulje valikko",
    sections = "Raportin osiot",
    searchLabel = "Hae raportista",
    searchPlaceholder = "Päivä, auto tai merkintä",
    clearSearch = "Tyhjennä haku",
    hitsTemplate = "Osumat: {0}, {1}",
    summary = "Yhteenveto",
    days = "Päivät",
    periods = "Viikot, kuukaudet ja vuodet",
    photos = "Kuvat",
    explain = "Selitteet",
    backup = "Varmuuskopio",
    daysNote = "Uusin ensin. Haku rajaa päiviä ja kirjauksia.",
    moreDays = "Näytä lisää päiviä",
    shownTemplate = "Näytetään {0}/{1} päivää.",
    noHits = "Ei osumia.",
    weeks = "Viikot",
    months = "Kuukaudet",
    years = "Vuodet",
    periodLength = "Jakson pituus",
    day = Plural("päivä", "päivää"),
    entry = Plural("kirjaus", "kirjausta"),
    workDay = Plural("työpäivä", "työpäivää"),
    photo = Plural("kuva", "kuvaa"),
    car = Plural("auto", "autoa"),
    workTime = "työaika",
    kilometres = "kilometrit",
    odometer = "mittari",
    partial = "vajaa",
    consumption = "kulutus",
    backdated = "takautuva kirjaus",
    inProgress = "kesken",
    noWorkEntry = "ei työkirjausta",
    missingReadings = "lukemia puuttuu tai ne ovat ristiriidassa",
    photoOfTemplate = "Kuva {0}/{1}",
    closePhoto = "Sulje kuva",
    previousPhoto = "Edellinen kuva",
    nextPhoto = "Seuraava kuva",
    goToEntry = "Siirry kirjaukseen",
    photosNote = "Kuitit ja mittarien kuvat kirjauksittain. Kuva avautuu koko näytölle, ja sieltä pääsee kirjaukseen.",
    explainTitle = "Miten luvut lasketaan",
    explanations = listOf(
        "Työaika on päivän ensimmäisestä aloituksesta viimeiseen lopetukseen. Kesken olevaa päivää ei lasketa " +
            "työaikaan, eikä sen avoin osa ole kilometreissä.",
        "Jakson kulutus on tankattu, ladattu tai täytetty määrä jaettuna kirjatuilla työkilometreillä × 100; se ei " +
            "ole auton mitattu kulutus. Jos jaksolla on useita käyttövoimia, kilometrit jaetaan autoittain auton " +
            "käyttövoiman mukaan. Jos osalta kilometreistä puuttuu rekisterinumero tai lukemia, kulutusta ei näytetä.",
        "Auton kulutus lasketaan täydestä seuraavaan täyteen; välissä olevat vajaat kuuluvat väliin, ja väli kuuluu " +
            "päättävän kirjauksen päivään. AdBluen kulutus on litroina tuhannelle kilometrille.",
        "Jos kirjaukselta puuttuu hinta, jakson hinta kilometriltä on arvio hinnoiteltujen kirjausten keskihinnalla. " +
            "Eri valuutoissa maksettuja summia ei muunneta, eikä niistä lasketa hintaa kilometriltä.",
    ),
    backupPeriodTemplate = "Tämä tiedosto on myös varmuuskopio jakson {0} kirjauksista ja kuvista. Palautus: avaa " +
        "Työmatka-sovelluksen Työmatka-sivu, napauta Tuo ja valitse tämä tiedosto. Jo puhelimessa olevat kirjaukset " +
        "ohitetaan. Koko varmuuskopio valitaan viennissä erikseen.",
    backupFullText = "Tämä tiedosto on koko varmuuskopio: siinä ovat kaikki kirjaukset ja kuvat. Palautus: avaa " +
        "Työmatka-sovelluksen Työmatka-sivu, napauta Tuo ja valitse tämä tiedosto. Jo puhelimessa olevat kirjaukset " +
        "ohitetaan.",
    noDays = "Ei työpäiviä.",
    noEntries = "Ei kirjauksia.",
    noPhotos = "Ei kuvia.",
    noPreview = "Esikatselua ei voitu tehdä",
    tableColumns = listOf("Päivä", "Auto", "Laji", "Mittari", "Määrä", "Hinta", "Yksikköhinta", "Kulutus", "Lisätiedot", "Kuvat"),
    notes = "Lisätiedot",
    table = "taulukko",
    and = "ja",
    kindNames = mapOf(
        FuelKind.DIESEL to "Diesel",
        FuelKind.PETROL to "Bensiini",
        FuelKind.GAS to "Kaasu",
        FuelKind.ELECTRIC to "Sähkö",
        FuelKind.ADBLUE to "AdBlue",
    ),
    groups = mapOf(
        FillGroup.FILL to GroupWords("Tankattu", Plural("tankkaus", "tankkausta"), "Tankkaukset", "Tankkauksen kuva"),
        FillGroup.CHARGE to GroupWords("Ladattu", Plural("lataus", "latausta"), "Lataukset", "Latauksen kuva"),
        FillGroup.GAS to GroupWords("Täytetty", Plural("täyttö", "täyttöä"), "Täytöt", "Täytön kuva"),
    ),
)

private val SWEDISH = ReportStrings(
    language = ReportLanguage.SV,
    appName = "Körjournal",
    title = "Körjournal – rapport",
    exportedTemplate = "exporterad {0} kl.$NB{1}",
    fullBackup = "Fullständig säkerhetskopia",
    skipToContent = "Hoppa till innehållet",
    menu = "Meny",
    closeMenu = "Stäng menyn",
    sections = "Rapportens delar",
    searchLabel = "Sök i rapporten",
    searchPlaceholder = "Dag, bil eller anteckning",
    clearSearch = "Töm sökningen",
    hitsTemplate = "Träffar: {0}, {1}",
    summary = "Sammanfattning",
    days = "Dagar",
    periods = "Veckor, månader och år",
    photos = "Bilder",
    explain = "Förklaringar",
    backup = "Säkerhetskopia",
    daysNote = "Senaste först. Sökningen filtrerar dagar och poster.",
    moreDays = "Visa fler dagar",
    shownTemplate = "Visar {0}/{1} dagar.",
    noHits = "Inga träffar.",
    weeks = "Veckor",
    months = "Månader",
    years = "År",
    periodLength = "Periodens längd",
    day = Plural("dag", "dagar"),
    entry = Plural("post", "poster"),
    workDay = Plural("arbetsdag", "arbetsdagar"),
    photo = Plural("bild", "bilder"),
    car = Plural("bil", "bilar"),
    workTime = "arbetstid",
    kilometres = "kilometer",
    odometer = "mätare",
    partial = "delvis",
    consumption = "förbrukning",
    backdated = "efterregistrerad",
    inProgress = "pågår",
    noWorkEntry = "inget arbete registrerat",
    missingReadings = "mätarställningar saknas eller är motstridiga",
    photoOfTemplate = "Bild {0}/{1}",
    closePhoto = "Stäng bilden",
    previousPhoto = "Föregående bild",
    nextPhoto = "Nästa bild",
    goToEntry = "Gå till posten",
    photosNote = "Kvitton och mätarbilder per post. Bilden öppnas i helskärm, och därifrån kommer du till posten.",
    explainTitle = "Hur siffrorna räknas",
    explanations = listOf(
        "Arbetstiden räknas från dagens första start till den sista avslutningen. En dag som pågår räknas inte in i " +
            "arbetstiden, och dess öppna del ingår inte i kilometrarna.",
        "Periodens förbrukning är tankad, laddad eller påfylld mängd delad med registrerade arbetskilometer × 100; " +
            "den är inte bilens uppmätta förbrukning. Om perioden har flera drivmedel fördelas kilometrarna per bil " +
            "enligt bilens drivmedel. Om registreringsnummer eller mätarställningar saknas för en del av " +
            "kilometrarna visas ingen förbrukning.",
        "Bilens förbrukning räknas från full tank till nästa fulla; delvisa tankningar, laddningar och påfyllningar däremellan hör till " +
            "intervallet, och intervallet hör till dagen för den post som avslutar det. AdBlue anges i liter per " +
            "tusen kilometer.",
        "Om en post saknar pris är periodens pris per kilometer en uppskattning baserad på de prissatta posternas " +
            "medelpris. Belopp i olika valutor räknas inte om, och för dem räknas inget pris per kilometer.",
    ),
    backupPeriodTemplate = "Den här filen är också en säkerhetskopia av poster och bilder för perioden {0}. " +
        "Återställning: öppna sidan Körjournal i appen Körjournal, tryck på Importera och välj den här filen. Poster " +
        "som redan finns i telefonen hoppas över. En fullständig säkerhetskopia väljs separat vid exporten.",
    backupFullText = "Den här filen är en fullständig säkerhetskopia med alla poster och bilder. Återställning: " +
        "öppna sidan Körjournal i appen Körjournal, tryck på Importera och välj den här filen. Poster som redan finns " +
        "i telefonen hoppas över.",
    noDays = "Inga arbetsdagar.",
    noEntries = "Inga poster.",
    noPhotos = "Inga bilder.",
    noPreview = "Förhandsvisningen kunde inte skapas",
    tableColumns = listOf("Dag", "Bil", "Typ", "Mätare", "Mängd", "Pris", "Enhetspris", "Förbrukning", "Anteckningar", "Bilder"),
    notes = "Anteckningar",
    table = "tabell",
    and = "och",
    kindNames = mapOf(
        FuelKind.DIESEL to "Diesel",
        FuelKind.PETROL to "Bensin",
        FuelKind.GAS to "Gas",
        FuelKind.ELECTRIC to "El",
        FuelKind.ADBLUE to "AdBlue",
    ),
    groups = mapOf(
        FillGroup.FILL to GroupWords("Tankat", Plural("tankning", "tankningar"), "Tankningar", "Bild av tankning"),
        FillGroup.CHARGE to GroupWords("Laddat", Plural("laddning", "laddningar"), "Laddningar", "Bild av laddning"),
        FillGroup.GAS to GroupWords("Påfyllt", Plural("påfyllning", "påfyllningar"), "Påfyllningar", "Bild av påfyllning"),
    ),
)

private val ENGLISH = ReportStrings(
    language = ReportLanguage.EN,
    appName = "Work Trip",
    title = "Work Trip report",
    exportedTemplate = "exported {0} at$NB{1}",
    fullBackup = "Full backup",
    skipToContent = "Skip to content",
    menu = "Menu",
    closeMenu = "Close menu",
    sections = "Report sections",
    searchLabel = "Search the report",
    searchPlaceholder = "Day, car or note",
    clearSearch = "Clear search",
    hitsTemplate = "Matches: {0}, {1}",
    summary = "Summary",
    days = "Days",
    periods = "Weeks, months and years",
    photos = "Photos",
    explain = "Explanations",
    backup = "Backup",
    daysNote = "Newest first. The search filters days and entries.",
    moreDays = "Show more days",
    shownTemplate = "Showing {0}/{1} days.",
    noHits = "No matches.",
    weeks = "Weeks",
    months = "Months",
    years = "Years",
    periodLength = "Period length",
    day = Plural("day", "days"),
    entry = Plural("entry", "entries"),
    workDay = Plural("working day", "working days"),
    photo = Plural("photo", "photos"),
    car = Plural("car", "cars"),
    workTime = "working time",
    kilometres = "kilometres",
    odometer = "odometer",
    partial = "partial",
    consumption = "consumption",
    backdated = "backdated",
    inProgress = "in progress",
    noWorkEntry = "no work entry",
    missingReadings = "readings missing or inconsistent",
    photoOfTemplate = "Photo {0}/{1}",
    closePhoto = "Close photo",
    previousPhoto = "Previous photo",
    nextPhoto = "Next photo",
    goToEntry = "Go to entry",
    photosNote = "Receipts and meter photos by entry. A photo opens full screen, and from there you can go to its entry.",
    explainTitle = "How the figures are calculated",
    explanations = listOf(
        "Working time runs from the first start of the day to the last finish. A day still in progress is not " +
            "included in working time, and its open part is not included in the kilometres.",
        "Period consumption is the amount fuelled, charged or refilled divided by the recorded working kilometres " +
            "× 100; it is not the measured consumption of the car. When a period has several fuel types, the " +
            "kilometres are divided by car according to each car's fuel type. If the registration number or " +
            "readings are missing for some of the kilometres, no consumption is shown.",
        "Car consumption is calculated from one full fill to the next; partial fills in between belong to that " +
            "interval, and the interval belongs to the day of the entry that closes it. AdBlue is shown in litres " +
            "per thousand kilometres.",
        "If an entry has no price, the period's cost per kilometre is an estimate using the average price of the " +
            "priced entries. Amounts paid in different currencies are not converted, and no cost per kilometre is " +
            "calculated from them.",
    ),
    backupPeriodTemplate = "This file is also a backup of the entries and photos for {0}. To restore: open the Work " +
        "Trip page in the Work Trip app, tap Import and choose this file. Entries already on the phone are skipped. A " +
        "full backup is chosen separately when exporting.",
    backupFullText = "This file is a full backup with all entries and photos. To restore: open the Work Trip page in " +
        "the Work Trip app, tap Import and choose this file. Entries already on the phone are skipped.",
    noDays = "No working days.",
    noEntries = "No entries.",
    noPhotos = "No photos.",
    noPreview = "Preview not available",
    tableColumns = listOf("Date", "Car", "Type", "Odometer", "Amount", "Price", "Unit price", "Consumption", "Notes", "Photos"),
    notes = "Notes",
    table = "table",
    and = "and",
    kindNames = mapOf(
        FuelKind.DIESEL to "Diesel",
        FuelKind.PETROL to "Petrol",
        FuelKind.GAS to "Gas (CNG)",
        FuelKind.ELECTRIC to "Electric",
        FuelKind.ADBLUE to "AdBlue",
    ),
    groups = mapOf(
        FillGroup.FILL to GroupWords("Fuelled", Plural("fill-up", "fill-ups"), "Fill-ups", "Fill-up photo"),
        FillGroup.CHARGE to GroupWords("Charged", Plural("charge", "charges"), "Charges", "Charge photo"),
        FillGroup.GAS to GroupWords("Refilled", Plural("refill", "refills"), "Refills", "Refill photo"),
    ),
)
