package fi.tyomatka.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kuvan pakkaus tallennettavaksi: EXIF-suunta kääntyy kuvaan, pitkä sivu enintään [PHOTO_MAX_SIDE] ja JPEG-laatu
 * [PHOTO_QUALITY]. Uudelleenpakkaus jättää metatiedot pois (myös sijainnin), joten kuvan voi jakaa raportissa.
 */
@Singleton
class PhotoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PhotoStore,
) {
    /** Lukee kuvan (kamera, valitsin tai jako) ja kirjoittaa sen odottavaksi tiedostoksi. */
    suspend fun prepare(source: Uri): File = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Kuvaa ei voitu lukea.")
        val orientation = runCatching {
            open(source).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, PHOTO_MAX_SIDE) }
        val decoded = try {
            open(source).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: OutOfMemoryError) {
            throw IOException("Kuva on liian suuri.", e)
        } ?: throw IOException("Kuvaa ei voitu lukea.")
        val photo = shape(decoded, exifTransform(orientation))
        val target = store.newPending()
        try {
            target.outputStream().use { out ->
                if (!photo.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)) throw IOException("Kuvaa ei voitu tallentaa.")
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            photo.recycle()
        }
        target
    }

    private fun open(source: Uri): InputStream =
        context.contentResolver.openInputStream(source) ?: throw IOException("Kuvaa ei voitu avata.")

    /** Pienennys, kääntö ja peilaus yhdellä muunnoksella; läpinäkyvä kuva valkoiselle, koska JPEG ei tunne läpinäkyvyyttä. */
    private fun shape(bitmap: Bitmap, exif: ExifTransform): Bitmap {
        val scale = photoScale(bitmap.width, bitmap.height, PHOTO_MAX_SIDE)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(exif.degrees.toFloat())
            if (exif.mirror) postScale(-1f, 1f)
        }
        val shaped = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (shaped !== bitmap) bitmap.recycle()
        if (!shaped.hasAlpha()) return shaped
        val opaque = Bitmap.createBitmap(shaped.width, shaped.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(shaped, 0f, 0f, null)
        }
        shaped.recycle()
        return opaque
    }
}
