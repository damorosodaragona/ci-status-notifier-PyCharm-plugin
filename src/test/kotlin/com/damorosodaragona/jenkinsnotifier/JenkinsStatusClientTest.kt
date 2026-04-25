package com.damorosodaragona.jenkinsnotifier

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JenkinsStatusClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `fetchLatestBuildForJobUrl parses Jenkins build, lastBuild result, building flag, stages and artifacts`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 42,
                      "displayName": "#42",
                      "fullDisplayName": "projector/main #42",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/projector/42/",
                      "timestamp": 1710000000000,
                      "duration": 12345,
                      "artifacts": [
                        {"fileName": "index.html", "relativePath": "reports/index.html"},
                        {"fileName": "log.txt", "relativePath": "logs/log.txt"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/42/wfapi/describe" to jsonResponse(
                    """
                    {
                      "stages": [
                        {
                          "id": "1",
                          "name": "Build",
                          "status": "SUCCESS",
                          "durationMillis": 1000,
                          "_links": {"self": {"href": "wfapi/describe"}}
                        },
                        {
                          "id": "2",
                          "name": "Test",
                          "status": "SUCCESS",
                          "durationMillis": 2000
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/42/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")

        assertEquals(42, summary.number)
        assertEquals("#42", summary.displayName)
        assertEquals("projector/main #42", summary.fullDisplayName)
        assertEquals("SUCCESS", summary.result)
        assertEquals("SUCCESS", summary.state)
        assertEquals(false, summary.building)
        assertEquals(1710000000000L, summary.timestampMillis)
        assertEquals(12345L, summary.durationMillis)

        assertEquals(2, summary.stages.size)
        assertEquals("1", summary.stages[0].id)
        assertEquals("Build", summary.stages[0].name)
        assertEquals("SUCCESS", summary.stages[0].status)
        assertEquals(1000L, summary.stages[0].durationMillis)
        assertTrue(summary.stages[0].url!!.startsWith(baseUrl))

        assertEquals(2, summary.artifacts.size)
        assertEquals("index.html", summary.artifacts[0].name)
        assertEquals("reports/index.html", summary.artifacts[0].path)
        assertEquals("$baseUrl/job/projector/42/artifact/reports/index.html", summary.artifacts[0].url)
        assertTrue(summary.artifacts[0].isHtml)
    }

    @Test
    fun `fetchLatestBuildForJobUrl parses running build with null result as RUNNING`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 43,
                      "displayName": "#43",
                      "fullDisplayName": "projector/main #43",
                      "result": null,
                      "building": true,
                      "url": "__BASE__/job/projector/43/",
                      "timestamp": 1710000005000,
                      "duration": 0,
                      "artifacts": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/43/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/43/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")

        assertEquals(43, summary.number)
        assertEquals(null, summary.result)
        assertEquals(true, summary.building)
        assertEquals("RUNNING", summary.state)
    }

    @Test
    fun `HTTP 401 is treated as expired Jenkins authentication in background mode`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to textResponse(401, "Unauthorized"),
            ),
        )

        assertFailsWith<JenkinsAuthenticationExpiredException> {
            JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "wrong-token")
        }
    }

    @Test
    fun `HTTP 403 is treated as expired Jenkins authentication in background mode`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to textResponse(403, "Forbidden"),
            ),
        )

        assertFailsWith<JenkinsAuthenticationExpiredException> {
            JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "wrong-token")
        }
    }

    @Test
    fun `securityRealm redirect is reported as Jenkins HTTP error`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to redirectResponse("/securityRealm/commenceLogin"),
            ),
        )

        val error = assertFailsWith<JenkinsHttpException> {
            JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")
        }

        assertTrue(error.message!!.contains("HTTP 302"))
        assertTrue(error.message!!.contains("securityRealm/commenceLogin"))
    }

    private fun startServer(routes: Map<String, (HttpExchange, String) -> Unit>): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/") { exchange ->
            val baseUrl = "http://127.0.0.1:${httpServer.address.port}"
            val route = routes[exchange.requestURI.path]
            if (route == null) {
                val bytes = "Not found: ${exchange.requestURI.path}".toByteArray()
                exchange.sendResponseHeaders(404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                route(exchange, baseUrl)
            }
        }
        httpServer.start()
        return "http://127.0.0.1:${httpServer.address.port}"
    }

    private fun jsonResponse(body: String): (HttpExchange, String) -> Unit = { exchange, baseUrl ->
        val bytes = body.replace("__BASE__", baseUrl).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun textResponse(status: Int, body: String): (HttpExchange, String) -> Unit = { exchange, _ ->
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun redirectResponse(location: String): (HttpExchange, String) -> Unit = { exchange, _ ->
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }
}
