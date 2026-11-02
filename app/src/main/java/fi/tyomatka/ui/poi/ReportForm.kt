package fi.tyomatka.ui.poi

import fi.tyomatka.core.poi.FuelType
import fi.tyomatka.core.poi.ReportField

/**
 * Ilmoita muutos -valikon kirjoitettavat osat: polttoaineet monivalintana, aukiolot, korkeus- ja painoraja.
 * [pairs] antaa vain täytetyt ja kelvolliset kentät ([ReportField.accepts]); kyllä/ei-valinnat lähtevät heti ilman lomaketta.
 * [fuels] null = ei muutosta, tyhjä = asema ei myy polttoaineita.
 */
data class ReportForm(
    val fuels: Set<FuelType>? = null,
    val hours: Map<ReportField, HoursForm> = emptyMap(),
    val maxHeight: String = "",
    val maxWeight: String = "",
) {
    /** Polttoaineilmoitus valinnasta, myös muuttumattomana (nykyisen valikoiman vahvistus); null, kun valikoima on tuntematon. */
    fun fuelsReport(): Pair<ReportField, String>? =
        fuels?.let { set -> ReportField.FUELS to if (set.isEmpty()) "none" else FuelType.entries.filter { it in set }.joinToString(";") { it.key } }

    fun pairs(): List<Pair<ReportField, String>> = buildList {
        hours.forEach { (field, form) -> add(field to form.toOsm()) }
        fuelsReport()?.let(::add)
        add(ReportField.MAX_HEIGHT to limitValue(maxHeight))
        add(ReportField.MAX_WEIGHT to limitValue(maxWeight))
    }.filter { (field, value) -> value.isNotEmpty() && field.accepts(value) }
}

private fun limitValue(text: String): String = text.trim().replace(',', '.')

/** Lomakkeen takana oleva tallennettu tieto: arvot ja kentät, jotka odottavat palvelimen kuittausta. */
data class SavedReport(val form: ReportForm, val waiting: Set<ReportField> = emptySet()) {
    private fun value(field: ReportField): Any? = when (field) {
        ReportField.FUELS -> form.fuels
        ReportField.MAX_HEIGHT -> form.maxHeight
        ReportField.MAX_WEIGHT -> form.maxWeight
        else -> form.hours[field]
    }

    /** Muuttuiko juuri tämän kentän arvo tai odotustila. */
    fun changedFrom(before: SavedReport, field: ReportField): Boolean =
        value(field) != before.value(field) || (field in waiting) != (field in before.waiting)
}

/** Kentän muokkaus tallennettuun tietoon nähden: ei muutosta, lähettämätön muutos tai arvo, jota ei voi lähettää. */
enum class EditState { NONE, UNSENT, INVALID }

/**
 * Kuljettajan muokkaukset tallennetun tiedon päällä. Puuttuva kenttä on koskematon ja seuraa tallennettua, joten myöhässä
 * saapuva ilmoitus päivittää sen eikä näytä muutokselta. Muokattu kenttä pysyy, kunnes tallennettu tieto saavuttaa sen ([settled]).
 */
data class ReportEdits(
    val fuels: Set<FuelType>? = null,
    val hours: Map<ReportField, HoursForm> = emptyMap(),
    val maxHeight: String? = null,
    val maxWeight: String? = null,
) {
    fun over(saved: ReportForm): ReportForm = ReportForm(
        fuels = fuels ?: saved.fuels,
        hours = saved.hours + hours,
        maxHeight = maxHeight ?: saved.maxHeight,
        maxWeight = maxWeight ?: saved.maxWeight,
    )

    fun state(field: ReportField, saved: ReportForm): EditState = when (field) {
        ReportField.FUELS -> fuels?.let { if (it == saved.fuels) EditState.NONE else EditState.UNSENT }
        ReportField.MAX_HEIGHT -> maxHeight?.let { limitState(field, it, saved.maxHeight) }
        ReportField.MAX_WEIGHT -> maxWeight?.let { limitState(field, it, saved.maxWeight) }
        else -> hours[field]?.let { hoursState(field, it, saved.hours[field] ?: HoursForm.EMPTY) }
    } ?: EditState.NONE

    /**
     * Kentän oman tallennetun tiedon muutos päättää muokkauksen, kun tieto on nyt sama: lähetys tuli perille tai joku muu
     * ilmoitti saman. Muun kentän muutos ei koske tähän, jottei kirjoitettu "6" muutu kesken kirjoituksen muotoon "06:00".
     * Palvelimen kuittausta odottava kenttä jatkuu, jotta hylätty lähetys ei hävitä kirjoitettua.
     */
    fun settled(before: SavedReport, now: SavedReport): ReportEdits {
        fun keep(field: ReportField) = !now.changedFrom(before, field) || field in now.waiting || state(field, now.form) != EditState.NONE
        return ReportEdits(
            fuels = fuels.takeIf { keep(ReportField.FUELS) },
            hours = hours.filterKeys(::keep),
            maxHeight = maxHeight.takeIf { keep(ReportField.MAX_HEIGHT) },
            maxWeight = maxWeight.takeIf { keep(ReportField.MAX_WEIGHT) },
        )
    }

    private fun limitState(field: ReportField, text: String, saved: String): EditState {
        val value = limitValue(text)
        val base = limitValue(saved)
        val same = value == base || value.toBigDecimalOrNull()?.let { number -> base.toBigDecimalOrNull()?.compareTo(number) == 0 } == true
        return when {
            same -> EditState.NONE
            value.isNotEmpty() && field.accepts(value) -> EditState.UNSENT
            else -> EditState.INVALID
        }
    }

    private fun hoursState(field: ReportField, form: HoursForm, saved: HoursForm): EditState {
        val value = form.toOsm()
        return when {
            form == saved || (value.isNotEmpty() && value == saved.toOsm()) -> EditState.NONE
            value.isNotEmpty() && field.accepts(value) -> EditState.UNSENT
            else -> EditState.INVALID
        }
    }
}
