package fi.tyomatka.data.repo

import fi.tyomatka.core.model.GeoPoint
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopMatchingTest {
    private val oulu = GeoPoint(65.01236, 25.47158)
    private val nextDoor = GeoPoint(65.01250, 25.47200)

    @Test
    fun `sama tunniste tai sama osoiteteksti on sama osoite`() {
        assertTrue(isSameAddress("osm:N:1", "Kauppakatu 10A, 90100 Oulu", oulu, "osm:N:1", "muu teksti", GeoPoint(0.0, 0.0)))
        assertTrue(isSameAddress("osm:N:1", "Kauppakatu 10A, 90100 Oulu", oulu, null, " kauppakatu 10a,  90100 oulu", oulu))
    }

    @Test
    fun `eri palveluiden tunnisteet eivät estä tuplan tunnistusta`() {
        // Hakupalvelut antavat samalle osoitteelle eri tunnisteen, eri postinumeron ja hieman eri pisteen.
        assertTrue(
            isSameAddress(
                "pelias:51d5", "Kauppakatu 10a, 90100 Oulu", oulu,
                "osm:N:2114939955", "Kauppakatu 10A, 90120 Oulu", GeoPoint(65.01238, 25.47185),
            )
        )
    }

    @Test
    fun `saman talon eri porras, asunto tai kirjoitusasu on sama stoppi`() {
        // samaan taloon riittää yksi stoppi, vaikka siellä olisi useampi yritys tai asunto.
        val otherEnd = GeoPoint(65.01290, 25.47230) // pitkän talon toinen porras ~70 m päässä
        assertTrue(isSameAddress("a", "Kauppakatu 5 A 3, 90100 Oulu", oulu, "b", "Kauppakatu 5 B 12, 90100 Oulu", otherEnd))
        assertTrue(isSameAddress("a", "Kauppakatu 5 A, Oulu", oulu, "b", "Kauppakatu 5, 90100 Oulu", oulu))
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "kauppakatu 5A, 90100 Oulu", oulu))
        // Ruotsinkielinen asunnon merkintä ja talonumeroiden väli.
        assertTrue(isSameAddress("a", "Storgatan 10 bst 12, Vasa", oulu, "b", "Storgatan 10, 65100 Vasa", oulu))
        assertTrue(isSameAddress("a", "Kauppakatu 5-7, Oulu", oulu, "b", "Kauppakatu 5, 90100 Oulu", oulu))
    }

    @Test
    fun `talossa oleva kohde on sama stoppi kuin talon osoite`() {
        // Kohteessa nimi on ensin ja katuosoite toisena.
        assertTrue(
            isSameAddress(
                "pelias:g", "Galleria 5, Hallituskatu 5, 90100 Oulu", GeoPoint(65.01236, 25.47158),
                "fi:1", "Hallituskatu 5, 90100 Oulu", GeoPoint(65.01250, 25.47170),
            )
        )
    }

    @Test
    fun `talonumeron kirjain ei erota stoppeja`() {
        // Numeroon kiinni kirjoitettu kirjain on sama asia kuin erillinen porras: samaan talonumeroon riittää yksi stoppi.
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 5b, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5, Oulu", oulu, "b", "Kauppakatu 5a, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 5 A, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5b, Oulu", oulu, "b", "Kauppakatu 5 B 12, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5a 3, Oulu", oulu, "b", "Kauppakatu 5 B, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Studio, Kauppakatu 5a, 90100 Oulu", oulu, "b", "Kauppakatu 5b, Oulu", nextDoor))
    }

    @Test
    fun `eri talonumero on eri talo, vaikka kirjain olisi sama`() {
        assertFalse(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 7a, Oulu", nextDoor))
        assertFalse(isSameAddress("a", "Kauppakatu 5 A 3, Oulu", oulu, "b", "Kauppakatu 3 A 5, Oulu", nextDoor))
    }

    @Test
    fun `kirjaimella erottuvat kohteen nimet eri kaduilla ovat eri stopit`() {
        assertFalse(isSameAddress("a", "Studio 24a, Hallituskatu, Oulu", oulu, "b", "Studio 24b, Kirkkokatu, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Studio 24a, Hallituskatu, Oulu", oulu, "b", "Studio 24b, Hallituskatu, Oulu", nextDoor))
    }

    @Test
    fun `kadun nimen lopussa oleva numero ei ole talonumero`() {
        assertFalse(isSameAddress("a", "Valtatie 4 12, Oulu", oulu, "b", "Valtatie 4 14, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Valtatie 4 12, Oulu", oulu, "b", "Valtatie 4 12, 90100 Oulu", nextDoor))
    }

    @Test
    fun `samanniminen liike eri osoitteessa on eri stoppi`() {
        val station = GeoPoint(60.1710, 24.9414)
        val nearby = GeoPoint(60.1695, 24.9450) // ~250 m
        assertFalse(
            isSameAddress("a", "R-kioski, Mannerheimintie 5, 00100 Helsinki", station, "b", "R-kioski, Kaivokatu 8, 00100 Helsinki", nearby)
        )
        // Katuosoitteet ratkaisevat myös aivan vieressä, vaikka nimi on sama.
        val corner = GeoPoint(60.17140, 24.94220) // ~50 m
        assertFalse(
            isSameAddress("a", "R-kioski, Mannerheimintie 5, 00100 Helsinki", station, "b", "R-kioski, Kaivokatu 8, 00100 Helsinki", corner)
        )
        // Ilman katuosoitetta pelkkä nimi riittää vain aivan vieressä.
        assertFalse(isSameAddress("a", "R-kioski, 00100 Helsinki", station, "b", "R-kioski, 00120 Helsinki", nearby))
        assertTrue(
            isSameAddress("a", "Yritys A, 90100 Oulu", GeoPoint(65.01236, 25.47158), "b", "Yritys A, Hallituskatu 5, 90100 Oulu", GeoPoint(65.01250, 25.47170))
        )
    }

    @Test
    fun `numeroitu kohteen nimi ei ohita eri katuosoitteita`() {
        // Kaksi samannimistä kohdetta eri taloissa ~220 m päässä: katuosoite ratkaisee, ei nimi.
        val hallituskatu = GeoPoint(65.0146, 25.4692)
        val kirkkokatu = GeoPoint(65.0126, 25.4692)
        assertFalse(
            isSameAddress(
                "pelias:1", "Studio 24, Hallituskatu 5, 90100 Oulu", hallituskatu,
                "pelias:2", "Studio 24, Kirkkokatu 10, 90100 Oulu", kirkkokatu,
            )
        )
        // Kun toisella on katuosoite, pelkkä numeroitu nimi on nimi, joka kelpaa vain aivan vieressä.
        assertFalse(isSameAddress("pelias:2", "Studio 24, Kirkkokatu 10, 90100 Oulu", kirkkokatu, "fi:v", "Studio 24, 90100 Oulu", hallituskatu))
        assertTrue(
            isSameAddress("pelias:1", "Studio 24, Hallituskatu 5, 90100 Oulu", hallituskatu, "fi:v", "Studio 24, 90100 Oulu", GeoPoint(65.01465, 25.46925))
        )
        // Sama nimi eri kadulla ilman talonumeroa; sama kohde talonumerolla ja ilman on sama stoppi.
        assertFalse(isSameAddress("pelias:1", "Studio 24, Hallituskatu, 90100 Oulu", hallituskatu, "pelias:2", "Studio 24, Kirkkokatu, 90100 Oulu", kirkkokatu))
        assertTrue(
            isSameAddress("pelias:1", "Studio 24, Hallituskatu, 90100 Oulu", hallituskatu, "pelias:3", "Studio 24, Hallituskatu 5, 90100 Oulu", GeoPoint(65.01462, 25.46922))
        )
    }

    @Test
    fun `saman katuosoitteen eri paikkakuntatarkennukset ovat sama talo`() {
        // Kolmiosaisen osoitteen toinen osa voi olla kaupunginosa tai kaupunki eikä kohteen katu.
        val point = GeoPoint(65.0123, 25.4715)
        assertTrue(isSameAddress("pelias:1", "Hallituskatu 5, Keskusta, Oulu", point, "pelias:2", "Hallituskatu 5, Oulu, Pohjois-Pohjanmaa", point))
        // Postinumero paikkakunnan edellä tai omana osanaan ei tee paikkakunnasta eri paikkaa.
        assertTrue(isSameAddress("pelias:1", "Hallituskatu 5, Keskusta, Oulu", point, "osm:N:2", "Hallituskatu 5, 90100 Oulu, Suomi", point))
        assertTrue(isSameAddress("pelias:1", "Hallituskatu 5, 90100, Oulu", point, "pelias:2", "Hallituskatu 5, Keskusta, Oulu", point))
        // Samanniminen kohde eri kaduilla erottuu edelleen.
        assertFalse(
            isSameAddress(
                "pelias:1", "Studio 24, Hallituskatu, 90100 Oulu", GeoPoint(65.0146, 25.4692),
                "pelias:2", "Studio 24, Kirkkokatu, 90100 Oulu", GeoPoint(65.0126, 25.4692),
            )
        )
    }

    @Test
    fun `kadun nimen järjestysnumero erottaa samannimisen kohteen kadut`() {
        // Viereisten katujen samannimiset liikkeet ~80 m päässä toisistaan ovat eri stoppeja.
        val third = GeoPoint(60.18300, 24.95200)
        val fourth = GeoPoint(60.18360, 24.95250)
        assertFalse(isSameAddress("osm:N:1", "Kioski, 3. linja, Helsinki", third, "osm:N:2", "Kioski, 4. linja, Helsinki", fourth))
        assertFalse(isSameAddress("osm:N:3", "Kioski, 3. linja, 00530 Helsinki", third, "osm:N:4", "Kioski, 4. linja, 00530 Helsinki", fourth))
    }

    @Test
    fun `postinumero ja kaupunki eivät ole kohteen katuosoite`() {
        // Jos maan nimi jää osoitteen perään, toinen osa on postinumero ja kaupunki eikä katu.
        val point = GeoPoint(65.0146, 25.4692)
        assertFalse(isSameAddress("pelias:1", "Hallituskatu 5, 90100 Oulu, Suomi", point, "pelias:2", "Hallituskatu 9, 90100 Oulu, Suomi", GeoPoint(65.0149, 25.4700)))
        assertTrue(isSameAddress("pelias:1", "Hallituskatu 5, 90100 Oulu, Suomi", point, "pelias:2", "Hallituskatu 5, 90100 Oulu", GeoPoint(65.0147, 25.4693)))
    }

    @Test
    fun `sama teksti kaukana toisistaan ei ole sama stoppi`() {
        // Ketjun kaksi myymälää samalla nimellä ja postinumerolla noin 800 metrin päässä toisistaan.
        assertFalse(isSameAddress("a", "R-kioski, 00100 Helsinki", GeoPoint(60.1699, 24.9384), "b", "R-kioski, 00100 Helsinki", GeoPoint(60.1640, 24.9300)))
    }

    @Test
    fun `naapuritalo tai kaukainen samanniminen katu ei ole tupla`() {
        assertFalse(isSameAddress("a", "Kauppakatu 10, Oulu", oulu, "b", "Kauppakatu 12, Oulu", nextDoor))
        assertFalse(isSameAddress("a", "Kauppakatu 10, Oulu", oulu, "b", "Kauppakatu 10, Kemi", GeoPoint(65.7364, 24.5637)))
    }
}
