package fi.tyomatka.core.eta

import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.core.model.GeoPoint
import fi.tyomatka.core.model.LearnedStats
import fi.tyomatka.core.model.PlannedStop
import kotlin.test.Test
import kotlin.test.assertEquals

class EtaEngineTest {

    private val engine = EtaEngine()

    private fun stop(id: Long, deadline: Long? = null, serviceSec: Long? = 60) = PlannedStop(
        id = id,
        label = "Stoppi $id",
        location = GeoPoint(60.0, 24.0),
        deadlineEpochSec = deadline,
        serviceSec = serviceSec,
    )

    @Test
    fun `laskee etat ketjuna`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 1000,
                liveEtaToNextSec = null,
                remainingStops = listOf(stop(1), stop(2), stop(3)),
                legPredictedSec = listOf(300, 200, 100),
            )
        )
        assertEquals(1300, snapshot.stopEtas[0].arrivalEpochSec)
        assertEquals(1360, snapshot.stopEtas[0].departureEpochSec)
        assertEquals(1560, snapshot.stopEtas[1].arrivalEpochSec)
        assertEquals(1620, snapshot.stopEtas[1].departureEpochSec)
        assertEquals(1720, snapshot.stopEtas[2].arrivalEpochSec)
        assertEquals(1780, snapshot.finishEpochSec)
    }

    @Test
    fun `live-eta ohittaa ensimmaisen legin ennusteen eika saa nopeuskerrointa`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 0,
                liveEtaToNextSec = 500,
                remainingStops = listOf(stop(1), stop(2)),
                legPredictedSec = listOf(300, 200),
                stats = LearnedStats(speedFactor = 2.0),
            )
        )
        // Ensimmäinen legi: live 500 s sellaisenaan. Toinen: 200 × 2.0 = 400 s.
        assertEquals(500, snapshot.stopEtas[0].arrivalEpochSec)
        assertEquals(560 + 400, snapshot.stopEtas[1].arrivalEpochSec)
    }

    @Test
    fun `live-ETA kerrotaan navigoinnin kertoimella ja myohemmat legit suunnitelman kertoimella`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 1000,
                liveEtaToNextSec = 600,
                remainingStops = listOf(stop(1), stop(2)),
                legPredictedSec = listOf(999, 300),
                stats = LearnedStats(speedFactor = 1.5, navSpeedFactor = 1.2),
            )
        )
        // Valhallan aika ei huomioi liikennettä: 600 × 1.2 = 720 s. Toinen legi OSRM:stä: 300 × 1.5 = 450 s.
        assertEquals(1000 + 720L, snapshot.stopEtas[0].arrivalEpochSec)
        assertEquals(1000 + 720 + 60 + 450L, snapshot.stopEtas[1].arrivalEpochSec)
    }

    @Test
    fun `viivastys ensimmaisella legilla siirtaa koko loppupaivaa`() {
        val base = EtaInput(
            nowEpochSec = 0,
            liveEtaToNextSec = 300,
            remainingStops = listOf(stop(1), stop(2), stop(3)),
            legPredictedSec = listOf(300, 200, 100),
        )
        val normal = engine.compute(base)
        val delayed = engine.compute(base.copy(liveEtaToNextSec = 900))
        assertEquals(600, delayed.finishEpochSec - normal.finishEpochSec)
    }

    @Test
    fun `stopilla oltaessa lahto lasketaan saapumisesta mutta ei menneisyyteen`() {
        // Saavuttu hetkellä 100, kesto 60 s → lähtö olisi 160, mutta kello on jo 400.
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 400,
                liveEtaToNextSec = null,
                remainingStops = listOf(stop(1), stop(2)),
                legPredictedSec = listOf(0, 200),
                atStopSinceEpochSec = 100,
            )
        )
        assertEquals(100, snapshot.stopEtas[0].arrivalEpochSec)
        assertEquals(400, snapshot.stopEtas[0].departureEpochSec)
        assertEquals(600, snapshot.stopEtas[1].arrivalEpochSec)
    }

    @Test
    fun `aikarajastatukset lasketaan pelivarasta`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 0,
                liveEtaToNextSec = null,
                remainingStops = listOf(
                    stop(1, deadline = 2000),          // saapuu 300 → OK
                    stop(2, deadline = 660),           // saapuu 560 → pelivara 100 s → TIGHT
                    stop(3, deadline = 700),           // saapuu 820 → LATE
                    stop(4),                           // ei aikarajaa
                ),
                legPredictedSec = listOf(300, 200, 200, 100),
            )
        )
        assertEquals(DeadlineStatus.OK, snapshot.stopEtas[0].deadlineStatus)
        assertEquals(DeadlineStatus.TIGHT, snapshot.stopEtas[1].deadlineStatus)
        assertEquals(DeadlineStatus.LATE, snapshot.stopEtas[2].deadlineStatus)
        assertEquals(DeadlineStatus.NONE, snapshot.stopEtas[3].deadlineStatus)
    }

    @Test
    fun `opittua stoppikestoa kaytetaan kun stopilla ei ole omaa arviota`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 0,
                liveEtaToNextSec = null,
                remainingStops = listOf(stop(1, serviceSec = null), stop(2, serviceSec = null)),
                legPredictedSec = listOf(100, 100),
                stats = LearnedStats(avgServiceSec = 240),
            )
        )
        assertEquals(100, snapshot.stopEtas[0].arrivalEpochSec)
        assertEquals(340, snapshot.stopEtas[0].departureEpochSec)
        assertEquals(440, snapshot.stopEtas[1].arrivalEpochSec)
    }

    @Test
    fun `tyhja lista palauttaa valmiin paivan`() {
        val snapshot = engine.compute(
            EtaInput(
                nowEpochSec = 123,
                liveEtaToNextSec = null,
                remainingStops = emptyList(),
                legPredictedSec = emptyList(),
            )
        )
        assertEquals(123, snapshot.finishEpochSec)
    }
}
