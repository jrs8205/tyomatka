package fi.tyomatka.data.report

import fi.tyomatka.data.db.FuelPhotoEntity
import java.io.InputStream

/** Raportin kuvien lähde. Android-toteutus tekee pienoiskuvat; testit käyttävät korviketta. */
interface ReportPhotos {
    /** Gallerian pienoiskuva JPEG-tavuina; null = kuvaa ei voitu pienentää, ja galleriaan tulee paikkamerkki. */
    fun thumbnail(photo: FuelPhotoEntity): ByteArray?

    /** Täysikokoinen kuva; null = tiedosto on poistettu, ja kuva jää raportista pois. */
    fun open(photo: FuelPhotoEntity): InputStream?
}
