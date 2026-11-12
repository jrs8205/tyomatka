package fi.tyomatka.data.repo

import fi.tyomatka.core.model.GeoPoint
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopMatchingTest {
    private val goteborg = GeoPoint(57.70318, 11.95901)

    @Test
    fun `sama tunniste tai sama osoiteteksti on sama osoite`() {
        assertTrue(isSameAddress("osm:N:1", "Kungsgatan 10A, 411 19 Göteborg", goteborg, "osm:N:1", "muu teksti", GeoPoint(0.0, 0.0)))
        assertTrue(isSameAddress("osm:N:1", "Kungsgatan 10A, 411 19 Göteborg", goteborg, null, " kungsgatan 10a,  411 19 göteborg", goteborg))
    }

    @Test
    fun `eri palveluiden tunnisteet eivät estä tuplan tunnistusta`() {
        // Geoapify ja Photon antavat samalle osoitteelle eri tunnisteen ja hieman eri pisteen.
        assertTrue(
            isSameAddress(
                "geoapify:51d5", "Kungsgatan 10a, 411 19 Göteborg", goteborg,
                "osm:N:2114939955", "Kungsgatan 10A, 404 21 Göteborg", GeoPoint(57.70320, 11.95928),
            )
        )
    }

    @Test
    fun `saman talon eri porras, asunto tai kirjoitusasu on sama stoppi`() {
        // samaan taloon riittää yksi stoppi, vaikka siellä olisi useampi yritys tai asunto.
        val oulu = GeoPoint(65.01236, 25.47158)
        val otherEnd = GeoPoint(65.01290, 25.47230) // pitkän talon toinen porras ~70 m päässä
        assertTrue(isSameAddress("a", "Kauppakatu 5 A 3, 90100 Oulu", oulu, "b", "Kauppakatu 5 B 12, 90100 Oulu", otherEnd))
        assertTrue(isSameAddress("a", "Kauppakatu 5 A, Oulu", oulu, "b", "Kauppakatu 5, 90100 Oulu", oulu))
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "kauppakatu 5A, 90100 Oulu", oulu))
        assertTrue(isSameAddress("a", "Kungsgatan 10 lgh 1102, Göteborg", oulu, "b", "Kungsgatan 10, 411 19 Göteborg", oulu))
        // Geoapify kirjoittaa talonumeron ensin, Photon kadun perään.
        val paris = GeoPoint(48.85575, 2.35906)
        assertTrue(
            isSameAddress("geoapify:1", "10 Rue De Rivoli, 75004 Pariisi", paris, "osm:W:1", "Rue de Rivoli 10, 75004 Paris", GeoPoint(48.85580, 2.35920))
        )
    }

    @Test
    fun `talossa oleva kohde on sama stoppi kuin talon osoite`() {
        // Geoapifyn kohteessa nimi on ensin ja katuosoite toisena.
        assertTrue(
            isSameAddress(
                "geoapify:g", "Galleria 5, Hallituskatu 5, 90100 Oulu", GeoPoint(65.01236, 25.47158),
                "fi:1", "Hallituskatu 5, 90100 Oulu", GeoPoint(65.01250, 25.47170),
            )
        )
    }

    @Test
    fun `talonumeron kirjain ei erota stoppeja`() {
        // Numeroon kiinni kirjoitettu kirjain on sama asia kuin erillinen porras: samaan talonumeroon riittää yksi stoppi.
        val oulu = GeoPoint(65.01236, 25.47158)
        val nextDoor = GeoPoint(65.01250, 25.47200)
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 5b, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5, Oulu", oulu, "b", "Kauppakatu 5a, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 5 A, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5b, Oulu", oulu, "b", "Kauppakatu 5 B 12, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Kauppakatu 5a 3, Oulu", oulu, "b", "Kauppakatu 5 B, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Studio, Kauppakatu 5a, 90100 Oulu", oulu, "b", "Kauppakatu 5b, Oulu", nextDoor))
    }

    @Test
    fun `eri talonumero on eri talo, vaikka kirjain olisi sama`() {
        val oulu = GeoPoint(65.01236, 25.47158)
        val nextDoor = GeoPoint(65.01250, 25.47200)
        assertFalse(isSameAddress("a", "Kauppakatu 5a, Oulu", oulu, "b", "Kauppakatu 7a, Oulu", nextDoor))
        assertFalse(isSameAddress("a", "Kauppakatu 5 A 3, Oulu", oulu, "b", "Kauppakatu 3 A 5, Oulu", nextDoor))
    }

    @Test
    fun `kirjaimella erottuvat kohteen nimet eri kaduilla ovat eri stopit`() {
        val oulu = GeoPoint(65.01236, 25.47158)
        val nextDoor = GeoPoint(65.01250, 25.47200)
        assertFalse(isSameAddress("a", "Studio 24a, Hallituskatu, Oulu", oulu, "b", "Studio 24b, Kirkkokatu, Oulu", nextDoor))
        assertTrue(isSameAddress("a", "Studio 24a, Hallituskatu, Oulu", oulu, "b", "Studio 24b, Hallituskatu, Oulu", nextDoor))
    }

    @Test
    fun `kadun nimen lopussa oleva numero ei ole talonumero`() {
        val oulu = GeoPoint(65.01236, 25.47158)
        val nextDoor = GeoPoint(65.01250, 25.47200)
        assertFalse(isSameAddress("a", "Valtatie 4 12, Oulu", oulu, "b", "Valtatie 4 14, Oulu", nextDoor))
        assertFalse(isSameAddress("a", "Avenue du 8 Mai 1945 10, Sevran", oulu, "b", "Avenue du 8 Mai 1945 12, Sevran", nextDoor))
        // Geoapify kirjoittaa numeron ensin, Photon perään.
        assertTrue(isSameAddress("a", "10 Avenue du 8 Mai 1945, Sevran", oulu, "b", "Avenue du 8 Mai 1945 10, Sevran", nextDoor))
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
                "geoapify:1", "Studio 24, Hallituskatu 5, 90100 Oulu", hallituskatu,
                "geoapify:2", "Studio 24, Kirkkokatu 10, 90100 Oulu", kirkkokatu,
            )
        )
        // Kun toisella on katuosoite, pelkkä numeroitu nimi on nimi, joka kelpaa vain aivan vieressä.
        assertFalse(isSameAddress("geoapify:2", "Studio 24, Kirkkokatu 10, 90100 Oulu", kirkkokatu, "fi:v", "Studio 24, 90100 Oulu", hallituskatu))
        assertTrue(
            isSameAddress("geoapify:1", "Studio 24, Hallituskatu 5, 90100 Oulu", hallituskatu, "fi:v", "Studio 24, 90100 Oulu", GeoPoint(65.01465, 25.46925))
        )
        // Sama nimi eri kadulla ilman talonumeroa; sama kohde talonumerolla ja ilman on sama stoppi.
        assertFalse(isSameAddress("geoapify:1", "Studio 24, Hallituskatu, 90100 Oulu", hallituskatu, "geoapify:2", "Studio 24, Kirkkokatu, 90100 Oulu", kirkkokatu))
        assertTrue(
            isSameAddress("geoapify:1", "Studio 24, Hallituskatu, 90100 Oulu", hallituskatu, "geoapify:3", "Studio 24, Hallituskatu 5, 90100 Oulu", GeoPoint(65.01462, 25.46922))
        )
    }

    @Test
    fun `kohteen katuosoite voi alkaa talonumerolla`() {
        // Ranskassa, Britanniassa ja Yhdysvalloissa talonumero on kadun edellä myös kohteen katuosoitteessa.
        val rivoli = GeoPoint(48.8557, 2.3591)
        val saintAntoine = GeoPoint(48.8538, 2.3610) // ~250 m
        assertFalse(
            isSameAddress(
                "geoapify:1", "Studio 24, 10 Rue de Rivoli, 75004 Pariisi", rivoli,
                "geoapify:2", "Studio 24, 2 Rue Saint-Antoine, 75004 Pariisi", saintAntoine,
            )
        )
        assertFalse(
            isSameAddress(
                "geoapify:1", "Studio 24, 10 Downing Street, London, SW1A 2AA", GeoPoint(51.5034, -0.1276),
                "geoapify:2", "Studio 24, 70 Whitehall, London, SW1A 2AS", GeoPoint(51.5045, -0.1262),
            )
        )
        // Talossa oleva kohde on sama stoppi kuin talon osoite.
        assertTrue(isSameAddress("geoapify:c", "Café X, 10 Rue de Rivoli, 75004 Pariisi", rivoli, "osm:1", "10 Rue de Rivoli, 75004 Pariisi", GeoPoint(48.8558, 2.3592)))
        // Numerolla alkava ensimmäinen osa on talon oma osoite: toinen osa on paikkakunta, ei kohteen katu.
        val brooklyn = GeoPoint(40.6930, -73.9900)
        assertTrue(isSameAddress("geoapify:1", "100 Main Street, Brooklyn, NY 11201", brooklyn, "geoapify:2", "100 Main Street, New York, NY 11201", GeoPoint(40.69307, -73.99008)))
        assertTrue(
            isSameAddress("geoapify:1", "10 Downing Street, London, SW1A 2AA", GeoPoint(51.5034, -0.1276), "geoapify:2", "10 Downing Street, Westminster, London, SW1A 2AA", GeoPoint(51.5034, -0.1276))
        )
        val whiteHouse = GeoPoint(38.8977, -77.0365)
        assertTrue(
            isSameAddress(
                "geoapify:w", "White House, 1600 Pennsylvania Avenue NW, Washington, DC 20500", whiteHouse,
                "geoapify:a", "1600 Pennsylvania Avenue NW, Washington, DC 20500", whiteHouse,
            )
        )
    }

    @Test
    fun `saman katuosoitteen eri paikkakuntatarkennukset ovat sama talo`() {
        // Kolmiosaisen osoitteen toinen osa voi olla kaupunginosa tai kaupunki eikä kohteen katu.
        val point = GeoPoint(57.7056, 11.9675)
        assertTrue(
            isSameAddress(
                "geoapify:1", "Kungsgatan 10, Inom Vallgraven, Göteborg", point,
                "geoapify:2", "Kungsgatan 10, Göteborg, Västra Götaland", point,
            )
        )
        // Postinumero paikkakunnan edellä tai omana osanaan ei tee paikkakunnasta eri paikkaa.
        assertTrue(
            isSameAddress(
                "geoapify:1", "Kungsgatan 10, Inom Vallgraven, Göteborg", point,
                "osm:N:1", "Kungsgatan 10, 411 19 Göteborg, Västra Götaland", point,
            )
        )
        assertTrue(
            isSameAddress(
                "geoapify:1", "Kungsgatan 10, 411 19, Göteborg", point,
                "geoapify:2", "Kungsgatan 10, Inom Vallgraven, Göteborg", point,
            )
        )
        val oulu = GeoPoint(65.0123, 25.4715)
        assertTrue(isSameAddress("geoapify:1", "Hallituskatu 5, Keskusta, Oulu", oulu, "osm:N:2", "Hallituskatu 5, 90100 Oulu, Suomi", oulu))
        // Postinumero paikkakunnan perässä.
        assertTrue(isSameAddress("geoapify:1", "Kungsgatan 10, Göteborg 411 19, Sverige", point, "geoapify:2", "Kungsgatan 10, Inom Vallgraven, Göteborg", point))
        // Samanniminen kohde eri kaduilla erottuu edelleen.
        assertFalse(
            isSameAddress(
                "geoapify:1", "Studio 24, Hallituskatu, 90100 Oulu", GeoPoint(65.0146, 25.4692),
                "geoapify:2", "Studio 24, Kirkkokatu, 90100 Oulu", GeoPoint(65.0126, 25.4692),
            )
        )
    }

    @Test
    fun `kadun nimen järjestysnumero erottaa samannimisen kohteen kadut`() {
        // Postinumero ei erota paikkakuntaa, mutta kadun nimen numero erottaa kadut: viereisten katujen liikkeet ~80 m
        // päässä toisistaan ovat eri stoppeja.
        val east23rd = GeoPoint(40.74200, -73.98900)
        val east24th = GeoPoint(40.74260, -73.98850)
        assertFalse(isSameAddress("osm:N:1", "7-Eleven, East 23rd Street, New York", east23rd, "osm:N:2", "7-Eleven, East 24th Street, New York", east24th))
        assertFalse(isSameAddress("osm:N:3", "7-Eleven, 5th Avenue, New York", east23rd, "osm:N:4", "7-Eleven, 6th Avenue, New York", east24th))
        // Pelkkä numerosana kadun nimen keskellä erottaa myös.
        assertFalse(isSameAddress("geoapify:1", "Starbucks, Calle 23 Norte, Bogotá", east23rd, "geoapify:2", "Starbucks, Calle 24 Norte, Bogotá", east24th))
        assertFalse(isSameAddress("geoapify:3", "Kiosk, Route 66 East, Springfield", east23rd, "geoapify:4", "Kiosk, Route 67 East, Springfield", east24th))
    }

    @Test
    fun `väliviivallinen postinumero ei erota paikkakuntaa`() {
        val point = GeoPoint(52.2297, 21.0122)
        assertTrue(isSameAddress("geoapify:1", "Café X, 00-950 Warszawa, Polska", point, "geoapify:2", "Café X, Śródmieście, Warszawa", point))
    }

    @Test
    fun `postinumero ja kaupunki eivät ole kohteen katuosoite`() {
        // Jos maan nimi jää osoitteen perään, toinen osa on postinumero ja kaupunki eikä katu.
        val damrak1 = GeoPoint(52.3770, 4.8980)
        val damrak5 = GeoPoint(52.3765, 4.8985) // ~65 m
        assertFalse(isSameAddress("geoapify:1", "Damrak 1, 1012 LG Amsterdam, Nederland", damrak1, "geoapify:5", "Damrak 5, 1012 LG Amsterdam, Nederland", damrak5))
        val oulu = GeoPoint(65.0146, 25.4692)
        assertFalse(isSameAddress("geoapify:1", "Hallituskatu 5, 90100 Oulu, Suomi", oulu, "geoapify:2", "Hallituskatu 9, 90100 Oulu, Suomi", GeoPoint(65.0149, 25.4700)))
    }

    @Test
    fun `sama teksti kaukana toisistaan ei ole sama stoppi`() {
        // Ketjun kaksi myymälää samalla nimellä ja postinumerolla noin 800 metrin päässä toisistaan.
        assertFalse(isSameAddress("a", "R-kioski, 00100 Helsinki", GeoPoint(60.1699, 24.9384), "b", "R-kioski, 00100 Helsinki", GeoPoint(60.1640, 24.9300)))
    }

    @Test
    fun `naapuritalo tai kaukainen samanniminen katu ei ole tupla`() {
        assertFalse(isSameAddress("a", "Kungsgatan 10, Göteborg", goteborg, "b", "Kungsgatan 12, Göteborg", GeoPoint(57.70330, 11.95990)))
        assertFalse(isSameAddress("a", "Kungsgatan 10, Göteborg", goteborg, "b", "Kungsgatan 10, Borås", GeoPoint(57.72, 12.94)))
    }
}
