package fi.tyomatka.ui.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaneIconTest {

    @Test
    fun `kaistan suunta saa oman kuvakkeen`() {
        assertEquals(Icons.Filled.Straight, laneIcon("straight"))
        assertEquals(Icons.Filled.TurnLeft, laneIcon("left"))
        assertEquals(Icons.Filled.TurnRight, laneIcon("right"))
        assertEquals(Icons.Filled.TurnSlightLeft, laneIcon("slight left"))
        assertEquals(Icons.Filled.TurnSlightRight, laneIcon("slight right"))
        assertEquals(Icons.Filled.TurnSharpLeft, laneIcon("sharp left"))
        assertEquals(Icons.Filled.TurnSharpRight, laneIcon("sharp right"))
        assertEquals(Icons.Filled.UTurnLeft, laneIcon("uturn"))
    }

    @Test
    fun `tuntematon suunta ei saa kuvaketta`() {
        assertNull(laneIcon("none"))
        assertNull(laneIcon(""))
    }
}
