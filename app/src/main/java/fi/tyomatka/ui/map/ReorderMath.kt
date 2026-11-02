package fi.tyomatka.ui.map

/** Näkyvän rivin sijainti listan koordinaateissa (px). */
data class VisibleRow(val index: Int, val offset: Int, val size: Int)

/**
 * Indeksi, johon raahattu stoppi [from] siirretään sormen [pointerY] alla olevan
 * rivin paikalle, tai null jos siirtoa ei tule (sormi oman rivin päällä tai
 * rivien ulkopuolella). Kärjen [pinnedCount] ARRIVED-stoppia eivät liiku.
 */
fun reorderTarget(
    visible: List<VisibleRow>,
    from: Int,
    pointerY: Float,
    pinnedCount: Int,
    lastIndex: Int,
): Int? {
    val target = visible
        .firstOrNull { it.index != from && pointerY >= it.offset && pointerY < it.offset + it.size }
        ?.index ?: return null
    val clamped = target.coerceIn(pinnedCount, lastIndex)
    return clamped.takeIf { it != from }
}

/**
 * Muuttaako siirto [from] → [to] ylimmän näkyvän rivin indeksiä. LazyColumn
 * ankkuroi vierityskohdan ylimmän rivin avaimeen, joten ilman kiinnitystä
 * indeksiin lista hyppäisi rivin verran ja kumoaisi automaattivierityksen
 * (havaittu 28.9.: raahaus jäi värisemään listan yläreunaan).
 */
fun swapMovesFirstVisible(firstVisibleIndex: Int, from: Int, to: Int): Boolean =
    firstVisibleIndex in minOf(from, to)..maxOf(from, to)
