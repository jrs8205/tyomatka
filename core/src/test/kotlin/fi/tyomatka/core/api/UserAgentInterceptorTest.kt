package fi.tyomatka.core.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `lisää tunnistettavan User-Agentin jokaiseen pyyntöön`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addInterceptor(UserAgentInterceptor("0.1")).build()

        client.execute(Request.Builder().url(server.url("/x")).build())

        assertEquals(
            "Tyomatka/0.1 (+https://github.com/jrs8205/tyomatka)",
            server.takeRequest().getHeader("User-Agent"),
        )
    }

    @Test
    fun `muotoilee agentin versiosta`() {
        assertEquals("Tyomatka/1.2.3 (+https://github.com/jrs8205/tyomatka)", userAgent("1.2.3"))
    }
}
