package fi.tyomatka.ui.drive

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveLayerTest {

    @Test
    fun `nakyva ja himmentamaton kartta on hereilla`() {
        assertFalse(driveMapSleeping(visible = true, dimmed = false))
    }

    @Test
    fun `piilossa oleva kartta lepaa`() {
        assertTrue(driveMapSleeping(visible = false, dimmed = false))
    }

    @Test
    fun `verhon alla kartta lepaa vaikka valilehti on nakyvissa`() {
        assertTrue(driveMapSleeping(visible = true, dimmed = true))
    }

    @Test
    fun `piilossa ja verhon alla kartta lepaa`() {
        assertTrue(driveMapSleeping(visible = false, dimmed = true))
    }

    @Test
    fun `kurkistuskorkeus on kahvan ja mitatun sisallon summa`() {
        assertEquals(48.dp + 132.dp, sheetPeekHeight(handle = 48.dp, content = 132.dp))
    }

    @Test
    fun `ennen mittausta kurkistuskorkeus on oletus`() {
        assertEquals(SHEET_PEEK_HEIGHT, sheetPeekHeight(handle = 48.dp, content = 0.dp))
        assertEquals(SHEET_PEEK_HEIGHT, sheetPeekHeight(handle = 0.dp, content = 0.dp))
    }
}
