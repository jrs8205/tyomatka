package fi.tyomatka.data.repo

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.db.FuelFillupEntity
import fi.tyomatka.data.db.FuelKind

/**
 * Auton polttoaine (suoraan annettu tieto voittaa, muuten se tulee tankkauksesta): profiili, muuten
 * auton viimeisin polttoainetankkaus, muuten tuntematon. Tankkaus ei kirjoita profiiliin, joten tankkauksen lajin
 * korjaus päivittää oletuksen heti eikä väärä oletus jää pysyväksi.
 */
fun carFuel(plate: String, cars: Map<String, CarEntity>, fillups: List<FuelFillupEntity>): FuelKind? =
    cars[plate]?.fuel
        ?: fillups.filter { it.licensePlate == plate && it.kind.isMainFuel }.maxWithOrNull(fillupOrder)?.kind

/** Autot-näkymän lista: tunnetut rekisterinumerot viimeisin käyttö ensin, sitten pelkät profiilit. */
fun carProfiles(cars: List<CarEntity>, knownPlates: List<String>): List<CarEntity> {
    val byPlate = cars.associateBy { it.plate }
    val plates = (knownPlates + cars.map { it.plate }).distinct()
    return plates.map { byPlate[it] ?: CarEntity(it, null, null) }
}

/**
 * Lomakkeiden rekisterinumeronapit: käytetyt viimeisin ensin, sitten pelkät profiilit, joten esittelyssä tai
 * Autot-näkymässä annettu auto on valittavissa jo ennen ensimmäistä kirjausta.
 */
fun suggestionPlates(knownPlates: List<String>, cars: Collection<CarEntity>): List<String> =
    carProfiles(cars.toList(), knownPlates).map { it.plate }
