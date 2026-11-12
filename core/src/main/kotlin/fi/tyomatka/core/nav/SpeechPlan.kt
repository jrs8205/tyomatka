package fi.tyomatka.core.nav

/** Yksi puheohje: teksti ja matka askeleen loppuun, jossa se laukeaa. */
data class PlannedInstruction(val text: String, val triggerM: Double)

/** Reitin askel: pituus ja puheohjeet laukaisumatkan mukaan laskevassa järjestyksessä. */
data class PlannedStep(val distanceM: Double, val instructions: List<PlannedInstruction>)

/** Kummalla puolella tietä kohde on reitin lopussa. */
enum class ArrivalSide { LEFT, RIGHT, UNKNOWN }

/** Tien lajin sijamuoto lauseessa: "tielle", "tiellä", "tien", "tietä". */
enum class RoadCase { ALLATIVE, ADESSIVE, GENITIVE, PARTITIVE }

/**
 * Valhallan puheohjeiden muokkaus ennen opastuksen aloitusta. Suomeksi tien laji
 * päätellään nimen päätteestä ("kadulle 1605" → "tielle 1605"), lähtöohje ei käytä ilmansuuntaa, askeleen alun
 * Jatka-lause kertoo seuraavan käännöksen, pitkä askel saa ennakkoilmoituksen 2–3 km ennen, ja viimeinen askel
 * kertoo kohteen puolen 250 m ennen. Vaihdetulla reitillä lähtöohje korvataan Jatka-muodolla, koska auto on jo
 * liikkeellä. Säännöt koskevat kaikkia kieliä; vain tien lajin taivutus on suomen oma. Ferrostar puhuu viimeisen
 * ohitetun ohjeen, joten lisätyt ohjeet sijoitetaan laukaisumatkan mukaan laskevaan järjestykseen. Näkymättömät
 * suuntamerkit poistetaan, jotta nimet tunnistuvat.
 */
object SpeechPlan {

    fun plan(steps: List<PlannedStep>, languageTag: String, rerouted: Boolean, arrivalSide: ArrivalSide): List<PlannedStep> {
        val lang = Lang.of(languageTag)
        // Reitin lopussa voi olla pituudeton askel ilman ohjeita: viimeinen on viimeinen ohjeellinen askel.
        val lastIndex = steps.indexOfLast { it.instructions.isNotEmpty() }
        return steps.mapIndexed { index, step ->
            val raw = step.instructions.map { it.copy(text = stripMarks(it.text)) }
            var fixed = raw.map { it.copy(text = roads(it.text, lang)) }
            val last = index == lastIndex
            fixed = fixed.map { it.copy(text = destination(it.text, lang)) }
            val next = nextCue(fixed, lang)
            if (index == 0 && fixed.isNotEmpty()) {
                departure(raw.first().text, step.distanceM, lang, rerouted, next)?.let { first ->
                    // Tyhjä korvaus poistaa lähtöohjeen: lyhyellä vaihdetulla askeleella seuraava ohje riittää.
                    fixed = if (first.isEmpty()) fixed.drop(1) else listOf(fixed.first().copy(text = destination(roads(first, lang), lang))) + fixed.drop(1)
                }
            }
            val beforeContinue = fixed.firstOrNull()?.text
            fixed = continueWithNext(fixed, lang, next)
            // Jatka-lause kertoo jo puolen ("…, sitten kohde on oikealla"): 250 m:n ilmoitus olisi toistoa.
            val continueSaysNext = fixed.firstOrNull()?.text != beforeContinue
            fixed = withEarlyAlert(fixed, step.distanceM, lang, next)
            if (last && !continueSaysNext) fixed = withArrivalAlert(fixed, step.distanceM, arrivalSide, lang)
            // Valhallan oma saapumisohje (40 m) jää pois: oma Perillä-lause puhutaan 50 m:ssä, muuten lauseet tulisivat
            // peräkkäin.
            if (last) fixed = fixed.filter { it.triggerM > ARRIVAL_SELF_M }
            PlannedStep(step.distanceM, fixed)
        }
    }

    /** "tielle 55", "kadulla Hämeenkatu": laji nimen viimeisen sanan päätteestä, numero tai tunnus on tie. */
    fun roadForm(name: String, case: RoadCase): String = "${roadType(name).form(case)} ${name.trim()}"

    /**
     * Oma Perillä-lause saapumisen hetkellä; puoli vain, kun se tiedetään ja saapuminen tuli reitiltä ([sideKnown]):
     * pysähdys ilman reittiä voi tulla toisesta suunnasta, jolloin vanhan reitin puoli olisi väärä.
     */
    fun arrivalText(languageTag: String, side: ArrivalSide, sideKnown: Boolean = true): String = when (Lang.of(languageTag)) {
        Lang.FI -> "Olet perillä." + when (if (sideKnown) side else ArrivalSide.UNKNOWN) {
            ArrivalSide.LEFT -> " Kohde on vasemmalla."
            ArrivalSide.RIGHT -> " Kohde on oikealla."
            ArrivalSide.UNKNOWN -> ""
        }
        Lang.SV -> "Du är framme." + when (if (sideKnown) side else ArrivalSide.UNKNOWN) {
            ArrivalSide.LEFT -> " Målet ligger till vänster."
            ArrivalSide.RIGHT -> " Målet ligger till höger."
            ArrivalSide.UNKNOWN -> ""
        }
        Lang.EN -> "You have arrived." + when (if (sideKnown) side else ArrivalSide.UNKNOWN) {
            ArrivalSide.LEFT -> " Your destination is on the left."
            ArrivalSide.RIGHT -> " Your destination is on the right."
            ArrivalSide.UNKNOWN -> ""
        }
    }

    private enum class Lang {
        FI, SV, EN;

        companion object {
            fun of(tag: String) = when (tag.substringBefore('-').lowercase()) {
                "fi" -> FI
                "sv" -> SV
                else -> EN
            }
        }
    }

    private enum class RoadType(val allative: String, val adessive: String, val genitive: String, val partitive: String) {
        TIE("tielle", "tiellä", "tien", "tietä"),
        KATU("kadulle", "kadulla", "kadun", "katua"),
        KUJA("kujalle", "kujalla", "kujan", "kujaa"),
        VAYLA("väylälle", "väylällä", "väylän", "väylää"),
        POLKU("polulle", "polulla", "polun", "polkua"),
        RAITTI("raitille", "raitilla", "raitin", "raittia"),
        KAARI("kaarelle", "kaarella", "kaaren", "kaarta"),
        RINNE("rinteelle", "rinteellä", "rinteen", "rinnettä"),
        RANTA("rannalle", "rannalla", "rannan", "rantaa"),
        BULEVARDI("bulevardille", "bulevardilla", "bulevardin", "bulevardia"),
        TORI("torille", "torilla", "torin", "toria"),
        KEHA("kehälle", "kehällä", "kehän", "kehää"),
        SILTA("sillalle", "sillalla", "sillan", "siltaa"),
        AUKIO("aukiolle", "aukiolla", "aukion", "aukiota"),
        LINJA("linjalle", "linjalla", "linjan", "linjaa");

        fun form(case: RoadCase) = when (case) {
            RoadCase.ALLATIVE -> allative
            RoadCase.ADESSIVE -> adessive
            RoadCase.GENITIVE -> genitive
            RoadCase.PARTITIVE -> partitive
        }
    }

    /** Päätteet pisimmästä lyhimpään, jotta "valtatie" ja "puistotie" osuvat tiehen vasta lopuksi. */
    private val SUFFIXES = listOf(
        "bulevardi" to RoadType.BULEVARDI, "raitti" to RoadType.RAITTI, "väylä" to RoadType.VAYLA, "rinne" to RoadType.RINNE,
        "ranta" to RoadType.RANTA, "silta" to RoadType.SILTA, "aukio" to RoadType.AUKIO, "linja" to RoadType.LINJA,
        "polku" to RoadType.POLKU, "kaari" to RoadType.KAARI, "katu" to RoadType.KATU, "kuja" to RoadType.KUJA,
        "tori" to RoadType.TORI, "kehä" to RoadType.KEHA, "tie" to RoadType.TIE,
    )

    private fun roadType(name: String): RoadType {
        val trimmed = name.trim()
        if (ROAD_REF.containsMatchIn(trimmed)) return RoadType.TIE
        val lastWord = trimmed.substringAfterLast(' ').lowercase()
        return SUFFIXES.firstOrNull { (suffix, _) -> lastWord.endsWith(suffix) }?.second ?: RoadType.TIE
    }

    /**
     * Suomen "kadulle/kadulla/kadun <nimi>" tien lajin mukaan; muut kielet ennallaan. Valhallan toistuva bear-lause
     * ("Jatkaoikealle Jatkaaksesi kadulla") korjataan ensin samaan muotoon kuin puhehetkellä, ja englanninkieliset
     * nimikkeet ("the bridge") jätetään [InstructionText]ille, joka kääntää ne.
     */
    private fun roads(text: String, lang: Lang): String {
        if (lang != Lang.FI) return text
        val normalized = FI_BEAR_TWICE.replace(text) { "Jatka ${it.groupValues[1]} pysyäksesi kadulla " }
        return FI_STREET.replace(normalized) { m ->
            if (m.groupValues[2].startsWith("the ")) return@replace m.value
            val case = when (m.groupValues[1]) {
                "kadulle" -> RoadCase.ALLATIVE
                "kadulla" -> RoadCase.ADESSIVE
                else -> RoadCase.GENITIVE
            }
            roadForm(m.groupValues[2], case)
        }
    }

    /** Valhallan kohdelause luonnolliseksi: "Määränpääsi on" ja ruotsin "Din destination" ovat kömpelöitä. */
    private fun destination(text: String, lang: Lang): String = when (lang) {
        Lang.FI -> text.replace("Määränpääsi on", "Kohde on").replace("Olet saapunut määränpäähäsi", "Olet perillä")
        Lang.SV -> {
            var s = SV_DESTINATION_AFTER.replace(text, "$1 ligger målet")
            s = SV_DESTINATION.replace(s, "Målet ligger")
            s = SV_ARRIVING_AFTER.replace(s, "$1 är du framme")
            s = SV_ARRIVING.replace(s, "Du är snart framme")
            SV_ARRIVED.replace(s, "Du är framme")
        }
        Lang.EN -> text
    }

    /** "Jatka"-lause ja seuraava ohje: ruotsissa sanajärjestys riippuu jatkosta, englannissa perilläolo on tulevaa aikaa. */
    private fun continueThen(continueText: String, next: String, lang: Lang): String = when (lang) {
        Lang.FI -> "$continueText, sitten $next"
        Lang.SV -> when {
            next.startsWith(SV_TARGET) -> "$continueText, sedan ligger målet${next.removePrefix(SV_TARGET)}"
            next.startsWith(SV_ARRIVAL) -> "$continueText, så är du framme${next.removePrefix(SV_ARRIVAL)}"
            else -> SV_IMPERATIVE.find(next)?.let { "$continueText och ${it.value} sedan${next.removePrefix(it.value)}" }
                ?: "$continueText, sedan $next"
        }
        Lang.EN -> "$continueText, then ${enFuture(next)}"
    }

    private fun enFuture(next: String) = next.replace(EN_ARRIVED, EN_WILL_ARRIVE)

    /** Askeleen viimeinen ohje ilman "Sitten …" -häntää, alkukirjain pienenä; vain kun askeleella on muitakin ohjeita. */
    private fun nextCue(instructions: List<PlannedInstruction>, lang: Lang): String? {
        if (instructions.size < 2) return null
        val text = instructions.last().text
        val cut = when (lang) {
            Lang.FI -> FI_THEN
            Lang.SV -> SV_THEN
            Lang.EN -> EN_THEN
        }.find(text)?.range?.first ?: text.length
        return text.substring(0, cut).trim().replaceFirstChar { it.lowercaseChar() }.ifBlank { null }
    }

    /** Lähtöohjeen korvaava teksti tai null, kun ohje ei ole lähtöohje tai sitä ei muuteta. */
    private fun departure(text: String, distanceM: Double, lang: Lang, rerouted: Boolean, next: String?): String? {
        departureWithLength(text, lang, rerouted)?.let { return it }
        val depart = when (lang) {
            Lang.FI -> FI_DEPART
            Lang.SV -> SV_DEPART
            Lang.EN -> EN_DEPART
        }
        val match = depart.find(text) ?: return null
        if (rerouted) {
            // Ilman seuraavaa ohjetta (yhden askeleen reitti) ilmansuunta korvataan pelkällä Jatka-lauseella.
            if (next == null) return reroutedWithoutNext(match, lang)
            // Alle 150 m: seuraava ohje kuuluu jo askeleen omalla laukaisulla; sama lause kahdesti olisi sekava.
            if (distanceM < REROUTE_CONTINUE_MIN_M) return ""
            val length = lengthText(roundManeuverDistanceM(distanceM), lang)
            val continueText = when (lang) {
                Lang.FI -> "Jatka $length"
                Lang.SV -> "Fortsätt i $length"
                Lang.EN -> "Continue for $length"
            }
            return continueThen(continueText, next, lang)
        }
        val name = match.groupValues[1].trim()
        val rest = match.groupValues[2]
        return when (lang) {
            Lang.FI -> if (name.isEmpty()) "Lähde liikkeelle.$rest" else "Aja kohti ${roadForm(name, RoadCase.PARTITIVE)}.$rest"
            Lang.SV -> if (name.isEmpty()) "Kör i väg.$rest" else "Kör mot $name.$rest"
            Lang.EN -> if (name.isEmpty()) "Start driving.$rest" else "Head towards $name.$rest"
        }
    }

    /** "Kör norrut i 2 kilometer." ilman kadun nimeä: matka jää, ilmansuunta pois. */
    private fun departureWithLength(text: String, lang: Lang, rerouted: Boolean): String? {
        val pattern = when (lang) {
            Lang.FI -> FI_DEPART_LENGTH
            Lang.SV -> SV_DEPART_LENGTH
            Lang.EN -> EN_DEPART_LENGTH
        }
        val match = pattern.find(text) ?: return null
        val (length, rest) = match.destructured
        return when (lang) {
            Lang.FI -> if (rerouted) "Jatka $length.$rest" else "Aja $length.$rest"
            Lang.SV -> if (rerouted) "Fortsätt i $length.$rest" else "Kör i $length.$rest"
            Lang.EN -> if (rerouted) "Continue for $length.$rest" else "Drive for $length.$rest"
        }
    }

    private fun reroutedWithoutNext(match: MatchResult, lang: Lang): String? {
        val name = match.groupValues[1].trim()
        return when (lang) {
            Lang.FI -> if (name.isEmpty()) "Jatka eteenpäin." else "Jatka ${roadForm(name, RoadCase.ADESSIVE)}."
            Lang.SV -> if (name.isEmpty()) "Fortsätt." else "Fortsätt på $name."
            Lang.EN -> if (name.isEmpty()) "Continue." else "Continue on $name."
        }
    }

    /** Askeleen alun "Jatka 2.5 kilometriä." kertoo myös seuraavan käännöksen. */
    private fun continueWithNext(instructions: List<PlannedInstruction>, lang: Lang, next: String?): List<PlannedInstruction> {
        if (next == null) return instructions
        val first = instructions.first()
        val continueOnly = when (lang) {
            Lang.FI -> FI_CONTINUE
            Lang.SV -> SV_CONTINUE
            Lang.EN -> EN_CONTINUE
        }
        if (!continueOnly.matches(first.text)) return instructions
        return listOf(first.copy(text = continueThen(first.text.dropLast(1), next, lang))) + instructions.drop(1)
    }

    /** Pitkä askel: ennakkoilmoitus 3 tai 2 km ennen, ellei Valhallan oma ilmoitus ole lähellä. */
    private fun withEarlyAlert(instructions: List<PlannedInstruction>, distanceM: Double, lang: Lang, next: String?): List<PlannedInstruction> {
        if (next == null) return instructions
        val km = when {
            distanceM >= EARLY_3KM_MIN_STEP_M -> 3
            distanceM >= EARLY_2KM_MIN_STEP_M -> 2
            else -> return instructions
        }
        val at = km * 1000.0
        if (instructions.any { kotlin.math.abs(it.triggerM - at) < EARLY_ALERT_GAP_M }) return instructions
        val text = when (lang) {
            Lang.FI -> "$km kilometrin päästä $next"
            Lang.SV -> when {
                next.startsWith(SV_TARGET) -> "Om $km kilometer ligger målet${next.removePrefix(SV_TARGET)}"
                next.startsWith(SV_ARRIVAL) -> "Om $km kilometer är du framme${next.removePrefix(SV_ARRIVAL)}"
                else -> "Om $km kilometer, $next"
            }
            Lang.EN -> "In $km kilometers, ${enFuture(next)}"
        }
        return insertByTrigger(instructions, PlannedInstruction(text, at))
    }

    /** Viimeinen askel vähintään 300 m ja Valhallan 100 m:n ohje olemassa: puoli kerrotaan 250 m ennen. */
    private fun withArrivalAlert(instructions: List<PlannedInstruction>, distanceM: Double, side: ArrivalSide, lang: Lang): List<PlannedInstruction> {
        if (side == ArrivalSide.UNKNOWN || distanceM < ARRIVAL_ALERT_MIN_STEP_M) return instructions
        if (instructions.none { it.triggerM in ARRIVAL_100M_RANGE }) return instructions
        val left = side == ArrivalSide.LEFT
        val text = when (lang) {
            Lang.FI -> "Kohde on $ARRIVAL_ALERT_M metrin päässä ${if (left) "vasemmalla" else "oikealla"}."
            Lang.SV -> "Om $ARRIVAL_ALERT_M meter ligger målet till ${if (left) "vänster" else "höger"}."
            Lang.EN -> "In $ARRIVAL_ALERT_M meters, your destination is on the ${if (left) "left" else "right"}."
        }
        return insertByTrigger(instructions, PlannedInstruction(text, ARRIVAL_ALERT_M.toDouble()))
    }

    private fun insertByTrigger(instructions: List<PlannedInstruction>, added: PlannedInstruction): List<PlannedInstruction> {
        val index = instructions.indexOfFirst { it.triggerM < added.triggerM }.let { if (it < 0) instructions.size else it }
        return instructions.toMutableList().apply { add(index, added) }
    }

    private fun lengthText(meters: Long, lang: Lang): String {
        if (meters < 1000) {
            return when (lang) {
                Lang.FI -> "$meters metriä"
                Lang.SV -> "$meters meter"
                Lang.EN -> "$meters meters"
            }
        }
        val whole = meters % 1000 == 0L
        val km = if (whole) (meters / 1000).toString() else String.format(java.util.Locale.ROOT, "%.1f", meters / 1000.0)
        return when (lang) {
            Lang.FI -> if (km == "1") "1 kilometri" else "${km.replace('.', ',')} kilometriä"
            Lang.SV -> "${km.replace('.', ',')} kilometer"
            Lang.EN -> if (km == "1") "1 kilometer" else "$km kilometers"
        }
    }

    private fun stripMarks(text: String) = text.filterNot { it == '‎' || it == '‏' }

    /** Vaihdetulla reitillä alle tämän pituinen ensimmäinen askel: pelkkä seuraava ohje. */
    private const val REROUTE_CONTINUE_MIN_M = 150.0
    private const val EARLY_3KM_MIN_STEP_M = 3_500.0
    private const val EARLY_2KM_MIN_STEP_M = 2_500.0
    private const val EARLY_ALERT_GAP_M = 150.0
    private const val ARRIVAL_ALERT_MIN_STEP_M = 300.0

    /** Oma saapumislause laukeaa tässä matkassa (ArrivalDetector.ROUTE_END_M); sen alle jäävät Valhallan ohjeet pois. */
    private const val ARRIVAL_SELF_M = 50.0
    private const val ARRIVAL_ALERT_M = 250
    private val ARRIVAL_100M_RANGE = 90.0..120.0

    /** Numero tai tienumero (55, E 63, Valtatie 3, Kehä I) nimen alussa. */
    private val ROAD_REF = Regex("""^(?:\d|E ?\d|Valtatie\b|Kantatie\b|Seututie\b|Kehä )""")

    /** Valhallan bear_verbal 3: "Jatka<suunta> Jatkaaksesi kadulla <katu>" (sama kuin InstructionText). */
    private val FI_BEAR_TWICE = Regex("""\bJatka(vasemmalle|oikealle) Jatkaaksesi kadulla """)

    /** Nimi päättyy pisteeseen, sanaan "suuntana" tai genetiivimuodon jatkoon ("risteyksessä", "lautalle"). */
    private val FI_STREET = Regex("""\b(kadulle|kadulla|kadun) (.+?)(?=\.(?:\s|$)| ja suuntana\b| suuntana\b| risteyksessä\b| lautalle\b)""")
    private val FI_DEPART =
        Regex("""^(?:Aja|Suuntaa) (?:pohjoiseen|koilliseen|itään|kaakkoon|etelään|lounaaseen|länteen|luoteeseen)(?: kadulla (.+?))?\.(.*)$""")
    private val SV_DEPART =
        Regex("""^(?:Kör|Gå|Cykla) (?:norrut|söderut|österut|västerut|mot nordöst|mot sydöst|mot sydväst|mot nordväst)(?: (?:på |on )?(.+?))?\.(.*)$""")
    private val EN_DEPART = Regex("""^(?:Drive|Head|Walk|Bike) (?:north|northeast|east|southeast|south|southwest|west|northwest)(?: on (.+?))?\.(.*)$""")
    private const val SV_TARGET = "målet ligger"
    private const val SV_ARRIVAL = "du är framme"
    private const val EN_ARRIVED = "you have arrived at your destination"
    private const val EN_WILL_ARRIVE = "you will arrive at your destination"
    private val SV_IMPERATIVE = Regex("""^(?:sväng|kör|ta|håll|gör)\b""")
    private val SV_DESTINATION_AFTER = Regex("""^(Om [\d.,]+ \p{L}+), Din destination (?:är|kommer att vara)\b""")
    private val SV_DESTINATION = Regex("""\bDin destination (?:är|kommer att vara)\b""")
    private val SV_ARRIVING_AFTER = Regex("""^(Om [\d.,]+ \p{L}+), Du kommer att anlända till din destination\b""")
    private val SV_ARRIVING = Regex("""\bDu kommer att anlända till din destination\b""")
    private val SV_ARRIVED = Regex("""\bDu har anlänt till din destination\b""")
    private val FI_THEN = Regex(""" Sitten\b""")
    private val SV_THEN = Regex(""" Därefter\b""")
    private val EN_THEN = Regex(""" Then\b""")
    private const val LENGTH = """((?:mindre än |less than |alle )?\d+(?:[.,]\d+)? \p{L}+)"""
    private val FI_DEPART_LENGTH =
        Regex("""^(?:Aja|Suuntaa) (?:pohjoiseen|koilliseen|itään|kaakkoon|etelään|lounaaseen|länteen|luoteeseen) $LENGTH\.(.*)$""")
    private val SV_DEPART_LENGTH =
        Regex("""^(?:Kör|Gå|Cykla) (?:norrut|söderut|österut|västerut|mot nordöst|mot sydöst|mot sydväst|mot nordväst) i $LENGTH\.(.*)$""")
    private val EN_DEPART_LENGTH =
        Regex("""^(?:Drive|Head|Walk|Bike) (?:north|northeast|east|southeast|south|southwest|west|northwest) for $LENGTH\.(.*)$""")

    /** Jatka-lause ilman suuntaa ("Jatka vasemmalle" on käännös) ja ilman jo liitettyä jatkoa. */
    private val FI_CONTINUE = Regex("""^Jatka(?! (?:vasemmalle|oikealle|vasemmalla|oikealla|suoraan|Y-risteyksessä|eteenpäin))(?:[^,]*)\.$""")
    private val SV_CONTINUE = Regex("""^Fortsätt(?! (?:vänster|höger|rakt))(?:(?! och )[^,])*\.$""")
    private val EN_CONTINUE = Regex("""^Continue(?! (?:left|right|straight))(?:[^,]*)\.$""")
}
