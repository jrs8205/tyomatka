package fi.tyomatka.data.repo

import fi.tyomatka.data.db.StopEntity
import fi.tyomatka.data.db.StopStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceStopInsertionTest {
    private fun stop(id: Long, orderIndex: Int, status: StopStatus) =
        StopEntity(id = id, routeDayId = 1, label = "S$id", address = "A$id", lat = 60.0, lng = 24.0, orderIndex = orderIndex, status = status)

    @Test
    fun `seuraavaksi menee ensimmäisen tulevan paikalle, siirtää sen ja seuraavat ja palauttaa aktiivisen odottamaan`() {
        val stops = listOf(stop(1, 0, StopStatus.DONE), stop(2, 1, StopStatus.ACTIVE), stop(3, 2, StopStatus.PENDING))

        assertEquals(ServiceStopInsertion(orderIndex = 1, shiftUp = listOf(3L, 2L), demoteActive = 2L), serviceStopInsertion(stops, ServiceStopPlacement.NEXT))
    }

    @Test
    fun `stopilla ollessa seuraavaksi tarkoittaa heti lähdön jälkeen`() {
        val stops = listOf(stop(1, 0, StopStatus.ARRIVED), stop(2, 1, StopStatus.PENDING))

        assertEquals(ServiceStopInsertion(orderIndex = 1, shiftUp = listOf(2L), demoteActive = null), serviceStopInsertion(stops, ServiceStopPlacement.NEXT))
    }

    @Test
    fun `ilman tulevia stoppeja seuraavaksi on sama kuin loppuun`() {
        assertEquals(ServiceStopInsertion(1, emptyList(), null), serviceStopInsertion(listOf(stop(1, 0, StopStatus.ARRIVED)), ServiceStopPlacement.NEXT))
        assertEquals(ServiceStopInsertion(0, emptyList(), null), serviceStopInsertion(emptyList(), ServiceStopPlacement.NEXT))
    }

    @Test
    fun `loppuun ottaa seuraavan vapaan järjestysnumeron ilman siirtoja`() {
        val stops = listOf(stop(1, 0, StopStatus.DONE), stop(2, 3, StopStatus.ACTIVE), stop(3, 5, StopStatus.PENDING))

        assertEquals(ServiceStopInsertion(6, emptyList(), null), serviceStopInsertion(stops, ServiceStopPlacement.END))
    }

    @Test
    fun `järjestys luetaan järjestysnumerosta, ei listan järjestyksestä`() {
        val stops = listOf(stop(3, 2, StopStatus.PENDING), stop(2, 1, StopStatus.PENDING), stop(1, 0, StopStatus.DONE))

        assertEquals(ServiceStopInsertion(1, listOf(3L, 2L), null), serviceStopInsertion(stops, ServiceStopPlacement.NEXT))
    }
}
