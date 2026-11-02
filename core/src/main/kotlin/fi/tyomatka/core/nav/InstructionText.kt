package fi.tyomatka.core.nav

/**
 * FOSSGIS-Valhallan puheohjeiden korjaus ennen puhesynteesiä.
 *
 * Valhallan kielitiedostoissa suomen etäisyysalku on jäänyt kääntämättä ("In 100 metriä, Käänny …"), suomen ja
 * ruotsin yhdessä fraasissa verbi toistuu ("Jatkavasemmalle Jatkaaksesi kadulla", "Kör in till vänster Kör in på"),
 * ruotsista puuttuu välilyöntejä ("motHelsinki", "9avfarten", "ta9 påfarten") tai niitä on ennen pistettä, ruotsin
 * aloitusohjeesta puuttuu paikoin "på", tyhjän kadunnimen nimikkeet ovat englanniksi ("the bridge") ja osa nimistä on
 * merkitty näkymättömillä suuntamerkeillä. Lauseen sisään liitetty ohje alkaa isolla kirjaimella, joka kuulostaa
 * puheessa katkolta, joten se pienennetään. Kadunnimet jäävät perusmuotoon: taivutus vaatisi kielioppiaineiston.
 * Ruotsin ja englannin pelkkä tienumero ("in på 29", "onto 29") saa sanan väg tai road, ja ramppi sanotaan
 * luonnollisesti ("Ta avfarten till väg 120", "Take the exit to road 120").
 */
object InstructionText {

    fun forSpeech(text: String, languageTag: String): String {
        val visible = text.filterNot { it == LEFT_TO_RIGHT_MARK || it == RIGHT_TO_LEFT_MARK }
        val fixed = when (languageTag.substringBefore('-').lowercase()) {
            "fi" -> finnish(visible)
            "sv" -> swedish(visible)
            else -> english(visible)
        }
        return fixed.replace(SPACES, " ").trim().replaceFirstChar { it.uppercaseChar() }
    }

    private fun finnish(text: String): String {
        var s = FI_BEAR_TWICE.replace(text, "Jatka $1 pysyäksesi kadulla ")
        s = FI_DISTANCE.replace(s) { m ->
            val (below, amount, unit, letter) = m.destructured
            val genitive = if (unit.startsWith("kilo")) "kilometrin" else "metrin"
            "$below$amount $genitive päästä ${letter.lowercase()}"
        }
        s = FI_THEN_AFTER_DISTANCE.lowerFirst(s)
        s = FI_THEN.lowerFirst(s)
        s = DECIMAL_POINT.replace(s, "$1,$2")
        s = FI_BRANCH_TOWARD.replace(s, "tielle $1 suuntana")
        s = FI_BRANCH.replace(s, "tielle ")
        s = FI_EXIT.replace(s, "$1 liittymä")
        s = FI_EXIT_GENITIVE.replace(s, "valitaksesi liittymän")
        return FI_LABELS.fold(s) { acc, (english, finnish) -> acc.replace(english, finnish) }
    }

    private fun swedish(text: String): String {
        var s = SV_MERGE_TWICE.replace(text, "Kör in till $1 på ")
        s = SV_MOT_GLUED.replace(s, "mot ")
        s = SV_TA_GLUED.replace(s, "ta ")
        s = SV_RAMP_GLUED.replace(s, "$1 $2")
        s = SV_MISSING_PA.replace(s, "$1 $2 på ")
        s = s.replace(SPACES, " ")
        s = SV_DISTANCE.lowerFirst(s)
        s = SV_THEN_AFTER_DISTANCE.lowerFirst(s)
        s = SV_THEN.lowerFirst(s)
        s = DECIMAL_POINT.replace(s, "$1,$2")
        s = SPACE_BEFORE_STOP.replace(s, "$1")
        s = SV_THEN_IN.replace(s, "Efter ")
        s = SV_THEN_VERB.replace(s) { "${it.groupValues[1].replaceFirstChar(Char::uppercaseChar)} sedan" }
        s = SV_UTURN.replace(s, "$1 en U-sväng till $2")
        s = SV_IN_ORDER_TO_TAKE.replace(s, "och ta")
        s = SV_KEEP.replace(s, "$1 till $2")
        s = SV_RAMP.replace(s) { m ->
            val (verb, sign, ramp, side, toward) = m.destructured
            if (side.isEmpty()) {
                "$verb $ramp till $sign" + if (toward.isEmpty()) "" else " mot $toward"
            } else {
                "$verb $ramp$side mot " + listOf(sign, toward).filter { it.isNotEmpty() }.joinToString(", ")
            }
        }
        s = SV_EXIT_ONTO.replace(s, "$1 till ")
        s = SV_ROAD_NUMBER.replace(s, "$1 väg $2")
        return SV_LABELS.fold(s) { acc, (english, swedish) -> acc.replace(english, swedish) }
    }

    private fun english(text: String): String {
        var s = EN_DISTANCE.lowerFirst(text)
        s = EN_THEN_AFTER_DISTANCE.lowerFirst(s)
        s = EN_THEN.lowerFirst(s)
        s = EN_NUMBERED_RAMP.replace(s, "the $2 to $1")
        return EN_ROAD_NUMBER.replace(s, "$1 road $2")
    }

    /** Ryhmä 1 on säilyvä alku, ryhmä 2 pienennettävä kirjain. */
    private fun Regex.lowerFirst(text: String): String =
        replace(text) { m -> m.groupValues[1] + m.groupValues[2].lowercase() }

    /**
     * Valhallan exit-fraasi "Valitse ulosmeno 50a haaraan 4 ja suuntana Helsinki": haara on
     * tien numero tai nimi, joten "tielle 4 suuntana Helsinki" ilman ja-sanaa.
     */
    private val FI_BRANCH_TOWARD = Regex("""\bhaaraan (.+?) ja suuntana\b""")
    private val FI_BRANCH = Regex("""\bhaaraan """)

    /**
     * Moottoritien ulosmeno on liittymä (numero kuten 50a on liittymän numero). Liikenneympyrän fraasit
     * ovat aina muotoa "… ja valitse (1.) ulosmeno", ja niissä ulosmeno on oikea sana, joten ne jäävät ennalleen.
     */
    private val FI_EXIT = Regex("""(?<!\bja )\b([Vv]alitse) ulosmeno\b""")
    private val FI_EXIT_GENITIVE = Regex("""\bvalitaksesi ulosmenon\b""")
    private val LEFT_TO_RIGHT_MARK = Char(0x200E)
    private val RIGHT_TO_LEFT_MARK = Char(0x200F)
    private val SPACES = Regex(" {2,}")
    private val SPACE_BEFORE_STOP = Regex(""" +([.,])""")
    private val DECIMAL_POINT = Regex("""(\d)\.(\d)""")

    /** Pienennettävä kirjain vain, jos sitä seuraa pieni kirjain: lyhenteet ja tienumerot ("E 63") säilyvät. */
    private const val CAPITAL = """(\p{Lu})(?=\p{Ll})"""

    /** Matkan ilmaus pilkkuun asti; pilkkudesimaali ("1,5 kilometriä") ei katkaise sitä. */
    private const val UP_TO_COMMA = """(?:[^,]|,(?=\d))+"""

    /** Valhallan bear_verbal 3: "Jatka<suunta> Jatkaaksesi kadulla <katu>". */
    private val FI_BEAR_TWICE = Regex("""\bJatka(vasemmalle|oikealle) Jatkaaksesi kadulla """)
    private val FI_DISTANCE =
        Regex("""\bIn (alle )?(\d+(?:[.,]\d+)?) (kilometriä|kilometri|metriä|metri), (?:$CAPITAL)?""")
    private val FI_THEN = Regex("""(\bSitten )$CAPITAL""")
    private val FI_THEN_AFTER_DISTANCE = Regex("""(\bSitten, kun matkaa on kulunut $UP_TO_COMMA, )$CAPITAL""")
    private val FI_LABELS = listOf(
        "kadulla the bridge" to "sillalla",
        "kadulle the bridge" to "sillalle",
        "kadulla the tunnel" to "tunnelissa",
        "kadulle the tunnel" to "tunneliin",
        "the bridge" to "silta",
        "the tunnel" to "tunneli",
        "the gate" to "portti",
        "the bollards" to "pollarit",
        "the crosswalk" to "suojatie",
        "the stairs" to "portaat",
    )

    private const val SV_DIRECTIONS =
        "norrut|söderut|österut|västerut|mot nordöst|mot sydöst|mot sydväst|mot nordväst"
    /** Valhallan aloitusfraasi "Kör <suunta>  <katu>" (puuttuva "på") ja "Kör <suunta> on <katu>". */
    private val SV_MISSING_PA = Regex("""\b(Kör|Gå|Cykla) ($SV_DIRECTIONS)(?:  | on )(?=\S)""")
    /** Valhallan merge_verbal 3: "Kör in till <suunta> Kör in på <katu>". */
    private val SV_MERGE_TWICE = Regex("""\bKör in till (vänster|höger) Kör in på """)
    /** "Fortsätt mot<kyltti>" ja "för att ta<kyltti> påfarten": sana on kiinni kyltin tekstissä. */
    private val SV_MOT_GLUED = Regex("""\bmot(?=[\p{Lu}\d])""")
    private val SV_TA_GLUED = Regex("""\bta(?=[\p{Lu}\d])""")
    /** "Ta <kyltti>avfarten": kyltin teksti on kiinni sanassa. */
    private val SV_RAMP_GLUED = Regex("""(\S)(avfarten|påfarten)\b""")
    private val SV_DISTANCE = Regex("""(\bOm (?:mindre än )?\d+(?:[.,]\d+)? (?:kilometer|meter), )$CAPITAL""")
    private val SV_THEN = Regex("""(\bDärefter )$CAPITAL""")
    private val SV_THEN_AFTER_DISTANCE = Regex("""(\bDärefter, i $UP_TO_COMMA, )$CAPITAL""")
    private val SV_THEN_IN = Regex("""\bDärefter, i """)

    /** Käskyssä "sedan" tulee verbin jälkeen: "Därefter sväng höger" → "Sväng sedan höger". */
    private val SV_THEN_VERB = Regex("""\bDärefter (sväng|kör|ta|håll|gör|fortsätt)\b""")
    private val SV_UTURN = Regex("""\b([Gg]ör) en (vänster|höger) U-sväng\b""")
    private val SV_IN_ORDER_TO_TAKE = Regex("""\bför att ta\b""")
    private val SV_KEEP = Regex("""\b([Hh]åll(?: sedan)?) (vänster|höger)\b""")

    /** "Ta [sedan] <kyltti> avfarten [till höger] [mot <suunta>]". Järjestysluvun "ta den 2:a avfarten" kyltti ei ole. */
    private val SV_RAMP =
        Regex("""\b([Tt]a(?: sedan)?) (?!den\b|sedan\b)([^.]+?) (avfarten|påfarten)( till (?:vänster|höger))?(?: mot ([^.]+))?""")

    /** Liittymästä ajetaan tielle, ei tien sisään: "avfarten in på X" → "avfarten till X". */
    private val SV_EXIT_ONTO = Regex("""\b(avfarten|avfart \S+) in på """)

    /** Pelkkä numero prepositiosta seuraavana on tie; matka ja järjestysluku eivät ole. */
    private val SV_ROAD_NUMBER = Regex("""\b(på|mot|till|vid|[Tt]a(?: sedan)?) (\d{1,4})\b(?! ?(?:meter|kilometer)|:|\. ?\p{Ll})""")
    private val SV_LABELS = listOf(
        "the bridge" to "bron",
        "the tunnel" to "tunneln",
        "the gate" to "grinden",
        "the bollards" to "pollarna",
        "the crosswalk" to "övergångsstället",
        "the stairs" to "trappan",
    )

    private val EN_DISTANCE =
        Regex("""(\bIn (?:less than )?\d+(?:\.\d+)? (?:kilometers|kilometer|meters|meter), )$CAPITAL""")
    private val EN_THEN = Regex("""(\bThen )$CAPITAL""")
    private val EN_NUMBERED_RAMP = Regex("""\bthe ((?:E ?)?\d{1,4}(?:, (?:E ?)?\d{1,4})*) (exit|ramp)\b""")
    private val EN_ROAD_NUMBER = Regex("""\b(onto|on|towards?|to|at|[Tt]ake) (\d{1,4})\b(?! ?(?:meters?|kilometers?)|:|\. ?\p{Ll})""")
    private val EN_THEN_AFTER_DISTANCE = Regex("""(\bThen, in [^,]+, )$CAPITAL""")
}
