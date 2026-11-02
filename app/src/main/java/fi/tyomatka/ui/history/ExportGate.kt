package fi.tyomatka.ui.history

import fi.tyomatka.data.db.CarEntity
import fi.tyomatka.data.repo.WorkHistoryDay

/**
 * HTML-raportin vienti on sallittu, kun on jotakin varmuuskopioitavaa: päiviä (työjaksot ja kirjaukset päivittäin)
 * tai autoprofiileja. Esittelyssä tai Autot-näkymässä lisätty auto käyttövoimineen tallentuu ennen
 * ensimmäistä kirjausta, ja raportti on myös sen varmuuskopio. Tallennetut reitit kulkevat vain
 * koko varmuuskopiossa, ja niitäkin pitää voida varmuuskopioida ilman kirjauksia.
 */
fun canExport(days: List<WorkHistoryDay>, cars: Collection<CarEntity>, templates: Int = 0): Boolean =
    days.isNotEmpty() || cars.isNotEmpty() || templates > 0
