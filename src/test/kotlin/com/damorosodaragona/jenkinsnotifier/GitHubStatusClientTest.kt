package com.damorosodaragona.jenkinsnotifier

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitHubStatusClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `fetch parses GitHub statuses and chooses best target URL`() {
        var authorizationHeader: String? = null
        val baseUrl = startServer { exchange ->
            authorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            jsonResponse(
                exchange,
                """
                {
                  "state": "failure",
                  "total_count": 2,
                  "statuses": [
                    {
                      "context": "Jenkins / Tests",
                      "state": "success",
                      "description": "tests passed",
                      "target_url": "https://ci/tests"
                    },
                    {
                      "context": "Code Quality",
                      "state": "failure",
                      "description": "quality failed",
                      "target_url": "https://ci/failure"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val summary = GitHubStatusClient(HttpClient.newHttpClient(), baseUrl)
            .fetch("owner/repo", "abc123", "token-123")

        assertEquals("Bearer token-123", authorizationHeader)
        assertEquals("abc123", summary.sha)
        assertEquals("failure", summary.state)
        assertEquals(2, summary.totalCount)
        assertEquals("https://ci/failure", summary.targetUrl)
        assertEquals(2, summary.statuses.size)
        assertEquals("Jenkins / Tests", summary.statuses.first().context)
        assertEquals("tests passed", summary.statuses.first().description)
        assertEquals("https://ci/tests", summary.statuses.first().targetUrl)
    }

    @Test
    fun `fetch omits authorization header when token is blank and falls back total count to statuses size`() {
        var authorizationHeader: String? = "unexpected"
        val baseUrl = startServer { exchange ->
            authorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            jsonResponse(
                exchange,
                """
                {
                  "state": "success",
                  "statuses": [
                    {
                      "context": "Jenkins / Tests",
                      "state": "success",
                      "description": "ok",
                      "target_url": null
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val summary = GitHubStatusClient(HttpClient.newHttpClient(), "$baseUrl/")
            .fetch("owner/repo", "def456", "   ")

        assertNull(authorizationHeader)
        assertEquals(1, summary.totalCount)
        assertNull(summary.targetUrl)
        assertNull(summary.statuses.single().targetUrl)
    }

    @Test
    fun `fetch throws clear error for non successful GitHub response`() {
        val baseUrl = startServer { exchange ->
            val bytes = "rate limited".toByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val error = assertFailsWith<IllegalStateException> {
            GitHubStatusClient(HttpClient.newHttpClient(), baseUrl)
                .fetch("owner/repo", "abc123", "token")
        }

        assertEquals("GitHub returned HTTP 403", error.message)
    }

    @Test
    fun `fetch tolerates missing status fields and falls back to first available url`() {
        val baseUrl = startServer { exchange ->
            jsonResponse(
                exchange,
                """
                {
                  "statuses": [
                    {
                      "target_url": "https://ci/only"
                    },
                    {
                      "context": "Jenkins / Coverage Gate",
                      "state": "success",
                      "description": "covered",
                      "target_url": ""
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val summary = GitHubStatusClient(HttpClient.newHttpClient(), baseUrl)
            .fetch("owner/repo", "ghi789", "token")

        assertEquals("", summary.state)
        assertEquals(2, summary.totalCount)
        assertEquals("https://ci/only", summary.targetUrl)
        assertEquals("", summary.statuses.first().context)
        assertEquals("", summary.statuses.first().state)
        assertEquals("", summary.statuses.first().description)
    }

    @Test
    fun `best target URL prefers failing status URL`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Tests", state = "success", targetUrl = "https://ci/success"),
                status(context = "Other", state = "failure", targetUrl = "https://ci/failure"),
            ),
        )

        assertEquals("https://ci/failure", url)
    }

    @Test
    fun `best target URL prefers Jenkins Tests when there are no failures`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Coverage Gate", state = "success", targetUrl = "https://ci/coverage"),
                status(context = "Jenkins / Tests", state = "success", targetUrl = "https://ci/tests"),
            ),
        )

        assertEquals("https://ci/tests", url)
    }

    @Test
    fun `best target URL falls back to coverage then quality then first available URL`() {
        assertEquals(
            "https://ci/coverage",
            bestTargetUrl(listOf(status(context = "Jenkins / Coverage Gate", targetUrl = "https://ci/coverage"))),
        )
        assertEquals(
            "https://ci/quality",
            bestTargetUrl(listOf(status(context = "Jenkins / Code Quality", targetUrl = "https://ci/quality"))),
        )
        assertEquals(
            "https://ci/other",
            bestTargetUrl(listOf(status(context = "Other", targetUrl = "https://ci/other"))),
        )
    }

    @Test
    fun `best target URL ignores blank and null URLs`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Tests", targetUrl = ""),
                status(context = "Other", state = "failure", targetUrl = null),
            ),
        )

        assertNull(url)
    }

    @Test
    fun `best target URL prefers error state and returns null for empty statuses`() {
        assertEquals(
            "https://ci/error",
            bestTargetUrl(
                listOf(
                    status(context = "Other", state = "error", targetUrl = "https://ci/error"),
                    status(context = "Jenkins / Tests", targetUrl = "https://ci/tests"),
                ),
            ),
        )
        assertNull(bestTargetUrl(emptyList()))
    }

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/") { exchange -> handler(exchange) }
        httpServer.start()
        return "http://127.0.0.1:${httpServer.address.port}"
    }

    private fun jsonResponse(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun bestTargetUrl(statuses: List<CommitStatus>): String? {
        val method = GitHubStatusClient::class.java.getDeclaredMethod("bestTargetUrl", List::class.java)
        method.isAccessible = true
        return method.invoke(GitHubStatusClient(), statuses) as String?
    }

    private fun status(
        context: String,
        state: String = "success",
        description: String = "",
        targetUrl: String? = null,
    ): CommitStatus = CommitStatus(
        context = context,
        state = state,
        description = description,
        targetUrl = targetUrl,
    )
}
