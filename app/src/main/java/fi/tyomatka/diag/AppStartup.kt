package fi.tyomatka.diag

import android.app.Application

/**
 * Sovelluksen käynnistyksessä ajettava koukku. Debug-lähdejoukko sitoo tähän diagnostiikkalokin käynnistyksen
 * (Hilt `@IntoSet`), release-lähdejoukko ei mitään, joten julkaisuversiossa ei ole lokikoodia.
 */
interface AppStartup {
    fun start(app: Application)
}
