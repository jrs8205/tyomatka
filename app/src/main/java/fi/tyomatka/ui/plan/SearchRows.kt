package fi.tyomatka.ui.plan

import fi.tyomatka.core.model.PlaceSuggestion
import fi.tyomatka.core.model.SuggestionKind

/** Hakulistan rivi: osion otsikko ("Yritykset", "Osoitteet") tai ehdotus. */
sealed interface SearchRow {
    data class Header(val kind: SuggestionKind) : SearchRow
    data class Item(val suggestion: PlaceSuggestion) : SearchRow
}

/**
 * Ehdotukset osioittain hakutuloksen järjestyksessä: otsikko jokaisen ei-tyhjän osion alkuun. Haku antaa osiot
 * peräkkäin (numero hakusanassa → osoitteet ensin); sekaisin tulleet ryhmitellään ensimmäisen esiintymän mukaan.
 */
fun sectioned(suggestions: List<PlaceSuggestion>): List<SearchRow> =
    suggestions.groupBy { it.kind }.flatMap { (kind, items) -> listOf(SearchRow.Header(kind)) + items.map { SearchRow.Item(it) } }
