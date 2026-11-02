package fi.tyomatka.data.repo

import java.util.Currency

/**
 * Sovelluksen valuutta: sovellus toimii vain Suomessa, joten uudet tankkaukset ja vanhojen varmuuskopioiden valuutattomat
 * rivit ovat euroissa. Tallennettu muu valuutta säilyy ja näkyy sellaisenaan.
 */
const val APP_CURRENCY = "EUR"

/** ISO 4217 -valuutta (Javan valuuttaluettelosta), ei tuntematonta `XXX`. */
fun isCurrencyCode(code: String): Boolean = code.matches(CURRENCY_CODE) && code != "XXX" && code in ISO_CURRENCIES

private val CURRENCY_CODE = Regex("[A-Z]{3}")
private val ISO_CURRENCIES: Set<String> by lazy { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }
