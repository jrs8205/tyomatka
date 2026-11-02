package fi.tyomatka.core.poi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuelTypeTest {
    @Test
    fun `lajit tunnistetaan avaimesta`() {
        assertEquals(FuelType.HVO, FuelType.of("hvo"))
        assertEquals(FuelType.MPO, FuelType.of("mpo"))
        assertNull(FuelType.of("kerosiini"))
        assertTrue(FuelType.BIOGAS.isGas)
        assertFalse(FuelType.DIESEL.isGas)
    }

    @Test
    fun `kertatuonnin lista tuotenimineen`() {
        assertEquals(
            listOf(FuelOffer(FuelType.DIESEL, "Neste Futura Diesel -10/-20"), FuelOffer(FuelType.HVO, "Neste MY")),
            parseFuels("diesel=Neste Futura Diesel -10/-20;hvo=Neste MY"),
        )
    }

    @Test
    fun `ilmoituksen lista ilman tuotenimiä`() {
        assertEquals(listOf(FuelOffer(FuelType.DIESEL, null), FuelOffer(FuelType.HVO, null)), parseFuels("diesel;hvo"))
    }

    @Test
    fun `none on tyhjä ja tuntematon laji ohitetaan`() {
        assertEquals(emptyList(), parseFuels("none"))
        assertEquals(listOf(FuelOffer(FuelType.E85, null)), parseFuels("kerosiini;e85; ;e85=Toinen"))
        assertEquals(emptyList(), parseFuels(""))
    }
}
