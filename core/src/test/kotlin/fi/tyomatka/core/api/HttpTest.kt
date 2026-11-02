package fi.tyomatka.core.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class HttpTest {
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

    private fun truncatedBody(): MockResponse = MockResponse()
        .setBody("x".repeat(16_384))
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    private fun request() = Request.Builder().url(server.url("/")).get().build()

    @Test
    fun `execute raportoi kesken katkenneen rungon poikkeuksena eikä jää odottamaan`() = runBlocking {
        server.enqueue(truncatedBody())

        assertFailsWith<IOException> {
            withTimeout(5_000) { OkHttpClient().execute(request()) }
        }
        Unit
    }

    @Test
    fun `executeRaw raportoi kesken katkenneen rungon poikkeuksena eikä jää odottamaan`() = runBlocking {
        server.enqueue(truncatedBody())

        assertFailsWith<IOException> {
            withTimeout(5_000) { OkHttpClient().executeRaw(request()) }
        }
        Unit
    }
}
