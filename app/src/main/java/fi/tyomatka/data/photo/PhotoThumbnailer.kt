package fi.tyomatka.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fi.tyomatka.data.db.FuelPhotoEntity
import fi.tyomatka.data.report.ReportPhotos
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.roundToInt

/** Gallerian pienoiskuvan pitkä sivu: puhelimen kahden sarakkeen ruudukossa terävä, ja 200 kuvaa vie noin 6 Mt. */
const val THUMBNAIL_SIDE = 400

/** Pienoiskuvan JPEG-laatu. */
const val THUMBNAIL_QUALITY = 75

/**
 * Raportin kuvat tallennetuista tiedostoista. Pienoiskuva puretaan pienennettynä ([sampleSize]), joten iso kuva ei
 * pura täyteen kokoonsa muistiin. Tallennetut kuvat ovat jo oikein päin (EXIF käsitellään tallennuksessa).
 */
class PhotoThumbnailer(private val store: PhotoStore) : ReportPhotos {
    override fun thumbnail(photo: FuelPhotoEntity): ByteArray? = runCatching {
        val path = store.file(photo.fileName).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_SIDE)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        val scale = photoScale(decoded.width, decoded.height, THUMBNAIL_SIDE)
        val scaled = if (scale < 1f) {
            val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, width, height, true)
        } else {
            decoded
        }
        try {
            ByteArrayOutputStream().use { out ->
                if (scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)) out.toByteArray() else null
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    override fun open(photo: FuelPhotoEntity): InputStream? = try {
        store.file(photo.fileName).inputStream()
    } catch (e: FileNotFoundException) {
        null
    }
}
