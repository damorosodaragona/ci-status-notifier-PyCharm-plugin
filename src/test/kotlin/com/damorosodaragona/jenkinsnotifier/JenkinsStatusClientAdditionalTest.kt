package com.damorosodaragona.jenkinsnotifier

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenkinsStatusClientAdditionalTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `fetchLatestBuildForJobUrl prefers workflow artifacts when Jenkins wfapi artifacts are available`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 50,
                      "displayName": "#50",
                      "fullDisplayName": "projector/main #50",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/projector/50/",
                      "timestamp": 1710000000000,
                      "duration": 100,
                      "artifacts": [
                        {"fileName": "classic.html", "relativePath": "classic/classic.html"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/50/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/50/wfapi/artifacts" to jsonResponse(
                    """
                    [
                      {"name": "workflow.html", "path": "workflow/workflow.html", "url": "artifact/workflow/workflow.html", "size": 128}
                    ]
                    """.trimIndent(),
                ),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")

        assertEquals(1, summary.artifacts.size)
        assertEquals("workflow.html", summary.artifacts.single().name)
        assertEquals("workflow/workflow.html", summary.artifacts.single().path)
        assertEquals("$baseUrl/job/projector/50/artifact/workflow/workflow.html", summary.artifacts.single().url)
        assertEquals(128L, summary.artifacts.single().size)
    }

    @Test
    fun `fetchLatestBuild resolves preferred multibranch job before reading latest build`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/api/json" to jsonResponse(
                    """
                    {
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "main", "url": "__BASE__/job/projector/job/main/", "color": "blue"},
                        {"name": "feature%2Flogin", "url": "__BASE__/job/projector/job/feature%252Flogin/", "color": "red"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/feature%2Flogin/api/json" to jsonResponse(
                    """
                    {
                      "buildable": true,
                      "lastBuild": {"number": 77},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/feature%2Flogin/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 77,
                      "displayName": "#77",
                      "fullDisplayName": "projector/feature/login #77",
                      "result": "FAILURE",
                      "building": false,
                      "url": "__BASE__/job/projector/job/feature%252Flogin/77/",
                      "timestamp": 1710000007000,
                      "duration": 700,
                      "artifacts": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/feature%2Flogin/77/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/job/feature%2Flogin/77/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuild(baseUrl, "projector", "user", "token", preferredBranch = "feature/login")

        assertEquals(77, summary.number)
        assertEquals("FAILURE", summary.state)
        assertTrue(summary.url.contains("feature%252Flogin"))
    }

    @Test
    fun `fetchJobTree marks build jobs and auto selects preferred branch`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/api/json" to jsonResponse(
                    """
                    {
                      "name": "projector",
                      "displayName": "projector",
                      "url": "__BASE__/job/projector/",
                      "color": "",
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "main", "displayName": "main", "url": "__BASE__/job/projector/job/main/", "color": "blue"},
                        {"name": "develop", "displayName": "develop", "url": "__BASE__/job/projector/job/develop/", "color": "blue_anime"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/main/api/json" to jsonResponse(
                    """
                    {
                      "name": "main",
                      "displayName": "main",
                      "url": "__BASE__/job/projector/job/main/",
                      "color": "blue",
                      "buildable": true,
                      "lastBuild": {"number": 10, "result": "SUCCESS", "building": false},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/develop/api/json" to jsonResponse(
                    """
                    {
                      "name": "develop",
                      "displayName": "develop",
                      "url": "__BASE__/job/projector/job/develop/",
                      "color": "blue_anime",
                      "buildable": true,
                      "lastBuild": {"number": 11, "result": null, "building": true},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val tree = JenkinsStatusClient().fetchJobTree(baseUrl, "projector", "user", "token", preferredBranch = "develop")

        assertEquals("projector", tree.root.name)
        assertEquals(2, tree.root.children.size)
        assertEquals("develop", tree.autoSelected?.name)
        assertEquals(11, tree.autoSelected?.lastBuildNumber)
        assertEquals(true, tree.autoSelected?.lastBuildBuilding)
        assertTrue(tree.autoSelected!!.isBuildJob)
    }

    @Test
    fun `downloadArtifacts writes artifacts under cache root`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/artifact/reports/index.html" to textResponse(200, "<html>ok</html>"),
                "/artifact/logs/log.txt" to textResponse(200, "log output"),
            ),
        )
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-test")
        val summary = summary(
            artifacts = listOf(
                JenkinsArtifact("index.html", "reports/index.html", "$baseUrl/artifact/reports/index.html", 15),
                JenkinsArtifact("log.txt", "logs/log.txt", "$baseUrl/artifact/logs/log.txt", null),
            ),
        )

        val buildCache = JenkinsStatusClient().downloadArtifacts(summary, "user", "token", cacheRoot)

        assertTrue(buildCache.resolve("reports/index.html").exists())
        assertEquals("<html>ok</html>", buildCache.resolve("reports/index.html").readText())
        assertEquals("log output", buildCache.resolve("logs/log.txt").readText())
    }

    @Test
    fun `downloadArtifacts rejects artifact paths escaping cache directory`() {
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-escape-test")
        val summary = summary(
            artifacts = listOf(
                JenkinsArtifact("evil.txt", "../evil.txt", "http://127.0.0.1/evil.txt", 1),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            JenkinsStatusClient().downloadArtifacts(summary, "user", "token", cacheRoot)
        }

        assertTrue(error.message!!.contains("escapes cache directory"))
    }

    @Test
    fun `diagnose records json content type and whether authorization header was sent`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/whoAmI/api/json" to jsonResponse("{\"name\":\"user\"}"),
                "/api/json" to jsonResponse("{\"mode\":\"NORMAL\"}"),
                "/job/projector/api/json" to jsonResponse("{\"buildable\":true,\"lastBuild\":{\"number\":1},\"jobs\":[]}"),
                "/job/projector/lastBuild/api/json" to jsonResponse("{\"number\":1}"),
            ),
        )

        val steps = JenkinsStatusClient().diagnose(baseUrl, "projector", "user", "token", preferredBranch = null)

        assertTrue(steps.size >= 3)
        assertTrue(steps.all { it.authHeaderSent })
        assertTrue(steps.take(3).all { it.ok })
        assertEquals("whoAmI", steps.first().name)
        assertTrue(steps.first().bodyPreview.contains("user"))
    }

    @Test
    fun `requests include basic auth header when username and token are configured`() {
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:token".toByteArray())
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to { exchange, _ ->
                    assertEquals(expected, exchange.requestHeaders.getFirst("Authorization"))
                    jsonResponse(
                        """
                        {
                          "number": 1,
                          "displayName": "#1",
                          "fullDisplayName": "projector #1",
                          "result": "SUCCESS",
                          "building": false,
                          "url": "__BASE__/job/projector/1/",
                          "timestamp": 1,
                          "duration": 1,
                          "artifacts": []
                        }
                        """.trimIndent(),
                    )(exchange, "http://127.0.0.1:${server!!.address.port}")
                },
                "/job/projector/1/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/1/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")

        assertEquals(1, summary.number)
    }

    @Test
    fun `HTTP 404 returns Jenkins job not found message`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/missing/lastBuild/api/json" to textResponse(404, "Not found"),
            ),
        )

        val error = assertFailsWith<JenkinsHttpException> {
            JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/missing", "user", "token")
        }

        assertNotNull(error.message)
        assertTrue(error.message!!.contains("Jenkins job was not found"))
    }

    @Test
    fun `fetchLatestBuild encodes nested job path segments`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/folder/job/projector/api/json" to jsonResponse(
                    """
                    {
                      "buildable": true,
                      "lastBuild": {"number": 12},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/projector/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 12,
                      "displayName": "#12",
                      "fullDisplayName": "folder/projector #12",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/folder/job/projector/12/",
                      "timestamp": 12,
                      "duration": 12,
                      "artifacts": []
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/projector/12/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/folder/job/projector/12/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuild(baseUrl, "folder/projector", "user", "token", preferredBranch = null)

        assertEquals(12, summary.number)
        assertTrue(summary.url.contains("/job/folder/job/projector/12/"))
    }

    @Test
    fun `classic artifact URL encodes spaces in path segments`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 13,
                      "displayName": "#13",
                      "fullDisplayName": "projector #13",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/projector/13/",
                      "timestamp": 13,
                      "duration": 13,
                      "artifacts": [
                        {"fileName": "index file.html", "relativePath": "reports/index file.html"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/13/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/13/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "token")

        assertEquals("$baseUrl/job/projector/13/artifact/reports/index%20file.html", summary.artifacts.single().url)
    }

    @Test
    fun `manual request mode returns Jenkins HTTP exception for 401 instead of auth expired exception`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to textResponse(401, "Unauthorized"),
            ),
        )

        val error = assertFailsWith<JenkinsHttpException> {
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
                JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "user", "wrong-token")
            }
        }

        assertTrue(error.message!!.contains("Jenkins authentication failed"))
    }

    @Test
    fun `requests omit authorization header when username or token is blank`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/lastBuild/api/json" to { exchange, base ->
                    assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
                    jsonResponse(
                        """
                        {
                          "number": 14,
                          "displayName": "#14",
                          "fullDisplayName": "projector #14",
                          "result": "SUCCESS",
                          "building": false,
                          "url": "__BASE__/job/projector/14/",
                          "timestamp": 14,
                          "duration": 14,
                          "artifacts": []
                        }
                        """.trimIndent(),
                    )(exchange, base)
                },
                "/job/projector/14/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
                "/job/projector/14/wfapi/artifacts" to jsonResponse("[]"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "", "token")

        assertEquals(14, summary.number)
    }

    @Test
    fun `downloadArtifacts rejects too many artifacts before making downloads`() {
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-count-limit-test")
        val artifacts = (1..501).map { index ->
            JenkinsArtifact("file-$index.txt", "files/file-$index.txt", "http://127.0.0.1/file-$index.txt", 1)
        }
        val summary = summary(artifacts = artifacts)

        val error = assertFailsWith<IllegalStateException> {
            JenkinsStatusClient().downloadArtifacts(summary, "user", "token", cacheRoot)
        }

        assertTrue(error.message!!.contains("exceeds the limit"))
    }

    @Test
    fun `downloadArtifacts rejects configured artifact size above preview limit`() {
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-size-limit-test")
        val summary = summary(
            artifacts = listOf(
                JenkinsArtifact("huge.bin", "huge.bin", "http://127.0.0.1/huge.bin", 101L * 1024L * 1024L),
            ),
        )

        val error = assertFailsWith<IllegalStateException> {
            JenkinsStatusClient().downloadArtifacts(summary, "user", "token", cacheRoot)
        }

        assertTrue(error.message!!.contains("preview limit"))
    }

    private fun summary(artifacts: List<JenkinsArtifact>): JenkinsBuildSummary = JenkinsBuildSummary(
        number = 1,
        displayName = "#1",
        fullDisplayName = "projector #1",
        result = "SUCCESS",
        building = false,
        url = "http://jenkins/job/projector/1/",
        timestampMillis = 1L,
        durationMillis = 1L,
        stages = emptyList(),
        artifacts = artifacts,
    )

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
}
