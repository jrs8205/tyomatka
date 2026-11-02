package fi.tyomatka.core.learn

import fi.tyomatka.core.model.LearnedStats
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryLearnerTest {

    private val learner = HistoryLearner()

    @Test
    fun `ensimmainen legi asettaa kertoimen suoraan`() {
        val stats = learner.onLegCompleted(LearnedStats(), predictedSec = 600, actualSec = 720)
        assertEquals(1.2, stats.speedFactor, 0.001)
        assertEquals(1, stats.legSamples)
    }

    @Test
    fun `navigoinnin legi opettaa vain navigoinnin kertoimen`() {
        val start = LearnedStats(speedFactor = 1.5, legSamples = 4)
        val stats = learner.onNavLegCompleted(start, predictedSec = 600, actualSec = 720)
        assertEquals(1.2, stats.navSpeedFactor, 0.001)
        assertEquals(1, stats.navLegSamples)
        assertEquals(1.5, stats.speedFactor, 0.0)
        assertEquals(4, stats.legSamples)
    }

    @Test
    fun `suunnitelman legi ei koske navigoinnin kerrointa`() {
        val start = LearnedStats(navSpeedFactor = 1.3, navLegSamples = 2)
        val stats = learner.onLegCompleted(start, predictedSec = 500, actualSec = 720)
        assertEquals(1.44, stats.speedFactor, 0.001)
        assertEquals(1.3, stats.navSpeedFactor, 0.0)
        assertEquals(2, stats.navLegSamples)
    }

    @Test
    fun `navigoinnin kerroin konvergoi samalla tavalla`() {
        var stats = LearnedStats()
        repeat(20) { stats = learner.onNavLegCompleted(stats, predictedSec = 600, actualSec = 780) }
        assertTrue(abs(stats.navSpeedFactor - 1.3) < 0.01)
    }

    @Test
    fun `nopeuskerroin konvergoi todelliseen suhteeseen`() {
        var stats = LearnedStats()
        // Kuljettaja ajaa toistuvasti 30 % ennustetta hitaammin.
        repeat(30) {
            stats = learner.onLegCompleted(stats, predictedSec = 600, actualSec = 780)
        }
        assertTrue(abs(stats.speedFactor - 1.3) < 0.05, "Kerroin ${stats.speedFactor}")
    }

    @Test
    fun `yksittainen poikkeama ei heilauta kerrointa kohtuuttomasti`() {
        var stats = LearnedStats()
        repeat(10) { stats = learner.onLegCompleted(stats, 600, 600) }
        stats = learner.onLegCompleted(stats, 600, 6000) // yksi katastrofilegi
        assertTrue(stats.speedFactor < 1.5, "Kerroin ${stats.speedFactor} hyppäsi liikaa")
    }

    @Test
    fun `liian lyhyet legit ohitetaan`() {
        val stats = learner.onLegCompleted(LearnedStats(), predictedSec = 5, actualSec = 25)
        assertEquals(1.0, stats.speedFactor, 0.001)
        assertEquals(0, stats.legSamples)
    }

    @Test
    fun `stoppikesto konvergoi keskiarvoon`() {
        var stats = LearnedStats()
        repeat(30) { stats = learner.onStopServiced(stats, 300) }
        assertTrue(abs(stats.avgServiceSec - 300) < 15, "Kesto ${stats.avgServiceSec}")
        assertEquals(30, stats.serviceSamples)
    }

    @Test
    fun `ensimmainen stoppikesto asettaa keskiarvon suoraan`() {
        val stats = learner.onStopServiced(LearnedStats(), 420)
        assertEquals(420, stats.avgServiceSec)
    }

    @Test
    fun `kohtuuttomat kestot leikataan`() {
        val stats = learner.onStopServiced(LearnedStats(), 10 * 3600)
        assertTrue(stats.avgServiceSec <= 2 * 3600)
    }
}
