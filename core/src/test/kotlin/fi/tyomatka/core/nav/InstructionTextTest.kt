package fi.tyomatka.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Syötteet ovat FOSSGIS-Valhallan oikeista vastauksista (Tampere keskusta → Hervanta, 18.10.2026) ja Valhallan
 * kielitiedostojen fraaseista. Valhalla merkitsee osan nimistä U+200E-merkeillä.
 */
class InstructionTextTest {

    private fun fi(text: String) = InstructionText.forSpeech(text, "fi-FI")
    private fun sv(text: String) = InstructionText.forSpeech(text, "sv-SE")
    @Test
    fun `ruotsin numerotie saa sanan väg`() {
        assertEquals("Sväng vänster in på väg 29, E 04.", sv("Sväng vänster in på 29, E 04."))
        assertEquals("Fortsätt på väg 29, Perämerentie i 2 kilometer.", sv("Fortsätt på 29, Perämerentie i 2 kilometer."))
        assertEquals("Kör ut ur rondellen mot väg 120, Keskusta.", sv("Kör ut ur rondellen mot 120, Keskusta."))
        assertEquals("Håll till höger och ta väg 101 mot väg 1, Kannelmäki.", sv("Håll höger för att ta 101 mot 1, Kannelmäki."))
        assertEquals("Fortsätt i 2 kilometer och ta sedan väg 101.", sv("Fortsätt i 2 kilometer och ta sedan 101."))
    }

    @Test
    fun `ruotsin järjestysluku ja numeroitu avfart eivät muutu`() {
        assertEquals(
            "Kör in i rondellen och ta den 2:a avfarten mot väg 29, Kemi.",
            sv("Kör in i rondellen och ta den 2:a avfarten mot 29, Kemi."),
        )
        assertEquals("Ta avfart 39 mot Paattio, Lautiosaari.", sv("Ta avfart 39 mot Paattio, Lautiosaari."))
        assertEquals("Kör in i rondellen och ta den 3:e avfarten till Pitäjänmäentie.", sv("Kör in i rondellen och ta den 3:e avfarten in på Pitäjänmäentie."))
        assertEquals("Ta avfart 3 till väg 101.", sv("Ta avfart 3 in på 101."))
        assertEquals("Håll till höger för att stanna kvar på Vihdintie.", sv("Håll höger för att stanna kvar på Vihdintie."))
        assertEquals("Gör en U-sväng till vänster.", sv("Gör en vänster U-sväng."))
        assertEquals("Gör en U-sväng till höger in på Testitie.", sv("Gör en höger U-sväng in på Testitie."))
        assertEquals("Sväng vänster in på Testitie. Sväng sedan höger.", sv("Sväng vänster in på Testitie. Därefter Sväng höger."))
        assertEquals("Börja. Därefter din destination.", sv("Börja. Därefter Din destination."))
        assertEquals("Ta avfarten till vänster.", sv("Ta avfarten till vänster ."))
        assertEquals("Fortsätt i 500 meter.", sv("Fortsätt i 500 meter."))
        assertEquals("Sväng höger in på Testitie 5.", sv("Sväng höger in på Testitie 5."))
    }

    @Test
    fun `englannin numerotie ja ramppi sanotaan luonnollisesti`() {
        assertEquals("Turn left onto road 29, E 04.", en("Turn left onto 29, E 04."))
        assertEquals("Continue on road 29, Perämerentie for 2 kilometers.", en("Continue on 29, Perämerentie for 2 kilometers."))
        assertEquals("Exit the roundabout toward road 120, Keskusta.", en("Exit the roundabout toward 120, Keskusta."))
        assertEquals("Keep right to take road 101 toward road 1, Kannelmäki.", en("Keep right to take 101 toward 1, Kannelmäki."))
        assertEquals("Turn right to take the ramp to road 3 toward Helsinki.", en("Turn right to take the 3 ramp toward Helsinki."))
        assertEquals("Take the exit to road 120 toward Vihti, Konala.", en("Take the 120 exit toward Vihti, Konala."))
        assertEquals("In 500 meters, take the exit to E 12.", en("In 500 meters, Take the E 12 exit."))
    }

    @Test
    fun `numerotien reunatapaukset`() {
        assertEquals("Ta avfart 39 till väg 9.", sv("Ta avfart 39 in på  9."))
        assertEquals("Ta avfarten till väg 9, E 12 mot Vaasa.", sv("Ta 9, E 12avfarten mot Vaasa."))
        assertEquals("Sväng höger in på 2. linjan.", sv("Sväng höger in på 2. linjan."))
        assertEquals("Gör en U-sväng vid väg 29.", sv("Gör en U-sväng vid 29."))
        assertEquals("Sväng höger in på väg 29. Sväng sedan vänster.", sv("Sväng höger in på 29. Därefter Sväng vänster."))
        assertEquals("Turn right onto 1. linja.", en("Turn right onto 1. linja."))
        assertEquals("Make a U-turn at road 29.", en("Make a U-turn at 29."))
        assertEquals("Take the exit to road 9, E 12.", en("Take the 9, E 12 exit."))
    }

    @Test
    fun `englannin järjestysluku ja numeroitu liittymä eivät muutu`() {
        assertEquals("Take exit 3 onto road 101.", en("Take exit 3 onto 101."))
        assertEquals("Enter the roundabout and take the 3rd exit onto Pitäjänmäentie.", en("Enter the roundabout and take the 3rd exit onto Pitäjänmäentie."))
        assertEquals("Continue for 500 meters.", en("Continue for 500 meters."))
        assertEquals("Take the Hervanta exit.", en("Take the Hervanta exit."))
        assertEquals("Turn right onto Testitie 5.", en("Turn right onto Testitie 5."))
    }

    private fun en(text: String) = InstructionText.forSpeech(text, "en-US")

    @Test
    fun `suomen etaisyysalku kaannetaan genetiiviksi ja ohje alkaa pienella`() {
        assertEquals(
            "100 metrin päästä käänny oikealle kadulle Aleksanterinkatu.",
            fi("In 100 metriä, Käänny oikealle kadulle Aleksanterinkatu."),
        )
        assertEquals("800 metrin päästä valitse liittymä 40.", fi("In 800 metriä, Valitse ulosmeno 40."))
    }

    @Test
    fun `suomen kilometrit ja yksikko kilometri`() {
        assertEquals("1 kilometrin päästä käänny vasemmalle.", fi("In 1 kilometri, Käänny vasemmalle."))
        assertEquals("1,5 kilometrin päästä käänny vasemmalle.", fi("In 1.5 kilometriä, Käänny vasemmalle."))
    }

    @Test
    fun `suomen alle kymmenen metria alkaa isolla`() {
        assertEquals(
            "Alle 10 metrin päästä määränpääsi on oikealla.",
            fi("In alle 10 metriä, Määränpääsi on oikealla."),
        )
    }

    @Test
    fun `suomen desimaalipiste pilkuksi`() {
        assertEquals("Jatka 1,5 kilometriä.", fi("Jatka 1.5 kilometriä."))
    }

    @Test
    fun `suomen nakymattomat merkit pois ja sitten-ohje pienella`() {
        assertEquals(
            "Valitse liittymä 40 tielle 9 suuntana Vaasa, Pori. Sitten jatka oikealla siirtyäksesi kadulle 9.",
            fi(
                "Valitse ulosmeno 40 haaraan \u200e\u200e9 ja suuntana Vaasa, Pori. " +
                    "Sitten Jatka oikealla siirtyäksesi kadulle \u200e\u200e9."
            ),
        )
    }

    @Test
    fun `suomen sitten matkan jalkeen pienella`() {
        assertEquals(
            "Käänny oikealle. Sitten, kun matkaa on kulunut 200 metriä, käänny vasemmalle.",
            fi("Käänny oikealle. Sitten, kun matkaa on kulunut 200 metriä, Käänny vasemmalle."),
        )
    }

    @Test
    fun `suomen kaantamattomat silta ja tunneli`() {
        assertEquals("Jatka sillalla.", fi("Jatka kadulla the bridge."))
        assertEquals("Käänny oikealle tunneliin.", fi("Käänny oikealle kadulle the tunnel."))
    }

    @Test
    fun `suomen haaralause tien numerolle ilman ja-sanaa`() {
        // "valitse ulosmeno 50a haaraan 4 ja suuntana Helsinki" kuulosti kömpelöltä.
        assertEquals("Valitse liittymä 50a tielle 4 suuntana Helsinki.", fi("Valitse ulosmeno 50a haaraan 4 ja suuntana Helsinki."))
        assertEquals(
            "Valitse liittymä 40 tielle 9 suuntana Vaasa, Pori.",
            fi("Valitse ulosmeno 40 haaraan \u200e\u200e9 ja \u200esuuntana Vaasa, Pori."),
        )
        assertEquals(
            "Jatka 3 kilometriä, sitten valitse liittymä 50a tielle 4 suuntana Helsinki.",
            fi("Jatka 3 kilometriä, sitten valitse ulosmeno 50a haaraan 4 ja suuntana Helsinki."),
        )
        assertEquals("Valitse liittymä 40 oikealla tielle 9.", fi("Valitse ulosmeno 40 oikealla \u200ehaaraan 9."))
    }

    @Test
    fun `suomen moottoritien ulosmeno on liittyma mutta liikenneympyran ulosmeno sailyy`() {
        // "liittymä" on parempi kuin "ulosmeno" tai "kaista"; 50a on liittymän numero.
        assertEquals("800 metrin päästä valitse liittymä 40.", fi("In 800 metriä, Valitse ulosmeno 40."))
        assertEquals("Valitse liittymä suuntana Hervanta.", fi("Valitse ulosmeno suuntana Hervanta."))
        assertEquals("Jatka oikealla valitaksesi liittymän 40.", fi("Jatka oikealla valitaksesi \u200eulosmenon 40."))
        assertEquals(
            "Mene kiertoliittymään ja valitse 1. ulosmeno kadulle Insinöörinkatu.",
            fi("Mene kiertoliittymään ja valitse \u200e\u200e1. ulosmeno kadulle Insinöörinkatu."),
        )
        assertEquals("Mene kiertoliittymään ja valitse ulosmeno suuntana Hervanta.", fi("Mene kiertoliittymään ja valitse ulosmeno suuntana Hervanta."))
        assertEquals("Mene kadulle Hatanpään valtatie ja valitse ulosmeno kadulle Lempääläntie.", fi("Mene kadulle Hatanpään valtatie ja valitse ulosmeno \u200ekadulle Lempääläntie."))
    }

    @Test
    fun `suomen moitteeton ohje pysyy ennallaan`() {
        assertEquals("Aja itään kadulla Hämeenkatu.", fi("Aja itään kadulla Hämeenkatu."))
    }

    @Test
    fun `ruotsin etaisyysalun jalkeen pienella`() {
        assertEquals(
            "Om 100 meter, sväng höger in på Aleksanterinkatu.",
            sv("Om 100 meter, Sväng höger in på Aleksanterinkatu."),
        )
    }

    @Test
    fun `ruotsin puuttuva pa ja englanninkielinen on`() {
        assertEquals("Kör österut på Hämeenkatu.", sv("Kör österut  Hämeenkatu."))
        assertEquals(
            "Kör söderut på Hämeenkatu. Fortsätt på Itsenäisyydenkatu.",
            sv("Kör söderut on Hämeenkatu. Fortsätt på Itsenäisyydenkatu."),
        )
    }

    @Test
    fun `ruotsin desimaalipiste pilkuksi`() {
        assertEquals("Fortsätt i 1,5 kilometer.", sv("Fortsätt i 1.5 kilometer."))
    }

    @Test
    fun `ruotsin darefter pienella myos matkan jalkeen`() {
        assertEquals(
            "Ta avfart 40 till väg 9 mot Vaasa, Pori. Håll sedan till höger och ta väg 9.",
            sv("Ta avfart 40 in på 9 mot Vaasa, Pori. Därefter Håll höger för att ta 9."),
        )
        assertEquals(
            "Sväng höger. Efter 200 meter, sväng vänster.",
            sv("Sväng höger. Därefter, i 200 meter, Sväng vänster."),
        )
    }

    @Test
    fun `ruotsin kaantamaton silta`() {
        assertEquals("Fortsätt på bron.", sv("Fortsätt på the bridge."))
    }

    @Test
    fun `englannin etaisyysalun ja then-ohjeen jalkeen pienella`() {
        assertEquals(
            "In 100 meters, turn right onto Aleksanterinkatu.",
            en("In 100 meters, Turn right onto Aleksanterinkatu."),
        )
        assertEquals(
            "Turn right onto Teekkarinkatu. Then enter the roundabout and take the 1st exit onto Insinöörinkatu.",
            en("Turn right onto Teekkarinkatu. Then Enter the roundabout and take the 1st exit onto Insinöörinkatu."),
        )
    }

    @Test
    fun `englannin desimaalipiste sailyy`() {
        assertEquals("Continue for 1.5 kilometers.", en("Continue for 1.5 kilometers."))
    }

    @Test
    fun `suomen jatka-ohjeesta toinen verbi pois myos matkan ja sitten-ohjeen jalkeen`() {
        assertEquals(
            "Jatka vasemmalle pysyäksesi kadulla Hämeenkatu.",
            fi("Jatkavasemmalle Jatkaaksesi kadulla \u200e\u200eHämeenkatu."),
        )
        assertEquals(
            "300 metrin päästä jatka oikealle pysyäksesi kadulla 9, E 63.",
            fi("In 300 metriä, Jatkaoikealle Jatkaaksesi kadulla \u200e\u200e9, E 63."),
        )
        assertEquals(
            "Käänny oikealle. Sitten jatka vasemmalle pysyäksesi sillalla.",
            fi("Käänny oikealle. Sitten Jatkavasemmalle Jatkaaksesi kadulla \u200e\u200ethe bridge."),
        )
    }

    @Test
    fun `suomen sitten matkan jalkeen pienella myos pilkkudesimaalilla`() {
        assertEquals(
            "Käänny oikealle. Sitten, kun matkaa on kulunut 1,5 kilometriä, käänny vasemmalle.",
            fi("Käänny oikealle. Sitten, kun matkaa on kulunut 1,5 kilometriä, Käänny vasemmalle."),
        )
    }

    @Test
    fun `ruotsin kor in -ohjeesta toinen verbi pois`() {
        assertEquals("Kör in till vänster på Hämeenkatu.", sv("Kör in till vänster Kör in på Hämeenkatu."))
        assertEquals("Om 300 meter, kör in till höger på bron.", sv("Om 300 meter, Kör in till höger Kör in på the bridge."))
    }

    @Test
    fun `ruotsin puuttuvat valilyonnit`() {
        assertEquals("Fortsätt mot Helsinki, Turku.", sv("Fortsätt motHelsinki, Turku."))
        assertEquals("Fortsätt mot E 12.", sv("Fortsätt motE 12."))
        assertEquals("Ta avfarten till vänster mot väg 9.", sv("Ta 9avfarten till vänster ."))
        assertEquals("Ta avfarten till höger mot Valtatie 3, E 12.", sv("Ta Valtatie 3avfarten till höger mot E 12."))
        assertEquals("Ta avfarten till vänster mot Hervanta.", sv("Ta Hervantaavfarten till vänster ."))
        assertEquals("Håll rakt fram och ta påfarten till väg 9.", sv("Håll rakt fram för att ta9 påfarten."))
        assertEquals("Håll rakt fram och ta påfarten till E 12.", sv("Håll rakt fram för att taE 12 påfarten."))
        assertEquals("Om 1 kilometer, ta avfarten till höger mot väg 9.", sv("Om 1 kilometer, Ta 9avfarten till höger."))
        assertEquals("Ta avfarten till väg 120 mot Vihti, Konala.", sv("Ta 120avfarten mot Vihti, Konala."))
        assertEquals("Sväng höger och ta påfarten till väg 3 mot Helsinki.", sv("Sväng höger för att ta 3påfarten mot Helsinki."))
        assertEquals("Fortsätt i 6 kilometer och ta sedan avfarten till väg 120 mot Vihti.", sv("Fortsätt i 6 kilometer och ta sedan 120avfarten mot Vihti."))
        assertEquals("Fortsätt i 6 kilometer och ta sedan avfarten till vänster.", sv("Fortsätt i 6 kilometer och ta sedan avfarten till vänster."))
    }

    @Test
    fun `ruotsin valilyonti ennen pistetta pois ja moitteeton ohje sailyy`() {
        assertEquals("Ta avfarten till vänster.", sv("Ta avfarten till vänster ."))
        assertEquals("Ta avfart 40 till vänster mot Helsinki.", sv("Ta avfart 40 till vänster mot Helsinki."))
        assertEquals("Fortsätt mot Helsinki i 2 kilometer.", sv("Fortsätt mot Helsinki i 2 kilometer."))
    }

    @Test
    fun `ruotsin darefter matkan jalkeen pienella myos pilkkudesimaalilla`() {
        assertEquals(
            "Sväng höger. Efter 1,5 kilometer, sväng vänster.",
            sv("Sväng höger. Därefter, i 1,5 kilometer, Sväng vänster."),
        )
    }
}
