package fi.tyomatka.ui.map

import androidx.compose.runtime.staticCompositionLocalOf
import fi.tyomatka.core.api.Defaults

/** Karttatyylin osoite asetuksista; molemmat kartat lukevat sen tästä. */
val LocalMapStyleUrl = staticCompositionLocalOf { Defaults.TILE_STYLE_LIGHT }
