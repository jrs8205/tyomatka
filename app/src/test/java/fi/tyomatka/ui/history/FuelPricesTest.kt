package fi.tyomatka.ui.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelPricesTest {

    @Test
    fun `desimaali pilkulla tai pisteella`() {
        assertEquals(1.812, parseDecimal("1,812"))
        assertEquals(1.812, parseDecimal(" 1.812 "))
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("1,8,1"))
    }

    @Test
    fun `desimaaliteksti kayttaa pilkkua`() {
        assertEquals("98,45", decimalText(98.449, 2))
        assertEquals("1,812", decimalText(1.8123, 3))
    }

    @Test
    fun `syote rajataan numeroihin ja yhteen erottimeen`() {
        assertEquals("12,345", filterDecimalInput("12.3.4,5"))
        assertEquals("", filterDecimalInput("abc"))
        assertEquals("54,3", filterDecimalInput("54,3"))
    }

    @Test
    fun `summa ja litrat antavat litrahinnan`() {
        val out = resolveFuelPrices(FuelField.TOTAL, FuelPrices(liters = "54,32", total = "98,45", perLiter = ""))
        assertEquals("1,812", out.perLiter)
        assertEquals("98,45", out.total)
    }

    @Test
    fun `litrahinta ja litrat antavat summan`() {
        val out = resolveFuelPrices(FuelField.PER_LITER, FuelPrices(liters = "54,32", total = "", perLiter = "1,812"))
        assertEquals("98,43", out.total)
    }

    @Test
    fun `litrojen muutos laskee litrahinnan summasta`() {
        val out = resolveFuelPrices(FuelField.LITERS, FuelPrices(liters = "50", total = "90", perLiter = "1,700"))
        assertEquals("1,800", out.perLiter)
        assertEquals("90", out.total)
    }

    @Test
    fun `litrojen muutos laskee summan litrahinnasta kun summaa ei ole`() {
        val out = resolveFuelPrices(FuelField.LITERS, FuelPrices(liters = "50", total = "", perLiter = "1,8"))
        assertEquals("90,00", out.total)
    }

    @Test
    fun `litrahinta ensin ja litrat merkki kerrallaan pitaa litrahinnan`() {
        var prices = FuelPrices(liters = "", total = "", perLiter = "")
        var anchor: FuelField? = null
        fun type(field: FuelField, text: String) {
            if (field != FuelField.LITERS) anchor = field
            prices = when (field) {
                FuelField.LITERS -> prices.copy(liters = text)
                FuelField.TOTAL -> prices.copy(total = text)
                FuelField.PER_LITER -> prices.copy(perLiter = text)
            }
            prices = resolveFuelPrices(field, prices, anchor)
        }
        type(FuelField.PER_LITER, "1,812")
        type(FuelField.LITERS, "5")
        assertEquals("9,06", prices.total)
        type(FuelField.LITERS, "54")
        assertEquals("1,812", prices.perLiter)
        assertEquals("97,85", prices.total)
        type(FuelField.LITERS, "54,3")
        assertEquals("1,812", prices.perLiter)
        assertEquals("98,39", prices.total)
    }

    @Test
    fun `summa ensin ja litrat merkki kerrallaan pitaa summan`() {
        var prices = FuelPrices(liters = "", total = "98,45", perLiter = "")
        prices = resolveFuelPrices(FuelField.LITERS, prices.copy(liters = "5"), FuelField.TOTAL)
        assertEquals("19,690", prices.perLiter)
        prices = resolveFuelPrices(FuelField.LITERS, prices.copy(liters = "54,32"), FuelField.TOTAL)
        assertEquals("98,45", prices.total)
        assertEquals("1,812", prices.perLiter)
    }

    @Test
    fun `tyhja tai nolla syote ei laske`() {
        val empty = FuelPrices(liters = "", total = "98,45", perLiter = "")
        assertEquals(empty, resolveFuelPrices(FuelField.TOTAL, empty))
        val zero = FuelPrices(liters = "0", total = "98,45", perLiter = "")
        assertEquals(zero, resolveFuelPrices(FuelField.TOTAL, zero))
        val noTotal = FuelPrices(liters = "50", total = "", perLiter = "")
        assertEquals(noTotal, resolveFuelPrices(FuelField.LITERS, noTotal))
    }

    @Test
    fun `hinnan tyhjennys poistaa toisen hinnan vain jos se oli laskettu`() {
        val prices = FuelPrices("50", "90", "1,800")
        // Ilman ankkuria tai summa-ankkurilla €/l oli laskettu summasta → tyhjenee.
        assertEquals(FuelPrices("50", "", ""), resolveFuelPrices(FuelField.TOTAL, prices.copy(total = "")))
        assertEquals(FuelPrices("50", "", ""), resolveFuelPrices(FuelField.TOTAL, prices.copy(total = ""), FuelField.TOTAL))
        assertEquals(FuelPrices("50", "", ""), resolveFuelPrices(FuelField.PER_LITER, prices.copy(perLiter = "")))
        // Käyttäjä kirjoitti €/l:n itse (ankkuri): summan tyhjennys ei saa hukata sitä.
        assertEquals(
            FuelPrices("50", "", "1,800"),
            resolveFuelPrices(FuelField.TOTAL, prices.copy(total = ""), FuelField.PER_LITER),
        )
        assertEquals(
            FuelPrices("50", "90", ""),
            resolveFuelPrices(FuelField.PER_LITER, prices.copy(perLiter = ""), FuelField.TOTAL),
        )
    }

    @Test
    fun `nonfinite negative and overflowing decimals are not valid amounts`() {
        for (text in listOf("NaN", "Infinity", "-1", "1e999")) assertNull(parseDecimal(text))
        val overflow = resolveFuelPrices(FuelField.PER_LITER, FuelPrices("1e308", "", "100"))
        assertNull(parseDecimal(overflow.total))
    }

    @Test
    fun `opening an existing amount retains all accepted decimals`() {
        for (amount in listOf(54.321, 0.001, 98.459, 1.8123)) {
            assertEquals(amount, parseDecimal(editableDecimal(amount)))
        }
    }
}
