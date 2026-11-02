package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechPlanTest {

    private fun step(distanceM: Double, vararg instructions: Pair<String, Double>) =
        PlannedStep(distanceM, instructions.map { PlannedInstruction(it.first, it.second) })

    private fun texts(step: PlannedStep) = step.instructions.map { it.text }

    private fun planFi(vararg steps: PlannedStep, rerouted: Boolean = false, side: ArrivalSide = ArrivalSide.UNKNOWN) =
        SpeechPlan.plan(steps.toList(), "fi-FI", rerouted, side)

    @Test
    fun `tien laji päätellään nimen päätteestä`() {
        assertEquals("tielle Tikkurilantie", SpeechPlan.roadForm("Tikkurilantie", RoadCase.ALLATIVE))
        assertEquals("kadulle Hämeenkatu", SpeechPlan.roadForm("Hämeenkatu", RoadCase.ALLATIVE))
        assertEquals("kadulla Hämeenkatu", SpeechPlan.roadForm("Hämeenkatu", RoadCase.ADESSIVE))
        assertEquals("kujalle Kirkkokuja", SpeechPlan.roadForm("Kirkkokuja", RoadCase.ALLATIVE))
        assertEquals("väylälle Itäväylä", SpeechPlan.roadForm("Itäväylä", RoadCase.ALLATIVE))
        assertEquals("tielle Hatanpään valtatie", SpeechPlan.roadForm("Hatanpään valtatie", RoadCase.ALLATIVE))
        assertEquals("katua Aleksis Kiven katu", SpeechPlan.roadForm("Aleksis Kiven katu", RoadCase.PARTITIVE))
    }

    @Test
    fun `numero ja tienumero ovat teitä`() {
        assertEquals("tielle 55", SpeechPlan.roadForm("55", RoadCase.ALLATIVE))
        assertEquals("tiellä 9, E 63", SpeechPlan.roadForm("9, E 63", RoadCase.ADESSIVE))
        assertEquals("tielle Valtatie 3", SpeechPlan.roadForm("Valtatie 3", RoadCase.ALLATIVE))
        assertEquals("tielle Kehä I", SpeechPlan.roadForm("Kehä I", RoadCase.ALLATIVE))
        assertEquals("tielle E 18", SpeechPlan.roadForm("E 18", RoadCase.ALLATIVE))
    }

    @Test
    fun `tuntematon pääte on tie`() {
        assertEquals("tielle Nuijamiestentie", SpeechPlan.roadForm("Nuijamiestentie", RoadCase.ALLATIVE))
        assertEquals("rinteelle Mäkelänrinne", SpeechPlan.roadForm("Mäkelänrinne", RoadCase.ALLATIVE))
        assertEquals("tielle Outolinna", SpeechPlan.roadForm("Outolinna", RoadCase.ALLATIVE))
    }

    @Test
    fun `kadulle ja kadulla vaihtuvat lajin mukaan`() {
        val planned = planFi(
            step(500.0, "Käänny oikealle kadulle 1605." to 60.0),
            step(900.0, "Jatka kadulla 55." to 890.0, "Jatka vasemmalla pysyäksesi kadulla ‎‎9, E 63." to 250.0),
            step(300.0, "Käänny oikealle kadulle 9, E 63 suuntana Vaasa." to 60.0),
            step(300.0, "Käänny oikealle kadulle Teekkarinkatu. Sitten Mene kiertoliittymään ja valitse 1. ulosmeno kadulle Insinöörinkatu." to 150.0),
        )
        assertEquals(listOf("Käänny oikealle tielle 1605."), texts(planned[0]))
        assertEquals("Jatka tiellä 55, sitten jatka vasemmalla pysyäksesi tiellä 9, E 63.", planned[1].instructions[0].text)
        assertEquals("Jatka vasemmalla pysyäksesi tiellä 9, E 63.", planned[1].instructions[1].text)
        assertEquals(listOf("Käänny oikealle tielle 9, E 63 suuntana Vaasa."), texts(planned[2]))
        assertEquals(
            listOf("Käänny oikealle kadulle Teekkarinkatu. Sitten Mene kiertoliittymään ja valitse 1. ulosmeno kadulle Insinöörinkatu."),
            texts(planned[3]),
        )
    }

    @Test
    fun `lähtöohje ei käytä ilmansuuntaa`() {
        val planned = planFi(
            step(400.0, "Aja luoteeseen kadulla Teollisuustie." to 400.0, "Käänny oikealle kadulle Pajatie." to 60.0),
        )
        assertEquals("Aja kohti tietä Teollisuustie.", planned[0].instructions[0].text)
        assertEquals("Lähde liikkeelle.", planFi(step(120.0, "Aja itään." to 120.0))[0].instructions[0].text)
        assertEquals("Aja kohti katua Hämeenkatu.", planFi(step(120.0, "Aja itään kadulla Hämeenkatu." to 120.0))[0].instructions[0].text)
    }

    @Test
    fun `jatka-lause kertoo seuraavan ja pitkä askel saa ennakkoilmoituksen`() {
        val planned = planFi(
            step(
                2384.0,
                "Jatka 2.5 kilometriä." to 2374.0,
                "In 900 metriä, Valitse ulosmeno suuntana Hervanta." to 875.0,
                "Valitse ulosmeno suuntana Hervanta." to 250.0,
            ),
        )
        val instructions = planned[0].instructions
        assertEquals("Jatka 2.5 kilometriä, sitten valitse ulosmeno suuntana Hervanta.", instructions[0].text)
        // Alle 2,5 km:n askel ei saa ennakkoa: Jatka-lause kertoo jo seuraavan, ja 2 km:n ilmoitus tulisi heti perään.
        assertEquals(listOf(2374.0, 875.0, 250.0), instructions.map { it.triggerM })
        val longer = planFi(
            step(
                2700.0,
                "Jatka 2.5 kilometriä." to 2690.0,
                "In 900 metriä, Valitse ulosmeno suuntana Hervanta." to 875.0,
                "Valitse ulosmeno suuntana Hervanta." to 250.0,
            ),
        )
        assertEquals(PlannedInstruction("2 kilometrin päästä valitse ulosmeno suuntana Hervanta.", 2000.0), longer[0].instructions[1])
        assertEquals(listOf(2690.0, 2000.0, 875.0, 250.0), longer[0].instructions.map { it.triggerM })
    }

    @Test
    fun `yli 3500 metrin askel saa ennakon 3 kilometriin`() {
        val planned = planFi(
            step(4000.0, "Jatka 4 kilometriä." to 3990.0, "In 900 metriä, Käänny oikealle kadulle Pajatie." to 875.0, "Käänny oikealle kadulle Pajatie." to 250.0),
        )
        assertEquals(PlannedInstruction("3 kilometrin päästä käänny oikealle tielle Pajatie.", 3000.0), planned[0].instructions[1])
    }

    @Test
    fun `ennakkoa ei lisätä valhallan oman ilmoituksen viereen`() {
        val planned = planFi(
            step(2600.0, "Jatka 2.5 kilometriä." to 2590.0, "In 1.9 kilometriä, Käänny oikealle kadulle Pajatie." to 1900.0, "Käänny oikealle kadulle Pajatie." to 250.0),
        )
        assertEquals(3, planned[0].instructions.size)
    }

    @Test
    fun `jatka-lause ilman seuraavaa ohjetta säilyy`() {
        val planned = planFi(step(300.0, "Jatka 300 metriä." to 290.0))
        assertEquals(listOf("Jatka 300 metriä."), texts(planned[0]))
    }

    @Test
    fun `saapuminen kertoo puolen 250 metriä ennen`() {
        val planned = planFi(
            step(400.0, "In 100 metriä, Määränpääsi on oikealla." to 106.9, "Määränpääsi on oikealla." to 40.0),
            side = ArrivalSide.RIGHT,
        )
        val instructions = planned[0].instructions
        assertEquals(PlannedInstruction("Kohde on 250 metrin päässä oikealla.", 250.0), instructions[0])
        assertEquals("In 100 metriä, Kohde on oikealla.", instructions[1].text)
        // Valhallan 40 m:n saapumisohje jää pois: oma Perillä-lause puhutaan 50 m:ssä (laitetesti 20.10.: kaksi lausetta peräkkäin).
        assertEquals(2, instructions.size)
    }

    @Test
    fun `viimeisen askeleen alle 50 metrin ohjeet poistetaan kaikilla kielillä`() {
        val fi = planFi(step(400.0, "In 100 metriä, Saavut määränpäähäsi." to 111.5, "Olet saapunut määränpäähäsi." to 40.0))
        assertEquals(listOf("In 100 metriä, Saavut määränpäähäsi."), texts(fi[0]))
        val en = SpeechPlan.plan(listOf(step(400.0, "In 100 meters, You will arrive." to 111.5, "You have arrived." to 40.0)), "en", false, ArrivalSide.UNKNOWN)
        assertEquals(listOf("In 100 meters, You will arrive."), texts(en[0]))
        // Muilla askeleilla lyhyt laukaisumatka säilyy.
        val mid = planFi(step(100.0, "Käänny oikealle kadulle Pajatie." to 40.0), step(400.0, "Olet saapunut määränpäähäsi." to 40.0))
        assertEquals(listOf("Käänny oikealle tielle Pajatie."), texts(mid[0]))
    }

    @Test
    fun `reitin lopun tyhjä askel ei estä saapumisen ilmoitusta`() {
        val planned = planFi(
            step(400.0, "In 100 metriä, Määränpääsi on oikealla." to 106.9, "Määränpääsi on oikealla." to 40.0),
            step(0.0),
            side = ArrivalSide.RIGHT,
        )
        assertEquals("Kohde on 250 metrin päässä oikealla.", planned[0].instructions[0].text)
        assertEquals(listOf(250.0, 106.9), planned[0].instructions.map { it.triggerM })
        assertTrue(planned[1].instructions.isEmpty())
    }

    @Test
    fun `250 metrin ilmoitusta ei lisätä kun jatka-lause kertoo jo puolen`() {
        val planned = planFi(
            step(400.0, "Jatka 400 metriä." to 390.0, "In 100 metriä, Määränpääsi on oikealla." to 106.9, "Määränpääsi on oikealla." to 40.0),
            side = ArrivalSide.RIGHT,
        )
        assertEquals(listOf("Jatka 400 metriä, sitten kohde on oikealla.", "In 100 metriä, Kohde on oikealla."), texts(planned[0]))
    }

    @Test
    fun `vaihdettu reitti ilman seuraavaa ohjetta ei käytä ilmansuuntaa`() {
        val named = planFi(step(300.0, "Aja itään kadulla Teollisuustie." to 300.0), rerouted = true)
        assertEquals(listOf("Jatka tiellä Teollisuustie."), texts(named[0]))
        val unnamed = planFi(step(300.0, "Aja itään." to 300.0), rerouted = true)
        assertEquals(listOf("Jatka eteenpäin."), texts(unnamed[0]))
    }

    @Test
    fun `lyhyt viimeinen askel ei saa 250 metrin ilmoitusta`() {
        val planned = planFi(step(180.0, "In 100 metriä, Määränpääsi on oikealla." to 106.9, "Määränpääsi on oikealla." to 40.0), side = ArrivalSide.RIGHT)
        assertEquals(listOf("In 100 metriä, Kohde on oikealla."), texts(planned[0]))
    }

    @Test
    fun `vaihdetun reitin lähtöohje on jatka-muodossa`() {
        val planned = planFi(
            step(420.0, "Aja itään kadulla Teollisuustie." to 420.0, "Käänny vasemmalle kadulle Pajatie." to 60.0),
            rerouted = true,
        )
        assertEquals("Jatka 400 metriä, sitten käänny vasemmalle tielle Pajatie.", planned[0].instructions[0].text)
        // Alle 150 m:n ensimmäinen askel: lähtöohje jää pois kokonaan, jotta sama käännös ei kuulu kahdesti.
        val short = planFi(step(120.0, "Aja itään kadulla Teollisuustie." to 120.0, "Käänny vasemmalle kadulle Pajatie." to 60.0), rerouted = true)
        assertEquals(listOf(PlannedInstruction("Käänny vasemmalle tielle Pajatie.", 60.0)), short[0].instructions)
        val km = planFi(step(1500.0, "Aja itään kadulla Teollisuustie." to 1500.0, "Tee U-käännös vasemmalle." to 60.0), rerouted = true)
        assertEquals("Jatka 1,5 kilometriä, sitten tee U-käännös vasemmalle.", km[0].instructions[0].text)
    }

    @Test
    fun `bear-lause ja sillan nimi säilyvät puhehetken korjauksille`() {
        // InstructionText.forSpeech hakee Valhallan alkuperäisiä ankkureita ("Jatkaaksesi kadulla", "kadulla the bridge"):
        // tien lajin vaihto ei saa rikkoa niitä.
        val planned = planFi(
            step(600.0, "In 300 metriä, Jatkaoikealle Jatkaaksesi kadulla \u200E\u200E9, E 63." to 300.0, "Jatkaoikealle Jatkaaksesi kadulla 9, E 63." to 60.0),
            step(200.0, "Jatka kadulla the bridge." to 190.0, "Käänny oikealle kadulle the tunnel." to 60.0),
        )
        val spoken = planned.flatMap { s -> s.instructions.map { InstructionText.forSpeech(it.text, "fi-FI") } }
        assertEquals("300 metrin päästä jatka oikealle pysyäksesi tiellä 9, E 63.", spoken[0])
        assertEquals("Jatka oikealle pysyäksesi tiellä 9, E 63.", spoken[1])
        assertEquals("Jatka sillalla, sitten käänny oikealle tunneliin.", spoken[2])
        assertEquals("Käänny oikealle tunneliin.", spoken[3])
    }

    @Test
    fun `puoli kerrotaan vain kun saapuminen tuli reitiltä`() {
        assertEquals("Olet perillä.", SpeechPlan.arrivalText("fi", ArrivalSide.RIGHT, sideKnown = false))
        assertEquals("Olet perillä. Kohde on oikealla.", SpeechPlan.arrivalText("fi", ArrivalSide.RIGHT, sideKnown = true))
    }

    @Test
    fun `perillä-lause kielen ja puolen mukaan`() {
        assertEquals("Olet perillä. Kohde on oikealla.", SpeechPlan.arrivalText("fi-FI", ArrivalSide.RIGHT))
        assertEquals("Olet perillä.", SpeechPlan.arrivalText("fi", ArrivalSide.UNKNOWN))
        assertEquals("Du är framme. Målet ligger till vänster.", SpeechPlan.arrivalText("sv-SE", ArrivalSide.LEFT))
        assertEquals("You have arrived.", SpeechPlan.arrivalText("en-US", ArrivalSide.UNKNOWN))
        assertEquals("You have arrived. Your destination is on the right.", SpeechPlan.arrivalText("en", ArrivalSide.RIGHT))
    }

    @Test
    fun `ruotsi ja englanti saavat jatka-lauseen ja ennakon`() {
        val sv = SpeechPlan.plan(
            listOf(step(4000.0, "Fortsätt i 4 kilometer." to 3990.0, "Om 900 meter, Sväng höger in på Pajatie." to 875.0, "Sväng höger in på Pajatie." to 250.0)),
            "sv-SE", rerouted = false, arrivalSide = ArrivalSide.UNKNOWN,
        )
        assertEquals("Fortsätt i 4 kilometer och sväng sedan höger in på Pajatie.", sv[0].instructions[0].text)
        assertEquals(PlannedInstruction("Om 3 kilometer, sväng höger in på Pajatie.", 3000.0), sv[0].instructions[1])
        val en = SpeechPlan.plan(
            listOf(step(420.0, "Drive east on Teollisuustie." to 420.0, "Turn left onto Pajatie." to 60.0)),
            "en-US", rerouted = true, arrivalSide = ArrivalSide.UNKNOWN,
        )
        assertEquals("Continue for 400 meters, then turn left onto Pajatie.", en[0].instructions[0].text)
    }

    private fun firstText(language: String, text: String): String =
        SpeechPlan.plan(listOf(step(400.0, text to 400.0)), language, false, ArrivalSide.UNKNOWN)[0].instructions[0].text

    @Test
    fun `ruotsin ja englannin lähtöohje ei käytä ilmansuuntaa`() {
        assertEquals("Kör mot Hämeenkatu.", firstText("sv", "Kör österut på Hämeenkatu."))
        assertEquals("Kör mot Isontammentie.", firstText("sv-SE", "Kör söderut  Isontammentie."))
        assertEquals("Kör i väg.", firstText("sv-SE", "Kör norrut."))
        assertEquals("Kör i väg. Därefter Sväng höger in på Testitie.", firstText("sv-SE", "Kör västerut. Därefter Sväng höger in på Testitie."))
        assertEquals("Head towards Isontammentie.", firstText("en-US", "Drive south on Isontammentie."))
        assertEquals("Start driving.", firstText("en-US", "Drive north."))
        assertEquals("Start driving. Then Turn right onto Testitie.", firstText("en-US", "Drive west. Then Turn right onto Testitie."))
    }

    @Test
    fun `ruotsi ja englanti kertovat kohteen puolen 250 metriä ennen`() {
        fun plan(language: String, side: ArrivalSide, vararg instructions: Pair<String, Double>) =
            SpeechPlan.plan(listOf(step(500.0, *instructions)), language, false, side)[0].instructions.map { it.text to it.triggerM }

        val sv = plan("sv-SE", ArrivalSide.RIGHT, "Sväng höger." to 490.0, "Om 100 meter, Din destination kommer att vara till höger." to 110.0)
        assertTrue(("Om 250 meter ligger målet till höger." to 250.0) in sv, sv.toString())
        val svLeft = plan("sv-SE", ArrivalSide.LEFT, "Sväng höger." to 490.0, "Om 100 meter, Din destination kommer att vara till vänster." to 110.0)
        assertTrue(("Om 250 meter ligger målet till vänster." to 250.0) in svLeft, svLeft.toString())
        val en = plan("en-US", ArrivalSide.LEFT, "Turn right." to 490.0, "In 100 meters, Your destination will be on the left." to 110.0)
        assertTrue(("In 250 meters, your destination is on the left." to 250.0) in en, en.toString())
        val enRight = plan("en-US", ArrivalSide.RIGHT, "Turn right." to 490.0, "In 100 meters, Your destination will be on the right." to 110.0)
        assertTrue(("In 250 meters, your destination is on the right." to 250.0) in enRight, enRight.toString())
    }

    @Test
    fun `ruotsin kohdelause on luonnollinen`() {
        fun last(vararg instructions: Pair<String, Double>) =
            SpeechPlan.plan(listOf(step(2_000.0, *instructions)), "sv-SE", false, ArrivalSide.UNKNOWN)[0].instructions.map { it.text }

        assertEquals(listOf("Målet ligger till höger."), last("Din destination är till höger." to 60.0))
        assertEquals(listOf("Om 200 meter ligger målet till vänster."), last("Om 200 meter, Din destination kommer att vara till vänster." to 215.0))
        assertEquals(listOf("Målet ligger till vänster."), last("Din destination kommer att vara till vänster." to 60.0))
        assertEquals(listOf("Du är framme."), last("Du har anlänt till din destination." to 60.0))
        assertEquals(listOf("Om 100 meter är du framme."), last("Om 100 meter, Du kommer att anlända till din destination." to 110.0))
        assertEquals(listOf("Du är snart framme."), last("Du kommer att anlända till din destination." to 110.0))
        assertEquals(
            "Fortsätt i 2 kilometer, sedan ligger målet till höger.",
            last("Fortsätt i 2 kilometer." to 1_990.0, "Din destination är till höger." to 60.0).first(),
        )
    }

    @Test
    fun `ruotsin ennakko pitää sanajärjestyksen kohteesta kerrottaessa`() {
        val steps = SpeechPlan.plan(
            listOf(step(4_000.0, "Fortsätt i 4 kilometer." to 3_990.0, "Din destination är till höger." to 60.0)),
            "sv-SE", false, ArrivalSide.UNKNOWN,
        )
        assertEquals(PlannedInstruction("Om 3 kilometer ligger målet till höger.", 3_000.0), steps[0].instructions[1])
    }

    private fun lastStep(language: String, vararg instructions: Pair<String, Double>) =
        SpeechPlan.plan(listOf(step(4_000.0, *instructions)), language, false, ArrivalSide.UNKNOWN)[0].instructions

    @Test
    fun `perillä sanotaan jatka-lauseessa ja ennakossa luonnollisesti`() {
        val sv = lastStep("sv-SE", "Fortsätt i 4 kilometer." to 3_990.0, "Du har anlänt till din destination." to 60.0)
        assertEquals("Fortsätt i 4 kilometer, så är du framme.", sv[0].text)
        assertEquals("Om 3 kilometer är du framme.", sv[1].text)
        val en = lastStep("en-US", "Continue for 4 kilometers." to 3_990.0, "You have arrived at your destination." to 60.0)
        assertEquals("Continue for 4 kilometers, then you will arrive at your destination.", en[0].text)
        assertEquals("In 3 kilometers, you will arrive at your destination.", en[1].text)
        val fi = lastStep("fi-FI", "Jatka 4 kilometriä." to 3_990.0, "Olet saapunut määränpäähäsi." to 60.0)
        assertEquals("Jatka 4 kilometriä, sitten olet perillä.", fi[0].text)
        assertEquals("3 kilometrin päästä olet perillä.", fi[1].text)
    }

    @Test
    fun `lyhyen reitin lähtölause korjaa myös kohdelauseen`() {
        fun only(language: String, text: String) =
            SpeechPlan.plan(listOf(step(30.0, text to 60.0)), language, false, ArrivalSide.UNKNOWN)[0].instructions[0].text
        assertEquals(
            "Kör mot Hämeenkatu. Därefter Målet ligger till vänster.",
            only("sv-SE", "Kör österut på Hämeenkatu. Därefter Din destination kommer att vara till vänster."),
        )
        assertEquals(
            "Aja kohti katua Hämeenkatu. Sitten Kohde on vasemmalla.",
            only("fi-FI", "Aja itään kadulla Hämeenkatu. Sitten Määränpääsi on vasemmalla."),
        )
    }

    @Test
    fun `pituudellinen lähtöohje ei käytä ilmansuuntaa`() {
        assertEquals("Kör i 2 kilometer.", firstText("sv-SE", "Kör norrut i 2 kilometer."))
        assertEquals("Drive for 2 kilometers.", firstText("en-US", "Drive north for 2 kilometers."))
        assertEquals("Drive for 1.5 kilometers.", firstText("en-US", "Head south for 1.5 kilometers."))
        assertEquals("Aja 2 kilometriä.", firstText("fi-FI", "Suuntaa pohjoiseen 2 kilometriä."))
        assertEquals("Kör i mindre än 10 meter.", firstText("sv-SE", "Kör norrut i mindre än 10 meter."))
        assertEquals("Drive for less than 10 meters.", firstText("en-US", "Drive north for less than 10 meters."))
        assertEquals("Aja alle 10 metriä.", firstText("fi-FI", "Aja pohjoiseen alle 10 metriä."))
        fun rerouted(language: String, text: String) =
            SpeechPlan.plan(listOf(step(2_000.0, text to 2_000.0)), language, true, ArrivalSide.UNKNOWN)[0].instructions[0].text
        assertEquals("Fortsätt i 2 kilometer.", rerouted("sv-SE", "Kör norrut i 2 kilometer."))
        assertEquals("Continue for 2 kilometers.", rerouted("en-US", "Drive north for 2 kilometers."))
        assertEquals("Jatka 2 kilometriä.", rerouted("fi-FI", "Aja pohjoiseen 2 kilometriä."))
    }

    @Test
    fun `ruotsin kohdelause myös desimaalimatkalla ja toiseksi viimeisellä askeleella`() {
        val last = SpeechPlan.plan(
            listOf(step(2_000.0, "Om 1,5 kilometer, Din destination kommer att vara till vänster." to 1_500.0)),
            "sv-SE", false, ArrivalSide.UNKNOWN,
        )[0].instructions.map { it.text }
        assertEquals(listOf("Om 1,5 kilometer ligger målet till vänster."), last)
        val middle = SpeechPlan.plan(
            listOf(
                step(200.0, "Sväng höger. Därefter Din destination är till höger." to 100.0),
                step(100.0, "Din destination är till höger." to 60.0),
            ),
            "sv-SE", false, ArrivalSide.UNKNOWN,
        )[0].instructions.map { it.text }
        assertEquals(listOf("Sväng höger. Därefter Målet ligger till höger."), middle)
    }

    @Test
    fun `ruotsin vaihdettu reitti käyttää samaa jatka-muotoa`() {
        val sv = SpeechPlan.plan(
            listOf(step(420.0, "Kör österut på Teollisuustie." to 420.0, "Sväng vänster in på Pajatie." to 60.0)),
            "sv-SE", rerouted = true, arrivalSide = ArrivalSide.UNKNOWN,
        )
        assertEquals("Fortsätt i 400 meter och sväng sedan vänster in på Pajatie.", sv[0].instructions[0].text)
    }

    @Test
    fun `matkat ja geometria eivät muutu`() {
        val planned = planFi(step(2384.0, "Jatka 2.5 kilometriä." to 2374.0, "Valitse ulosmeno suuntana Hervanta." to 250.0))
        assertEquals(2384.0, planned[0].distanceM)
        assertTrue(planned[0].instructions.all { it.triggerM <= 2384.0 })
        assertNull(planFi().firstOrNull())
    }
}
