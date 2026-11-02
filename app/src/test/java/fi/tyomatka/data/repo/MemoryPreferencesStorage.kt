package fi.tyomatka.data.repo

import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

/**
 * Muistinvarainen tallennus: DataStoren tiedoston korvaava uudelleennimeäminen ei onnistu Windowsissa, kun tiedosto
 * on jo olemassa, joten useamman kirjoituksen testit eivät voi käyttää tiedostoa. [readError] jäljittelee rikkinäistä
 * tiedostoa; [failedReads] rajaa virheen ensimmäisiin lukuihin (ohimenevä virhe), oletuksena virhe on pysyvä.
 * [writeError] jäljittelee levyä, jolle ei voi kirjoittaa.
 */
internal class MemoryPreferencesStorage(
    private val readError: IOException? = null,
    private val failedReads: Int = Int.MAX_VALUE,
    private val writeError: IOException? = null,
) : Storage<Preferences> {
    var value: Preferences = emptyPreferences()
    var reads = 0

    override fun createConnection(): StorageConnection<Preferences> = object : StorageConnection<Preferences> {
        override val coordinator: InterProcessCoordinator = createSingleProcessCoordinator("memory")

        override suspend fun <R> readScope(block: suspend ReadScope<Preferences>.(locked: Boolean) -> R): R =
            block(scope(), false)

        override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = block(scope())

        override fun close() = Unit

        private fun scope() = object : WriteScope<Preferences> {
            override suspend fun readData(): Preferences {
                if (readError != null && reads++ < failedReads) throw readError
                return value
            }
            override suspend fun writeData(value: Preferences) {
                writeError?.let { throw it }
                this@MemoryPreferencesStorage.value = value
            }
            override fun close() = Unit
        }
    }
}
