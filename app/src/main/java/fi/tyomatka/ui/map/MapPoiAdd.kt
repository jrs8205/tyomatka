package fi.tyomatka.ui.map

/** Kartta-välilehden kohteen lisäyksen vaihe: kysy ensin tai lisää päivän listan loppuun. */
enum class MapPoiAddStep { ASK, ADD }

/**
 * Ajon aikana Kartta-välilehdeltä lisättävä kohde varmistetaan ensin, koska se muuttaa ajossa olevaa listaa; ilman ajoa
 * se lisätään suoraan.
 */
fun mapPoiAddStep(driving: Boolean): MapPoiAddStep = if (driving) MapPoiAddStep.ASK else MapPoiAddStep.ADD
