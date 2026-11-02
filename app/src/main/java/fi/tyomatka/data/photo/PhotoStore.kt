package fi.tyomatka.data.photo

import java.io.File
import java.util.UUID

/**
 * Tankkauskuvien tiedostot sovelluksen omassa tallennustilassa (muut sovellukset eivät näe niitä). Tallennetut kuvat
 * ovat kuvakansiossa (`files/kuitit/`). Lomakkeen uudet kuvat odottavat välimuistissa (`cache/kuitit-uudet/`), kunnes
 * kirjaus tallennetaan. Kameran raakakuvat (`cache/kamera/`, jaettu kamerasovellukselle FileProviderilla) poistetaan
 * pakkauksen jälkeen.
 */
class PhotoStore(filesDir: File, cacheDir: File) {
    val photosDir = File(filesDir, "kuitit")
    val pendingDir = File(cacheDir, "kuitit-uudet")
    val cameraDir = File(cacheDir, "kamera")

    /** Uusi odottava tiedosto pakatulle kuvalle. */
    fun newPending(): File = File(pendingDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    /** Uusi tiedosto kameran raakakuvalle. */
    fun newCapture(): File = File(cameraDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    /** Tallennetun kuvan tiedosto. */
    fun file(fileName: String): File = File(photosDir, fileName)

    fun isPending(file: File): Boolean = file.canonicalFile.parentFile == pendingDir.canonicalFile

    /** Odottava kuva kuvakansioon: palauttaa tiedoston nimen. */
    fun commit(pending: File): String {
        require(isPending(pending)) { "Ei odottava kuva: ${pending.name}" }
        photosDir.mkdirs()
        val target = File(photosDir, pending.name)
        // Sama tiedostojärjestelmä: siirto on nimen vaihto. Varalla kopio.
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
        return target.name
    }

    fun delete(fileName: String) {
        file(fileName).delete()
    }

    /** Lomake suljettiin tallentamatta tai kuva poistettiin ennen tallennusta. */
    fun discard(pending: File) {
        if (isPending(pending)) pending.delete()
    }

    /**
     * Prosessin alussa: odottavista ja kameran tiedostoista poistetaan vain [STALE_AGE_MS]:ää vanhemmat. Android voi
     * lopettaa prosessin kameran ollessa auki, ja palautuva lomake tarvitsee odottavat kuvansa ja kameran juuri
     * kirjoittaman raakakuvan; kaatuneiden istuntojen jäämät siivoutuvat myöhemmin.
     */
    fun clearStalePending(nowMillis: Long = System.currentTimeMillis()) {
        listOf(pendingDir, cameraDir).forEach { dir ->
            dir.listFiles()?.filter { nowMillis - it.lastModified() > STALE_AGE_MS }?.forEach { it.delete() }
        }
    }

    /**
     * Kuvakansion siivous: tiedostot, joita [referenced] ei mainitse, poistetaan. Palauttaa mainitut nimet, joiden
     * tiedosto puuttuu (esimerkiksi pilvivarmuuskopion palautus ilman kuvakansiota).
     */
    fun sweepPhotos(referenced: Set<String>): Set<String> {
        val files = photosDir.listFiles().orEmpty()
        files.filter { it.name !in referenced }.forEach { it.delete() }
        return referenced - files.map { it.name }.toSet()
    }

    companion object {
        /**
         * Käynnistyssiivouksen ikäraja. Lomake on auki enintään työpäivän, ja kameran raakakuva pakataan minuuteissa,
         * joten vuorokautta nuorempi tiedosto voi kuulua palautuvaan lomakkeeseen. Vanhempi on jäämä, ja jäämät ovat
         * pieniä pakattuja kuvia välimuistissa, joten ne saavat odottaa seuraavaa käynnistystä.
         */
        const val STALE_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
