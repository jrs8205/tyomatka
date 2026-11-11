package fi.tyomatka.ui.plan

import androidx.compose.material3.lightColorScheme
import fi.tyomatka.core.model.DeadlineStatus
import fi.tyomatka.data.db.StopStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Suunnittelun aikajana: pallon sisältö, saapumisajan sävy ja himmennetty viiva. */
class StopTimelineTest {

    @Test
    fun `jaljella olevalla stopilla on numero`() {
        assertEquals(StopNode.Number(3), stopNode(3, StopStatus.PENDING))
        assertEquals(StopNode.Number(1), stopNode(1, StopStatus.ACTIVE))
        assertEquals(StopNode.Number(2), stopNode(2, StopStatus.ARRIVED))
    }

    @Test
    fun `kayty stoppi saa merkin`() {
        assertEquals(StopNode.Done, stopNode(null, StopStatus.DONE))
    }

    @Test
    fun `ohitettu tai numeroton stoppi saa viivan`() {
        assertEquals(StopNode.Skipped, stopNode(null, StopStatus.SKIPPED))
        assertEquals(StopNode.Skipped, stopNode(null, StopStatus.PENDING))
    }

    @Test
    fun `saapumisajan savy aikarajan mukaan`() {
        assertEquals(EtaTone.LATE, etaTone(DeadlineStatus.LATE))
        assertEquals(EtaTone.TIGHT, etaTone(DeadlineStatus.TIGHT))
        assertEquals(EtaTone.NORMAL, etaTone(DeadlineStatus.OK))
        assertEquals(EtaTone.NORMAL, etaTone(DeadlineStatus.NONE))
        assertEquals(EtaTone.NORMAL, etaTone(null))
    }

    @Test
    fun `saapumisaika on nimen vieressa vain jos sanat mahtuvat kokonaisina`() {
        assertEquals(true, etaBesideNames(widestWordPx = 200, availablePx = 600, etaPx = 120, gapPx = 30))
        assertEquals(true, etaBesideNames(widestWordPx = 450, availablePx = 600, etaPx = 120, gapPx = 30))
        assertEquals(false, etaBesideNames(widestWordPx = 451, availablePx = 600, etaPx = 120, gapPx = 30))
    }

    @Test
    fun `huoltoaseman merkki mitataan nimen ja osoitteen kanssa`() {
        assertEquals(
            listOf("Neste Kamppi" to NamePart.NAME, "Urho Kekkosen katu 1" to NamePart.ADDRESS, "Huoltoasema" to NamePart.BADGE),
            nameParts("Neste Kamppi", "Urho Kekkosen katu 1", serviceBadge = "Huoltoasema"),
        )
        assertEquals(listOf("Kotikatu 1" to NamePart.NAME, "Kotikatu 1" to NamePart.ADDRESS), nameParts("Kotikatu 1", "Kotikatu 1", null))
        assertEquals(listOf("Kotikatu 1" to NamePart.NAME), nameParts("Kotikatu 1", " ", null))
    }

    @Test
    fun `asettamattoman chipin ruudunlukija lukee pitkan tekstin`() {
        assertEquals("Aseta aikaraja", chipDescription(set = false, long = "Aseta aikaraja"))
        assertEquals(null, chipDescription(set = true, long = "Aseta aikaraja"))
    }

    @Test
    fun `saapumisajan varit`() {
        val scheme = lightColorScheme()
        assertEquals(scheme.error, etaColor(EtaTone.LATE, scheme))
        assertEquals(scheme.tertiary, etaColor(EtaTone.TIGHT, scheme))
        assertEquals(scheme.onSurface, etaColor(EtaTone.NORMAL, scheme))
    }

    @Test
    fun `kaydyn ja ohitetun stopin viiva on himmea`() {
        assertEquals(true, railMuted(StopStatus.DONE))
        assertEquals(true, railMuted(StopStatus.SKIPPED))
        assertEquals(false, railMuted(StopStatus.PENDING))
        assertEquals(false, railMuted(StopStatus.ACTIVE))
        assertEquals(false, railMuted(StopStatus.ARRIVED))
    }
}
