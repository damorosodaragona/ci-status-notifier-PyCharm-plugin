package com.damorosodaragona.jenkinsnotifier

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsStatusClientMassCoverageTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() { server?.stop(0); server = null }

    @Test
    fun `fetchLatestBuildForJobUrl falls back to default display name and build URL when JSON omits them`() {
        val baseUrl = startServer(mapOf(
            "/job/projector/lastBuild/api/json" to jsonResponse("""
                {"number":88,"displayName":"","fullDisplayName":"","result":null,"building":false,"timestamp":0,"duration":0,"artifacts":[]}
            """.trimIndent()),
            "/job/projector/88/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
            "/job/projector/88/wfapi/artifacts" to jsonResponse("[]"),
        ))

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "", "")

        assertEquals(88, summary.number)
        assertEquals("#88", summary.displayName)
        assertEquals("UNKNOWN", summary.state)
        assertEquals("$baseUrl/job/projector/88/", summary.url)
    }

    @Test
    fun `fetchArtifacts falls back to classic artifacts when wfapi artifacts endpoint fails`() {
        val baseUrl = startServer(mapOf(
            "/job/projector/lastBuild/api/json" to jsonResponse("""
                {"number":91,"displayName":"#91","fullDisplayName":"projector #91","result":"SUCCESS","building":false,"url":"__BASE__/job/projector/91/","timestamp":91,"duration":91,"artifacts":[{"fileName":"","relativePath":"reports/fallback.html"},"invalid-artifact"]}
            """.trimIndent()),
            "/job/projector/91/wfapi/describe" to jsonResponse("{\"stages\":[]}"),
            "/job/projector/91/wfapi/artifacts" to textResponse(500, "wfapi unavailable"),
        ))

        val summary = JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/projector", "", "")

        assertEquals(1, summary.artifacts.size)
        assertEquals("fallback.html", summary.artifacts.single().name)
        assertEquals("$baseUrl/job/projector/91/artifact/reports/fallback.html", summary.artifacts.single().url)
    }

    @Test
    fun `diagnose captures redirect headers auth challenges and compact body preview`() {
        val longBody = " line1\n" + "x".repeat(400)
        val baseUrl = startServer(mapOf(
            "/whoAmI/api/json" to textResponse(401, "Unauthorized") { it.responseHeaders.add("WWW-Authenticate", "Basic realm=Jenkins"); it.responseHeaders.add("Content-Type", "text/plain") },
            "/api/json" to textResponse(302, "") { it.responseHeaders.add("Location", "/securityRealm/commenceLogin") },
            "/job/projector/api/json" to textResponse(200, longBody) { it.responseHeaders.add("Content-Type", "application/json") },
        ))

        val steps = JenkinsStatusClient().diagnose(baseUrl, "projector", "", "", preferredBranch = null)

        assertEquals(3, steps.size)
        assertEquals(401, steps[0].statusCode)
        assertEquals("Basic realm=Jenkins", steps[0].wwwAuthenticate)
        assertFalse(steps[0].authHeaderSent)
        assertEquals("/securityRealm/commenceLogin", steps[1].location)
        assertTrue(steps[2].bodyPreview.length <= 240)
        assertFalse(steps[2].bodyPreview.contains("\n"))
    }

    @Test
    fun `downloadArtifacts refreshes existing cache directory before downloading`() {
        val baseUrl = startServer(mapOf("/artifact/new.txt" to textResponse(200, "new content")))
        val client = JenkinsStatusClient()
        val cacheRoot = Files.createTempDirectory("jenkins-cache-refresh-test")
        val summary = summary(101, "$baseUrl/job/projector/101/", listOf(JenkinsArtifact("new.txt", "new.txt", "$baseUrl/artifact/new.txt", 11)))
        val firstCache = client.downloadArtifacts(summary, "", "", cacheRoot)
        firstCache.resolve("old.txt").toFile().writeText("old content")

        val secondCache = client.downloadArtifacts(summary, "", "", cacheRoot)

        assertEquals(firstCache, secondCache)
        assertFalse(secondCache.resolve("old.txt").exists())
        assertEquals("new content", secondCache.resolve("new.txt").readText())
    }

    @Test
    fun `downloadArtifacts throws Jenkins HTTP exception when artifact download fails`() {
        val baseUrl = startServer(mapOf("/artifact/missing.txt" to textResponse(404, "missing")))
        val summary = summary(102, "$baseUrl/job/projector/102/", listOf(JenkinsArtifact("missing.txt", "missing.txt", "$baseUrl/artifact/missing.txt", 7)))

        val error = assertFailsWith<JenkinsHttpException> {
            JenkinsStatusClient().downloadArtifacts(summary, "", "", Files.createTempDirectory("jenkins-cache-missing-test"))
        }

        assertTrue(error.message!!.contains("Jenkins job was not found"))
    }

    @Test
    fun `private URL helpers normalize paths encode jobs resolve urls and create stable ids`() {
        val client = JenkinsStatusClient()

        assertEquals("https://jenkins.example.org", client.callPrivateString("buildJenkinsUrl", "https://jenkins.example.org", ""))
        assertEquals("https://jenkins.example.org/job/folder/job/projector", client.callPrivateString("buildJenkinsUrl", "https://jenkins.example.org", "folder/projector"))
        assertEquals("https://jenkins.example.org/job/folder/job/projector", client.callPrivateString("buildJenkinsUrl", "https://jenkins.example.org", "job/folder/job/projector"))
        assertEquals("https://jenkins.example.org/jenkins", client.callPrivateString("rootUrl", "https://jenkins.example.org/jenkins/job/projector/lastBuild/api/json"))
        assertEquals("https://external.example/report.html", client.callPrivateString("resolveJenkinsUrl", "https://jenkins.example.org/job/projector/1/", "https://external.example/report.html"))
        assertEquals("https://jenkins.example.org/artifact/report.html", client.callPrivateString("resolveJenkinsUrl", "https://jenkins.example.org/job/projector/1/", "/artifact/report.html"))
        assertEquals("report%20file.html", client.callPrivateString("encodePathSegment", "report file.html"))
        assertEquals("feature%252Flogin", client.callPrivateString("encodeJobName", "feature/login"))

        val id1 = client.callPrivateString("stableId", "https://jenkins/job/projector/1/")
        val id2 = client.callPrivateString("stableId", "https://jenkins/job/projector/1/")
        val id3 = client.callPrivateString("stableId", "https://jenkins/job/projector/2/")
        assertEquals(24, id1.length)
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }

    @Test
    fun `private retry and redirect predicates identify expected HTTP statuses`() {
        val client = JenkinsStatusClient()

        assertTrue(client.callPrivateBoolean("shouldRetry", fakeResponse(401)))
        assertTrue(client.callPrivateBoolean("shouldRetry", fakeResponse(503)))
        assertTrue(client.callPrivateBoolean("shouldRetry", fakeResponse(302, location = "/securityRealm/commenceLogin")))
        assertFalse(client.callPrivateBoolean("shouldRetry", fakeResponse(404)))
        assertTrue(client.callPrivateBoolean("isSecurityRedirect", fakeResponse(302, location = "/securityRealm/commenceLogin")))
        assertTrue(client.callPrivateBoolean("isSecurityRedirect", fakeResponse(302, location = "https://id.example.org/keycloak")))
        assertFalse(client.callPrivateBoolean("isSecurityRedirect", fakeResponse(302, location = "/plain-redirect")))
        assertTrue(client.callPrivateIOExceptionBoolean("isTransientNetworkError", java.io.IOException("stream reset by peer")))
        assertFalse(client.callPrivateIOExceptionBoolean("isTransientNetworkError", java.io.IOException("permanent failure")))
    }

    private fun JenkinsStatusClient.callPrivateString(methodName: String, vararg args: String): String {
        val method = JenkinsStatusClient::class.java.getDeclaredMethod(methodName, *Array(args.size) { String::class.java })
        method.isAccessible = true
        return method.invoke(this, *args) as String
    }

    private fun JenkinsStatusClient.callPrivateBoolean(methodName: String, response: HttpResponse<String>): Boolean {
        val method = JenkinsStatusClient::class.java.getDeclaredMethod(methodName, HttpResponse::class.java)
        method.isAccessible = true
        return method.invoke(this, response) as Boolean
    }

    private fun JenkinsStatusClient.callPrivateIOExceptionBoolean(methodName: String, error: java.io.IOException): Boolean {
        val method = JenkinsStatusClient::class.java.getDeclaredMethod(methodName, java.io.IOException::class.java)
        method.isAccessible = true
        return method.invoke(this, error) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeResponse(statusCode: Int, location: String? = null): HttpResponse<String> {
        val headers = java.net.http.HttpHeaders.of(if (location == null) emptyMap() else mapOf("Location" to listOf(location))) { _, _ -> true }
        return Proxy.newProxyInstance(HttpResponse::class.java.classLoader, arrayOf(HttpResponse::class.java)) { _, method, _ ->
            when (method.name) {
                "statusCode" -> statusCode
                "headers" -> headers
                "body" -> ""
                "toString" -> "HttpResponse($statusCode)"
                else -> null
            }
        } as HttpResponse<String>
    }

    private fun summary(number: Int, url: String, artifacts: List<JenkinsArtifact>) = JenkinsBuildSummary(
        number = number, displayName = "#$number", fullDisplayName = "projector #$number", result = "SUCCESS", building = false,
        url = url, timestampMillis = number.toLong(), durationMillis = number.toLong(), stages = emptyList(), artifacts = artifacts,
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
            } else route(exchange, baseUrl)
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

    private fun textResponse(status: Int, body: String, beforeSend: (HttpExchange) -> Unit = {}): (HttpExchange, String) -> Unit = { exchange, _ ->
        beforeSend(exchange)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
