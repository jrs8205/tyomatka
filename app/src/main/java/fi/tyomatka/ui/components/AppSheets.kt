package fi.tyomatka.ui.components

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Alapaneelin kahva `outline`-värillä (≥ 3:1): oletuskahva on läpikuultava ja jää heikoksi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDragHandle() {
    BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline)
}
