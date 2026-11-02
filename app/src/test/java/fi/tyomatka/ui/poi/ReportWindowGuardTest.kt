package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.PoiBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportWindowGuardTest {
    private val a = PoiBox(24.75, 60.25, 25.0, 60.5)
    private val b = PoiBox(25.0, 60.25, 25.25, 60.5)

    @Test
    fun `vanhan ikkunan hidas vastaus hylätään, kun kartta on siirtynyt`() {
        val guard = ReportWindowGuard()
        guard.moveTo(a)
        val cardFetch = guard.begin()!!
        guard.moveTo(b)
        val windowFetch = guard.begin()!!

        assertTrue(guard.accept(windowFetch, version = 10))
        assertFalse(guard.accept(cardFetch, version = 20))
        assertEquals(b, guard.box)
    }

    @Test
    fun `näkyvää vanhempi sisältö hylätään saapumisjärjestyksestä riippumatta`() {
        val guard = ReportWindowGuard()
        guard.moveTo(a)
        val first = guard.begin()!!
        val second = guard.begin()!!

        assertTrue(guard.accept(second, version = 20))
        assertFalse(guard.accept(first, version = 10))
        // Sama sisältö (sama välimuistirivi) kelpaa uudelleen.
        assertTrue(guard.accept(guard.begin()!!, version = 20))
    }

    @Test
    fun `paluu samaan ikkunaan ei hyväksy ennen vaihtoa aloitettua hakua`() {
        val guard = ReportWindowGuard()
        guard.moveTo(a)
        val stale = guard.begin()!!
        guard.moveTo(b)
        guard.moveTo(a)
        assertFalse(guard.accept(stale, version = 30))
        assertTrue(guard.accept(guard.begin()!!, version = 10))
    }

    @Test
    fun `ilman ikkunaa hakua ei aloiteta`() {
        val guard = ReportWindowGuard()
        assertNull(guard.begin())
        guard.moveTo(a)
        val fetch = guard.begin()!!
        guard.moveTo(null)
        assertNull(guard.begin())
        assertFalse(guard.accept(fetch, version = 10))
    }

    @Test
    fun `uudempi epäonnistunut haku ei estä vanhemman onnistuneen vastauksen käyttöä`() {
        val guard = ReportWindowGuard()
        guard.moveTo(a)
        val window = guard.begin()!!
        val card = guard.begin()!!
        // Korttihaku sai 503:n, eikä välimuistia ole: tyhjä kelpaa uuteen ikkunaan, kunnes sisältöä tulee.
        assertTrue(guard.accept(card, version = null))
        assertTrue(guard.accept(window, version = 15))
        // Sisällötön tulos ei pyyhi jo näkyvää.
        assertFalse(guard.accept(guard.begin()!!, version = null))
    }

    @Test
    fun `toisen näkymän hakema uudempi välimuistitieto kelpaa, vanhempi ei`() {
        // Kartta sai verkosta vastauksen versiona 10; ajonäkymä haki samaan ikkunaan myöhemmin (versio 20), ja Kartan
        // seuraava haku epäonnistui ja palautti yhteisestä välimuistista ajonäkymän uudemman vastauksen.
        val map = ReportWindowGuard()
        map.moveTo(a)
        assertTrue(map.accept(map.begin()!!, version = 10))
        assertTrue(map.accept(map.begin()!!, version = 20))
        assertFalse(map.accept(map.begin()!!, version = 15))
    }
}
