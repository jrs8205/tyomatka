package fi.tyomatka.data.photo

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PhotoStore(tmp.newFolder("files"), tmp.newFolder("cache"))

    @Test
    fun `odottava kuva siirtyy kuvakansioon vasta tallennuksessa`() {
        val store = store()
        val pending = store.newPending().apply { writeText("kuva") }
        assertTrue(store.isPending(pending))

        val name = store.commit(pending)

        assertFalse(pending.exists())
        assertEquals("kuva", store.file(name).readText())
        assertEquals(store.photosDir, store.file(name).parentFile)
    }

    @Test
    fun `vain odottavan kuvan voi tallentaa tai hylätä`() {
        val store = store()
        val stored = store.file("vanha.jpg").apply { parentFile.mkdirs(); writeText("tallennettu") }
        assertFailsWith<IllegalArgumentException> { store.commit(stored) }
        store.discard(stored)
        assertTrue(stored.exists())

        val pending = store.newPending().apply { writeText("uusi") }
        store.discard(pending)
        assertFalse(pending.exists())
    }

    @Test
    fun `siivous poistaa vanhat odottavat ja kameran kuvat sekä mainitsemattomat kuvat ja kertoo puuttuvat`() {
        val store = store()
        val now = 1_800_000_000_000L
        val kept = store.commit(store.newPending().apply { writeText("pidetään") })
        val orphan = store.commit(store.newPending().apply { writeText("orpo") })
        val stalePending = store.newPending().apply { writeText("x"); setLastModified(now - PhotoStore.STALE_AGE_MS - 1) }
        val staleCapture = store.newCapture().apply { writeText("x"); setLastModified(now - PhotoStore.STALE_AGE_MS - 1) }

        store.clearStalePending(now)
        val missing = store.sweepPhotos(referenced = setOf(kept, "puuttuu.jpg"))

        assertEquals(setOf("puuttuu.jpg"), missing)
        assertFalse(stalePending.exists())
        assertFalse(staleCapture.exists())
        assertTrue(store.file(kept).exists())
        assertFalse(store.file(orphan).exists())
    }

    @Test
    fun `käynnistyssiivous säilyttää palautuvan lomakkeen kuvat ja kameran tuoreen raakakuvan`() {
        // Android lopetti prosessin kameran ollessa auki: lomakkeen odottava kuva ja juuri otettu raakakuva ovat tuoreita.
        val store = store()
        val now = 1_800_000_000_000L
        val formPhoto = store.newPending().apply { writeText("lomakkeen kuva"); setLastModified(now - 3 * 60 * 60 * 1000) }
        val capture = store.newCapture().apply { writeText("raakakuva"); setLastModified(now - 1000) }
        val stale = store.newPending().apply { writeText("jäämä"); setLastModified(now - PhotoStore.STALE_AGE_MS - 1) }

        store.clearStalePending(now)

        assertTrue(formPhoto.exists())
        assertTrue(capture.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `kuvakansion siivous jättää odottavat rauhaan`() {
        // Kesken olevan lomakkeen kuvat ovat odottavia: vain käynnistyksen siivous poistaa ne.
        val store = store()
        val pending = store.newPending().apply { writeText("x") }
        val orphan = store.commit(store.newPending().apply { writeText("orpo") })

        assertEquals(emptySet(), store.sweepPhotos(referenced = emptySet()))

        assertTrue(pending.exists())
        assertFalse(store.file(orphan).exists())
    }
}
