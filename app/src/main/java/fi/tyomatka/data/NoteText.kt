package fi.tyomatka.data

/** Lisätiedot-tekstin enimmäispituus merkkeinä (lomakkeet, repositorio ja tuonti). */
const val NOTE_MAX_LENGTH = 500

/**
 * Lisätiedot-tekstin normalisointi: Windows-rivinvaihdot yhdeksi, reunojen välilyönnit pois ja rajaus [NOTE_MAX_LENGTH]
 * merkkiin (rajattu teksti ei pääty välilyöntiin). Tyhjä teksti tarkoittaa, ettei lisätietoa ole.
 */
fun normalizeNote(raw: String?): String =
    raw.orEmpty().replace("\r\n", "\n").replace('\r', '\n').trim().take(NOTE_MAX_LENGTH).trimEnd()
