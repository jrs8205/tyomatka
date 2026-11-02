package fi.tyomatka.data.repo

import fi.tyomatka.data.db.FuelFillupEntity
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Kuvat raportissa ja varmuuskopiossa ("A, kuvat mukaan HTML-vientiin"). */
class WorkLogPhotoTransferTest {
    private val nb = '\u00A0'
    private val noon = LocalDate.of(2026, 9, 25).atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond()

    private fun fillup(id: Long) = FuelFillupEntity(id, noon, "ABC-123", 100_000 + id, 50.0, 90.0, 1.8, currency = "EUR")

    private fun photoLines(report: String) = report.lines().filter { it.startsWith(PHOTO_LINE) }

    /**
     * Kuvarivin attribuutit nimittäin. Rivin on oltava kokonaan muotoa `<img nimi="arvo" …>`: jokainen arvo
     * lainausmerkeissä eikä arvoissa lainausmerkkejä tai kulmasulkeita, jotta selain lukee ne samoin kuin tämä.
     */
    private fun attributes(line: String): List<Pair<String, String>> {
        assertTrue(IMG_TAG.matches(line), "Kuvarivi ei ole kelvollinen img-tagi: ${line.take(160)}")
        return ATTRIBUTE.findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    @Test
    fun `kuvarivillä on yksi src ja sen base64 purkautuu tallennetuksi kuvaksi`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1))
        val receipt = store.addPhoto(1, byteArrayOf(1, 2, 3), at = 10)
        val pump = store.addPhoto(1, ByteArray(5000) { it.toByte() }, at = 11)

        val lines = photoLines(store.repository.exportHtml(noon))

        assertEquals(2, lines.size)
        listOf(receipt, pump).zip(lines).forEach { (photo, line) ->
            val attributes = attributes(line)
            assertEquals(1, attributes.count { it.first == "src" }, line.take(160))
            val src = assertNotNull(attributes.toMap()["src"])
            assertTrue(src.startsWith(DATA_PREFIX), src.take(60))
            val bytes = Base64.getDecoder().decode(src.removePrefix(DATA_PREFIX))
            assertContentEquals(store.photoStore.file(photo.fileName).readBytes(), bytes)
            assertEquals("kuva-${photo.backupId}", attributes.toMap()["id"])
        }
    }

    @Test
    fun `kuvarivin alt on escapattu eikä riko attribuutteja`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.add(fillup(1).copy(licensePlate = "A\"B&C<D"))
        store.addPhoto(1, byteArrayOf(1))

        val line = photoLines(store.repository.exportHtml(noon)).single()

        val attributes = attributes(line).toMap()
        assertEquals("Kuva 1/1: Pe${nb}25.9.2026, A&quot;B&amp;C&lt;D", attributes["alt"])
        assertEquals(listOf("class", "id", "alt", "src"), attributes.keys.toList())
    }

    @Test
    fun `vanhan viennin kuvarivi tuplatulla src-alulla tuodaan edelleen`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        val photo = source.addPhoto(1, byteArrayOf(4, 5, 6), at = 10)
        // Ennen 17.10. tehdyissä vienneissä kuvarivi on muotoa src="src="data:…; niiden kuvat pitää saada takaisin.
        val legacy = source.repository.exportHtml(noon).lines().joinToString("\n") { line ->
            if (line.startsWith(PHOTO_LINE)) line.replace(" src=\"$DATA_PREFIX", " src=\"src=\"$DATA_PREFIX") else line
        }
        assertTrue(legacy.contains("src=\"src=\"$DATA_PREFIX"))

        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(legacy)

        assertEquals(1, result.photosAdded)
        val restored = destination.photos.single()
        assertEquals(photo.backupId, restored.backupId)
        assertContentEquals(byteArrayOf(4, 5, 6), destination.photoStore.file(restored.fileName).readBytes())
    }

    @Test
    fun `kuvanapit ja galleria viittaavat kirjauksiin ja tunnisteet ovat yksilöllisiä`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.addAll(listOf(fillup(1), fillup(2), fillup(3)))
        store.addPhoto(1, byteArrayOf(1))
        store.addPhoto(2, byteArrayOf(2))
        store.addPhoto(2, byteArrayOf(3))

        val report = store.repository.exportHtml(noon)

        val entryRefs = Regex("data-entry=\"([0-9]+)\"").findAll(report).map { it.groupValues[1] }.toSet()
        val cards = Regex("<li class=\"card entry\" id=\"e-([0-9]+)\"").findAll(report).map { it.groupValues[1] }.toSet()
        assertEquals(2, entryRefs.size)
        assertEquals(3, cards.size)
        assertTrue(cards.containsAll(entryRefs))
        val thumbs = Regex("data-photo=\"([^\"]*)\"").findAll(report).map { it.groupValues[1] }.toList()
        assertEquals(store.photos.map { it.backupId }.sorted(), thumbs.sorted())
        val ids = Regex(" id=\"([^\"]*)\"").findAll(report).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.distinct().size, ids.toString())
        assertTrue(ids.none { it.isBlank() || it.any(Char::isWhitespace) }, ids.toString())
    }

    @Test
    fun `kuvat kulkevat raportissa ja palautuvat tuonnissa`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        val receipt = source.addPhoto(1, byteArrayOf(1, 2, 3), at = 10)
        val pump = source.addPhoto(1, ByteArray(5000) { it.toByte() }, at = 11)

        val report = source.repository.exportHtml(noon)

        assertTrue(report.contains("\"version\":8"))
        assertTrue(report.contains(">Kuvat <span class=\"count\""))
        assertEquals(2, photoLines(report).size)
        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(report)
        assertEquals(1, result.fuelAdded)
        assertEquals(2, result.photosAdded)
        val restored = destination.photos.sortedBy { it.addedAtEpochSec }
        assertEquals(listOf(receipt.backupId, pump.backupId), restored.map { it.backupId })
        assertEquals(listOf(10L, 11L), restored.map { it.addedAtEpochSec })
        assertEquals(destination.fillups.single().id, restored.map { it.fillupId }.distinct().single())
        assertContentEquals(byteArrayOf(1, 2, 3), destination.photoStore.file(restored[0].fileName).readBytes())
        assertContentEquals(ByteArray(5000) { it.toByte() }, destination.photoStore.file(restored[1].fileName).readBytes())

        // Uusi tuonti ei tuplaa kuvia eikä jätä odottavia tiedostoja.
        assertEquals(0, destination.repository.importFromHtml(report).photosAdded)
        assertEquals(2, destination.photos.size)
        assertTrue(destination.photoStore.pendingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `kuvat liittyvät puhelimessa jo olevaan kirjaukseen`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        source.addPhoto(1, byteArrayOf(7))
        // Sama kirjaus (sama tunniste) eri id:llä, ilman kuvia.
        val phone = WorkLogTestStore()
        phone.fillups.add(source.fillups.single().copy(id = 5))
        // Sama sisältö eri tunnisteella (vanhan varmuuskopion palautus).
        val other = WorkLogTestStore()
        other.fillups.add(source.fillups.single().copy(id = 9, backupId = "toinen"))

        val report = source.repository.exportHtml(noon)
        val result = phone.repository.importFromHtml(report)
        val otherResult = other.repository.importFromHtml(report)

        assertEquals(1, result.fuelSkipped)
        assertEquals(1, result.photosAdded)
        assertEquals(5, phone.photos.single().fillupId)
        assertEquals(1, otherResult.photosAdded)
        assertEquals(9, other.photos.single().fillupId)
    }

    @Test
    fun `rikkinäinen kuvarivi ohitetaan ja muu tuodaan`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        val good = source.addPhoto(1, byteArrayOf(1), at = 10)
        val bad = source.addPhoto(1, byteArrayOf(2), at = 11)
        val report = source.repository.exportHtml(noon)
        val broken = report.lines().joinToString("\n") { line ->
            if (line.startsWith("$PHOTO_LINE${bad.backupId}\"")) line.replace("base64,", "base64,!!!") else line
        }

        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(broken)

        assertEquals(1, result.fuelAdded)
        assertEquals(listOf(good.backupId), destination.photos.map { it.backupId })
        assertTrue(destination.photoStore.pendingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `puuttuva kuvatiedosto jää pois viennistä`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        source.addPhoto(1, byteArrayOf(1), at = 10)
        val lost = source.addPhoto(1, byteArrayOf(2), at = 11)
        source.photoStore.delete(lost.fileName)

        val report = source.repository.exportHtml(noon)

        assertEquals(1, photoLines(report).size)
        assertFalse(report.contains(lost.backupId))
    }

    @Test
    fun `kesken viennin poistettu kuva jää pois ja vienti valmistuu kokonaisena`() = runTest {
        val source = WorkLogTestStore()
        source.fillups.add(fillup(1))
        val kept = source.addPhoto(1, byteArrayOf(1, 2, 3), at = 10)
        val deleted = source.addPhoto(1, byteArrayOf(4, 5, 6), at = 11)
        // Poisto vasta, kun vienti on jo valinnut kuvat: ensimmäinen kirjoitus virtaan tulee otsikko-osasta, ennen kuin
        // toinen kuva avataan (Windowsissa avoimen tiedoston poisto ei onnistu).
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                source.photoStore.delete(deleted.fileName)
                super.write(b, off, len)
            }
        }

        source.repository.exportHtml(noon, out)

        val report = out.toString(Charsets.UTF_8.name())
        assertEquals(listOf(kept.backupId), photoLines(report).map { it.removePrefix(PHOTO_LINE).substringBefore('"') })
        assertTrue(report.contains("<script type=\"application/json\" id=\"tyomatka-data\">"))
        assertTrue(report.trimEnd().endsWith("</script></body></html>"))
        // Tuonti ohittaa JSONin kuvan, jolta puuttuu kuvarivi, ja palauttaa muut.
        val destination = WorkLogTestStore()
        val result = destination.repository.importFromHtml(report)
        assertEquals(1, result.fuelAdded)
        assertEquals(1, result.photosAdded)
        assertEquals(listOf(kept.backupId), destination.photos.map { it.backupId })
        assertContentEquals(byteArrayOf(1, 2, 3), destination.photoStore.file(destination.photos.single().fileName).readBytes())
        assertTrue(destination.photoStore.pendingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `kirjauksella on kuvanappi ja ilman kuvia galleria kertoo sen`() = runTest {
        val store = WorkLogTestStore()
        store.fillups.addAll(listOf(fillup(1), fillup(2)))
        store.addPhoto(1, byteArrayOf(1))
        store.addPhoto(1, byteArrayOf(2))

        val report = store.repository.exportHtml(noon)

        assertTrue(Regex("<button class=\"photo-btn[^>]*>.*?2${nb}kuvaa</button>").containsMatchIn(report))
        // Ilman kuvia kirjauksilla ei ole kuvanappia, ja Kuvat-osio kertoo, ettei kuvia ole.
        val plain = WorkLogTestStore().apply { fillups.add(fillup(1)) }.repository.exportHtml(noon)
        assertFalse(plain.contains("class=\"photo-btn"))
        assertTrue(plain.contains("Ei kuvia."))
    }

    private companion object {
        const val PHOTO_LINE = "<img class=\"kuva\" id=\"kuva-"
        const val DATA_PREFIX = "data:image/jpeg;base64,"
        /** Koko rivi: vain lainausmerkeissä olevia attribuutteja, joiden arvossa ei ole lainausmerkkiä tai kulmasuljetta. */
        val IMG_TAG = Regex("^<img( [a-z]+=\"[^\"<>]*\")+>$")
        val ATTRIBUTE = Regex(" ([a-z]+)=\"([^\"]*)\"")
    }
}
