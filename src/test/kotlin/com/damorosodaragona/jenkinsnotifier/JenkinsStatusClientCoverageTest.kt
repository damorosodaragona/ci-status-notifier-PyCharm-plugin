package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.google.gson.JsonParser
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class JenkinsStatusClientCoverageTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `fetchLatestBuild resolves folder jobs and falls back when wfapi endpoints fail`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/folder/api/json" to jsonResponse(
                    """
                    {
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "dev", "url": "__BASE__/job/folder/job/dev/"},
                        {"name": "main", "url": "__BASE__/job/folder/job/main/"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/main/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 7,
                      "displayName": "",
                      "fullDisplayName": "folder/main #7",
                      "result": "",
                      "building": false,
                      "url": "",
                      "timestamp": 123,
                      "duration": 456,
                      "artifacts": [
                        {"fileName": "", "relativePath": "reports/index.html"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/main/7/wfapi/describe" to textResponse(404, "missing"),
                "/job/folder/job/main/7/wfapi/artifacts" to textResponse(404, "missing"),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuild(baseUrl, "folder", "user", "token")

        assertEquals(7, summary.number)
        assertEquals("#7", summary.displayName)
        assertEquals("UNKNOWN", summary.state)
        assertEquals("$baseUrl/job/folder/job/main/7/", summary.url)
        assertTrue(summary.stages.isEmpty())
        assertEquals(1, summary.artifacts.size)
        assertEquals("index.html", summary.artifacts.single().name)
        assertEquals("reports/index.html", summary.artifacts.single().path)
    }

    @Test
    fun `fetchLatestBuild prefers encoded multibranch names`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/folder/api/json" to jsonResponse(
                    """
                    {
                      "buildable": false,
                      "jobs": [
                        {"name": "feature%252Fdemo", "url": "__BASE__/job/folder/job/feature%252Fdemo/"},
                        {"name": "main", "url": "__BASE__/job/folder/job/main/"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/feature%252Fdemo/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 12,
                      "displayName": "#12",
                      "fullDisplayName": "feature/demo #12",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/folder/job/feature%252Fdemo/12/",
                      "timestamp": 1,
                      "duration": 2,
                      "artifacts": []
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/feature%2Fdemo/12/wfapi/describe" to jsonResponse("""{"stages":[]}"""),
                "/job/folder/job/feature%2Fdemo/12/wfapi/artifacts" to jsonResponse("[]"),
                "/job/folder/job/feature%2Fdemo/lastBuild/api/json" to jsonResponse(
                    """
                    {
                      "number": 12,
                      "displayName": "#12",
                      "fullDisplayName": "feature/demo #12",
                      "result": "SUCCESS",
                      "building": false,
                      "url": "__BASE__/job/folder/job/feature%252Fdemo/12/",
                      "timestamp": 1,
                      "duration": 2,
                      "artifacts": []
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val summary = JenkinsStatusClient().fetchLatestBuild(baseUrl, "folder", "user", "token", preferredBranch = "feature/demo")

        assertEquals(12, summary.number)
        assertContains(summary.url, "feature%252Fdemo")
    }

    @Test
    fun `fetchJobTree follows nested jobs skips bad children and auto selects preferred branch`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/projector/api/json" to jsonResponse(
                    """
                    {
                      "name": "projector",
                      "displayName": "",
                      "url": "",
                      "color": "",
                      "buildable": false,
                      "jobs": [
                        {"name": "folder", "displayName": "Folder", "url": "__BASE__/job/projector/job/folder/", "color": ""},
                        {"name": "broken", "displayName": "Broken", "url": "__BASE__/job/projector/job/missing/", "color": ""}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/folder/api/json" to jsonResponse(
                    """
                    {
                      "name": "folder",
                      "displayName": "Folder",
                      "url": "__BASE__/job/projector/job/folder/",
                      "color": "",
                      "buildable": false,
                      "jobs": [
                        {"name": "self", "displayName": "Self", "url": "__BASE__/job/projector/job/folder/", "color": ""},
                        {"name": "feature%252Fdemo", "displayName": "Feature Demo", "url": "__BASE__/job/projector/job/folder/job/feature%252Fdemo/", "color": "blue"},
                        {"name": "main", "displayName": "", "url": "__BASE__/job/projector/job/folder/job/main/", "color": "blue"},
                        {"name": "skip", "displayName": "Skip", "url": "", "color": ""}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/folder/job/feature%2Fdemo/api/json" to jsonResponse(
                    """
                    {
                      "name": "feature%252Fdemo",
                      "displayName": "Feature Demo",
                      "url": "__BASE__/job/projector/job/folder/job/feature%252Fdemo/",
                      "color": "blue_anime",
                      "buildable": true,
                      "lastBuild": {"number": 21, "result": "SUCCESS", "building": false},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/folder/job/main/api/json" to jsonResponse(
                    """
                    {
                      "name": "main",
                      "displayName": "",
                      "url": "",
                      "color": "blue",
                      "buildable": true,
                      "lastBuild": null,
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
                "/job/projector/job/missing/api/json" to textResponse(404, "missing"),
            ),
        )

        val tree = JenkinsStatusClient().fetchJobTree(baseUrl, "job/projector", "user", "token", preferredBranch = "feature/demo")

        assertEquals("projector", tree.root.name)
        assertEquals("$baseUrl/job/projector", tree.root.url)
        val folder = tree.root.children.single { it.name == "Folder" }
        assertTrue(folder.children.any { it.name == "folder" })
        assertTrue(folder.children.any { it.name == "Feature Demo" })
        assertEquals("Feature Demo", tree.autoSelected?.name)
        assertTrue(tree.autoSelected?.lastBuildBuilding == false)
    }

    @Test
    fun `downloadArtifacts supports unknown sizes and cleans existing cache`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/artifact/report.txt" to textResponse(200, "report-body"),
            ),
        )
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-cache")
        val client = JenkinsStatusClient()
        val summary = JenkinsBuildSummary(
            number = 5,
            displayName = "#5",
            fullDisplayName = "job #5",
            result = "SUCCESS",
            building = false,
            url = "$baseUrl/job/test/5/",
            timestampMillis = 0,
            durationMillis = 0,
            stages = emptyList(),
            artifacts = listOf(JenkinsArtifact("report.txt", "reports/report.txt", "$baseUrl/artifact/report.txt", null)),
        )

        val first = client.downloadArtifacts(summary, "user", "token", cacheRoot)
        first.resolve("stale.txt").writeText("stale")

        val second = client.downloadArtifacts(summary, "user", "token", cacheRoot)

        assertEquals(first, second)
        assertFalse(second.resolve("stale.txt").exists())
        assertEquals("report-body", second.resolve("reports/report.txt").readText())
    }

    @Test
    fun `downloadArtifacts rejects excessive counts size limits escaping paths and HTTP errors`() {
        val client = JenkinsStatusClient()
        val cacheRoot = Files.createTempDirectory("jenkins-artifacts-errors")
        val summary = JenkinsBuildSummary(
            number = 1,
            displayName = "#1",
            fullDisplayName = "#1",
            result = "SUCCESS",
            building = false,
            url = "https://jenkins/job/test/1/",
            timestampMillis = 0,
            durationMillis = 0,
            stages = emptyList(),
            artifacts = List(501) { index ->
                JenkinsArtifact("a$index.txt", "a$index.txt", "https://jenkins/artifact/a$index.txt", 1)
            },
        )
        assertFailsWith<IllegalStateException> {
            client.downloadArtifacts(summary, "user", "token", cacheRoot)
        }

        val pathEscape = summary.copy(artifacts = listOf(JenkinsArtifact("evil.txt", "../evil.txt", "https://jenkins/artifact/evil.txt", 1)))
        assertFailsWith<IllegalStateException> {
            client.downloadArtifacts(pathEscape, "user", "token", cacheRoot)
        }

        val sizeLimit = summary.copy(
            artifacts = listOf(JenkinsArtifact("huge.txt", "huge.txt", "https://jenkins/artifact/huge.txt", 101L * 1024L * 1024L)),
        )
        assertFailsWith<IllegalStateException> {
            client.downloadArtifacts(sizeLimit, "user", "token", cacheRoot)
        }

        val errorBase = startServer(routes = mapOf("/artifact/missing.txt" to textResponse(404, "missing")))
        val httpError = summary.copy(
            url = "$errorBase/job/test/1/",
            artifacts = listOf(JenkinsArtifact("missing.txt", "missing.txt", "$errorBase/artifact/missing.txt", 1)),
        )
        val error = assertFailsWith<JenkinsHttpException> {
            client.downloadArtifacts(httpError, "user", "token", cacheRoot)
        }
        assertContains(error.message.orEmpty(), "job was not found")
    }

    @Test
    fun `helper methods normalize paths urls headers diagnostics and retry responses`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/retry/whoAmI/api/json" to textResponse(200, "{}"),
                "/retry/job/project/lastBuild/api/json" to countingRoute(
                    first = textResponse(503, "temporary"),
                    then = jsonResponse(
                        """
                        {
                          "number": 8,
                          "displayName": "#8",
                          "fullDisplayName": "retry #8",
                          "result": "SUCCESS",
                          "building": false,
                          "url": "__BASE__/retry/job/project/8/",
                          "timestamp": 1,
                          "duration": 2,
                          "artifacts": []
                        }
                        """.trimIndent(),
                    ),
                ),
                "/retry/job/project/8/wfapi/describe" to jsonResponse("""{"stages":["ignored",{"id":"1","name":"Build","status":"SUCCESS","durationMillis":9,"_links":{"self":{"href":"/absolute-stage"}}}]}"""),
                "/retry/job/project/8/wfapi/artifacts" to jsonResponse("""["ignored",{"name":"report.html","path":"reports/report.html","url":"relative/report.html","size":42}]"""),
                "/absolute-stage" to textResponse(200, "unused"),
                "/retry/job/project/api/json" to textResponse(401, """{"status":"401"}""", mapOf("WWW-Authenticate" to "Basic realm=Jenkins")),
                "/retry/api/json" to textResponse(401, """{"status":"401"}""", mapOf("WWW-Authenticate" to "Basic realm=Jenkins")),
                "/retry/job/projector/api/json" to textResponse(401, """{"status":"401"}""", mapOf("WWW-Authenticate" to "Basic realm=Jenkins")),
            ),
        )
        val client = JenkinsStatusClient()

        val summary = client.fetchLatestBuildForJobUrl("$baseUrl/retry/job/project", "user", "token")
        assertEquals(1, summary.stages.size)
        assertEquals("$baseUrl/absolute-stage", summary.stages.single().url)
        assertEquals(1, summary.artifacts.size)
        assertEquals("$baseUrl/retry/job/project/8/relative/report.html", summary.artifacts.single().url)

        val requestWithAuth = client.invokePrivate<HttpRequest>("request", String::class.java to "$baseUrl/api/json", String::class.java to "user", String::class.java to "token")
        assertTrue(requestWithAuth.headers().firstValue("Authorization").orElse("").startsWith("Basic "))

        val requestWithoutAuth = client.invokePrivate<HttpRequest>("request", String::class.java to "$baseUrl/api/json", String::class.java to "", String::class.java to "")
        assertFalse(requestWithoutAuth.headers().firstValue("Authorization").isPresent)

        assertEquals("", client.invokePrivate("normalizeJobPath", String::class.java to "  "))
        assertEquals("job/folder/job/feature/job/demo", client.invokePrivate("normalizeJobPath", String::class.java to "folder/feature/demo"))
        assertEquals("job/folder/job/main", client.invokePrivate("normalizeJobPath", String::class.java to "job/folder/job/main"))
        assertEquals("$baseUrl", client.invokePrivate("buildJenkinsUrl", String::class.java to baseUrl, String::class.java to ""))
        assertEquals("$baseUrl/job/folder", client.invokePrivate("buildJenkinsUrl", String::class.java to baseUrl, String::class.java to "folder"))
        assertEquals("https://external.example/path", client.invokePrivate("resolveJenkinsUrl", String::class.java to "$baseUrl/job/p/1/", String::class.java to "https://external.example/path"))
        assertEquals("$baseUrl/root/path", client.invokePrivate("resolveJenkinsUrl", String::class.java to "$baseUrl/job/p/1/", String::class.java to "/root/path"))
        assertEquals("$baseUrl/job/p/1/relative/path", client.invokePrivate("resolveJenkinsUrl", String::class.java to "$baseUrl/job/p/1/", String::class.java to "relative/path"))
        assertEquals("$baseUrl/retry", client.invokePrivate("rootUrl", String::class.java to "$baseUrl/retry/job/project/8/"))
        assertEquals("a%20b", client.invokePrivate("encodePathSegment", String::class.java to "a b"))
        assertEquals("feature%252Fdemo", client.invokePrivate("encodeJobName", String::class.java to "feature/demo"))
        assertEquals(client.invokePrivate<String>("stableId", String::class.java to "abc"), client.invokePrivate("stableId", String::class.java to "abc"))
        assertNotEquals(client.invokePrivate<String>("stableId", String::class.java to "abc"), client.invokePrivate("stableId", String::class.java to "xyz"))
        assertEquals("spaced out body", client.invokePrivate("compactBodyPreview", String::class.java to " spaced \n out \t body "))

        val diagnostics = client.diagnose("$baseUrl/retry", "job/projector", "user", "token", null)
        assertEquals(3, diagnostics.size)
        assertEquals(200, diagnostics.first().statusCode)
        assertEquals(401, diagnostics[1].statusCode)
        assertEquals("Basic realm=Jenkins", diagnostics[1].wwwAuthenticate)
        assertTrue(diagnostics[1].authHeaderSent)

        val diagnosticError = client.invokePrivate<JenkinsDiagnosticStep>("diagnosticRequest", String::class.java to "broken", String::class.java to "http://127.0.0.1:1/broken", String::class.java to "user", String::class.java to "token")
        assertFalse(diagnosticError.ok)
        assertNotNull(diagnosticError.error)
    }

    @Test
    fun `auth helper methods respect fallback settings request modes and redirects`() {
        val settings = CiStatusSettings().apply {
            experimentalKeycloakInteractiveFallback = true
            jenkinsBaseUrl = "https://jenkins.example.org"
        }
        lateinit var service: KeycloakSessionService
        val project = proxyProject(settings) { requested ->
            when (requested) {
                CiStatusSettings::class.java -> settings
                KeycloakSessionService::class.java -> service
                else -> null
            }
        }
        service = KeycloakSessionService(project)
        val client = JenkinsStatusClient(project)
        val unauthorized = response(status = 401)
        val redirect = response(status = 302, headers = mapOf("Location" to "/securityRealm/commenceLogin"))
        val ok = response(status = 200)

        assertTrue(client.invokePrivate("shouldRetry", HttpResponse::class.java to unauthorized))
        assertTrue(client.invokePrivate("shouldRetry", HttpResponse::class.java to redirect))
        assertFalse(client.invokePrivate("shouldRetry", HttpResponse::class.java to ok))

        assertTrue(client.invokePrivate("isSecurityRedirect", HttpResponse::class.java to response(status = 302, headers = mapOf("Location" to "/keycloak/login"))))
        assertFalse(client.invokePrivate("isSecurityRedirect", HttpResponse::class.java to response(status = 302, headers = mapOf("Location" to "/safe"))))

        assertTrue(client.invokePrivate("shouldTriggerInteractiveFallback", HttpResponse::class.java to unauthorized, String::class.java to "https://jenkins.example.org/job/p"))
        assertFalse(client.invokePrivate("shouldTriggerInteractiveFallback", HttpResponse::class.java to ok, String::class.java to "https://jenkins.example.org/job/p"))
        assertFalse(client.invokePrivate("shouldTriggerInteractiveFallback", HttpResponse::class.java to unauthorized, String::class.java to "https://other.example.org/job/p"))

        settings.experimentalKeycloakInteractiveFallback = false
        assertFalse(client.invokePrivate("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p"))
        settings.experimentalKeycloakInteractiveFallback = true

        withTestPasswordSafe {
            assertFailsWith<IllegalStateException> {
                JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
                    client.invokePrivate<Boolean>("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p")
                }
            }
            assertFailsWith<IllegalStateException> {
                JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) {
                    client.invokePrivate<Boolean>("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p")
                }
            }
        }

        assertTrue(client.invokePrivate("isTransientNetworkError", IOException::class.java to IOException("GOAWAY")))
        assertTrue(client.invokePrivate("isTransientNetworkError", IOException::class.java to IOException("stream closed")))
        assertTrue(client.invokePrivate("isTransientNetworkError", IOException::class.java to IOException("connection reset")))
        assertFalse(client.invokePrivate("isTransientNetworkError", IOException::class.java to IOException("permission denied")))
    }

    @Test
    fun `manual request mode preserves http errors instead of throwing authentication expired`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/project/lastBuild/api/json" to textResponse(401, """{"status":"401"}"""),
            ),
        )

        val error = assertFailsWith<JenkinsHttpException> {
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
                JenkinsStatusClient().fetchLatestBuildForJobUrl("$baseUrl/job/project", "user", "token")
            }
        }

        assertContains(error.message.orEmpty(), "authentication failed")
    }

    @Test
    fun `json helper extensions return safe defaults`() {
        val json = JsonParser.parseString(
            """
            {
              "text": "value",
              "nullText": null,
              "intValue": 7,
              "nullInt": null,
              "longValue": 9,
              "nullLong": null,
              "boolValue": true,
              "nullBool": null,
              "nested": {"ok": 1},
              "nullObj": null,
              "items": [1],
              "nullItems": null
            }
            """.trimIndent(),
        ).asJsonObject

        assertEquals("value", json.invokeExtension<String>("string", "text"))
        assertEquals("", json.invokeExtension<String>("string", "missing"))
        assertNull(json.invokeExtension<String?>("nullableString", "nullText"))
        assertEquals(7, json.invokeExtension<Int>("int", "intValue"))
        assertEquals(0, json.invokeExtension<Int>("int", "missing"))
        assertNull(json.invokeExtension<Int?>("nullableInt", "nullInt"))
        assertEquals(9L, json.invokeExtension<Long>("long", "longValue"))
        assertEquals(0L, json.invokeExtension<Long>("long", "missing"))
        assertNull(json.invokeExtension<Long?>("nullableLong", "nullLong"))
        assertTrue(json.invokeExtension<Boolean>("boolean", "boolValue"))
        assertFalse(json.invokeExtension<Boolean>("boolean", "missing"))
        assertNotNull(json.invokeExtension<Any?>("obj", "nested"))
        assertNull(json.invokeExtension<Any?>("obj", "nullObj"))
        assertEquals(1, json.invokeExtension<List<*>>("array", "items").size)
        assertTrue(json.invokeExtension<List<*>>("array", "missingItems").isEmpty())
        assertNotNull(JsonParser.parseString("""{"ok":1}""").invokeElementExtension<Any?>("asJsonObjectOrNull"))
        assertNull(JsonParser.parseString("1").invokeElementExtension<Any?>("asJsonObjectOrNull"))
    }

    @Test
    fun `job selection helpers cover empty trees main fallback and first fallback`() {
        val client = JenkinsStatusClient()
        val emptyRoot = JenkinsJobNode("root", "https://jenkins/root", "", false, null, null, false, emptyList())
        assertNull(client.invokePrivate<JenkinsJobNode?>("findAutoSelected", JenkinsJobNode::class.java to emptyRoot, String::class.java to null))

        val main = JenkinsJobNode("main", "https://jenkins/main", "blue", true, 1, "SUCCESS", false, emptyList())
        val feature = JenkinsJobNode("feature", "https://jenkins/feature", "blue", true, 2, "SUCCESS", false, emptyList())
        val root = JenkinsJobNode("root", "https://jenkins/root", "", false, null, null, false, listOf(feature, main))
        assertEquals("main", client.invokePrivate<JenkinsJobNode?>("findAutoSelected", JenkinsJobNode::class.java to root, String::class.java to null)?.name)

        val firstOnly = JenkinsJobNode("first", "https://jenkins/first", "blue", true, 3, "SUCCESS", false, emptyList())
        val noMainRoot = JenkinsJobNode("root", "https://jenkins/root", "", false, null, null, false, listOf(firstOnly))
        assertEquals("first", client.invokePrivate<JenkinsJobNode?>("findAutoSelected", JenkinsJobNode::class.java to noMainRoot, String::class.java to "missing")?.name)
    }

    @Test
    fun `resolveBuildableJobUrl returns configured job for buildable and empty-folder cases`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/buildable/api/json" to jsonResponse("""{"buildable":true,"lastBuild":null,"jobs":[]}"""),
                "/job/empty/api/json" to jsonResponse("""{"buildable":false,"lastBuild":null,"jobs":[]}"""),
            ),
        )
        val client = JenkinsStatusClient()

        assertEquals(
            "$baseUrl/job/buildable",
            client.invokePrivate<String>("resolveBuildableJobUrl", String::class.java to "$baseUrl/job/buildable", String::class.java to "user", String::class.java to "token", String::class.java to null),
        )
        assertEquals(
            "$baseUrl/job/empty",
            client.invokePrivate<String>("resolveBuildableJobUrl", String::class.java to "$baseUrl/job/empty", String::class.java to "user", String::class.java to "token", String::class.java to null),
        )
    }

    @Test
    fun `request mode and data class helpers expose expected behavior`() {
        val previous = JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) { "ok" }
        }
        assertEquals("ok", previous)

        val running = JenkinsBuildSummary(1, "#1", "#1", null, true, "https://jenkins/job/1/", 0, 0, emptyList(), emptyList())
        val unknown = running.copy(building = false)
        assertEquals("RUNNING", running.state)
        assertEquals("UNKNOWN", unknown.state)

        assertTrue(JenkinsArtifact("report.txt", "reports/index.html", "https://jenkins/report.txt", null).isHtml)
        assertTrue(JenkinsJobNode("job", "https://jenkins/job", "", true, null, null, false, emptyList()).isBuildJob)
        assertTrue(JenkinsJobNode("job", "https://jenkins/job", "", false, 1, "SUCCESS", false, listOf(JenkinsJobNode("child", "https://jenkins/child", "", false, null, null, false, emptyList()))).isBuildJob)
        assertTrue(JenkinsDiagnosticStep("ok", "https://jenkins", 200, null, null, "application/json", true, "{}", null).ok)
        assertFalse(JenkinsDiagnosticStep("bad", "https://jenkins", 200, null, null, "text/html", true, "", null).ok)
        assertFalse(JenkinsDiagnosticStep("err", "https://jenkins", null, null, null, null, false, "", "boom").ok)
    }

    @Test
    fun `downloadArtifacts enforces preview limit even when artifact size metadata is missing`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/artifact/big.bin" to textResponse(200, "1234567890"),
            ),
        )
        val summary = JenkinsBuildSummary(
            number = 1,
            displayName = "#1",
            fullDisplayName = "#1",
            result = "SUCCESS",
            building = false,
            url = "$baseUrl/job/projector/1/",
            timestampMillis = 0,
            durationMillis = 0,
            stages = emptyList(),
            artifacts = listOf(JenkinsArtifact("big.bin", "big.bin", "$baseUrl/artifact/big.bin", null)),
        )

        val error = assertFailsWith<IllegalStateException> {
            JenkinsStatusClient(maxArtifactBytes = 5).downloadArtifacts(
                summary = summary,
                username = "user",
                token = "token",
                cacheRoot = Files.createTempDirectory("jenkins-unknown-size-limit"),
            )
        }

        assertContains(error.message.orEmpty(), "preview limit")
    }

    @Test
    fun `resolveBuildableJobUrl can return recursively auto selected build job URL`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/folder/api/json" to jsonResponse(
                    """
                    {
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "alpha", "url": "__BASE__/job/folder/job/alpha/"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/alpha/api/json" to jsonResponse(
                    """
                    {
                      "name": "alpha",
                      "displayName": "alpha",
                      "url": "__BASE__/job/folder/job/alpha/",
                      "color": "",
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "develop", "displayName": "develop", "url": "__BASE__/job/folder/job/alpha/job/develop/", "color": "blue"}
                      ]
                    }
                    """.trimIndent(),
                ),
                "/job/folder/job/alpha/job/develop/api/json" to jsonResponse(
                    """
                    {
                      "name": "develop",
                      "displayName": "develop",
                      "url": "__BASE__/job/folder/job/alpha/job/develop/",
                      "color": "blue",
                      "buildable": true,
                      "lastBuild": {"number": 3, "result": "SUCCESS", "building": false},
                      "jobs": []
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val client = JenkinsStatusClient()

        val resolved = client.invokePrivate<String>(
            "resolveBuildableJobUrl",
            String::class.java to "$baseUrl/job/folder",
            String::class.java to "user",
            String::class.java to "token",
            String::class.java to "develop",
        )

        assertEquals("$baseUrl/job/folder/job/alpha/job/develop", resolved)
    }

    @Test
    fun `fetchJobNode stops exploring children when max depth is reached`() {
        val baseUrl = startServer(
            routes = mapOf(
                "/job/root/api/json" to jsonResponse(
                    """
                    {
                      "name": "root",
                      "displayName": "root",
                      "url": "__BASE__/job/root/",
                      "color": "",
                      "buildable": false,
                      "lastBuild": null,
                      "jobs": [
                        {"name": "child", "displayName": "child", "url": "__BASE__/job/root/job/child/", "color": "blue"}
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val client = JenkinsStatusClient()

        val node = client.invokePrivate<JenkinsJobNode>(
            "fetchJobNode",
            String::class.java to "$baseUrl/job/root",
            String::class.java to "user",
            String::class.java to "token",
            Int::class.javaPrimitiveType!! to 5,
            Int::class.javaPrimitiveType!! to 5,
            MutableSet::class.java to mutableSetOf<String>(),
        )

        assertTrue(node.children.isEmpty())
    }

    @Test
    fun `sendWithRetry returns retried response when auth recovery override succeeds`() {
        val settings = CiStatusSettings().apply {
            experimentalKeycloakInteractiveFallback = true
            jenkinsBaseUrl = "https://jenkins.example.org"
        }
        val project = proxyProject(settings) { null }
        var call = 0
        val client = JenkinsStatusClient(
            project = project,
            authenticationRecoveryOverride = { true },
            sleepOverride = {},
            httpClientOverride = FakeHttpClient(
                sendHandler = { request ->
                    if (request.uri().path.endsWith("/lastBuild/api/json")) {
                        call += 1
                        if (call == 1) {
                            httpResponse<String>(401, """{"status":"401"}""")
                        } else {
                            httpResponse(200, """{"number":1,"displayName":"#1","fullDisplayName":"#1","result":"SUCCESS","building":false,"url":"https://jenkins.example.org/job/p/1/","timestamp":1,"duration":1,"artifacts":[]}""")
                        }
                    } else if (request.uri().path.endsWith("/wfapi/describe")) {
                        httpResponse(200, """{"stages":[]}""")
                    } else {
                        httpResponse(200, "[]")
                    }
                },
            ),
        )

        val summary = client.fetchLatestBuildForJobUrl("https://jenkins.example.org/job/p", "user", "token")
        assertEquals(1, summary.number)
        assertTrue(call >= 2)
    }

    @Test
    fun `sendWithRetry throws auth expired when auth recovery override fails in background mode`() {
        val settings = CiStatusSettings().apply {
            experimentalKeycloakInteractiveFallback = true
            jenkinsBaseUrl = "https://jenkins.example.org"
        }
        val project = proxyProject(settings) { null }
        val client = JenkinsStatusClient(
            project = project,
            authenticationRecoveryOverride = { false },
            sleepOverride = {},
            httpClientOverride = FakeHttpClient(sendHandler = { httpResponse(401, """{"status":"401"}""") }),
        )

        assertFailsWith<JenkinsAuthenticationExpiredException> {
            client.fetchLatestBuildForJobUrl("https://jenkins.example.org/job/p", "user", "token")
        }
    }

    @Test
    fun `sendWithRetry retries transient IOException and eventually succeeds`() {
        var sleepCalls = 0
        var calls = 0
        val client = JenkinsStatusClient(
            sleepOverride = { sleepCalls += 1 },
            httpClientOverride = FakeHttpClient(
                sendHandler = { request ->
                    if (request.uri().path.endsWith("/lastBuild/api/json")) {
                        calls += 1
                        if (calls < 3) {
                            throw IOException("stream reset by peer")
                        }
                        httpResponse(
                            200,
                            """{"number":2,"displayName":"#2","fullDisplayName":"#2","result":"SUCCESS","building":false,"url":"https://jenkins.example.org/job/p/2/","timestamp":2,"duration":2,"artifacts":[]}""",
                        )
                    } else if (request.uri().path.endsWith("/wfapi/describe")) {
                        httpResponse(200, """{"stages":[]}""")
                    } else {
                        httpResponse(200, "[]")
                    }
                },
            ),
        )

        val summary = client.fetchLatestBuildForJobUrl("https://jenkins.example.org/job/p", "user", "token")

        assertEquals(2, summary.number)
        assertEquals(2, sleepCalls)
    }

    @Test
    fun `recoverAuthentication returns true when auto login override reports recovery`() {
        val settings = CiStatusSettings().apply {
            experimentalKeycloakInteractiveFallback = true
            jenkinsBaseUrl = "https://jenkins.example.org"
        }
        lateinit var service: KeycloakSessionService
        val project = proxyProject(settings) { requested ->
            when (requested) {
                KeycloakSessionService::class.java -> service
                else -> null
            }
        }
        service = KeycloakSessionService(project)
        val client = JenkinsStatusClient(
            project = project,
            autoLoginAttemptOverride = { _, _ -> true },
            interactiveLoginOverride = { _, _ -> false },
        )

        val recovered = client.invokePrivate<Boolean>("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p")
        assertTrue(recovered)
    }

    @Test
    fun `recoverAuthentication covers manual and background fallback branches`() {
        val settings = CiStatusSettings().apply {
            experimentalKeycloakInteractiveFallback = true
            jenkinsBaseUrl = "https://jenkins.example.org"
        }
        lateinit var service: KeycloakSessionService
        val project = proxyProject(settings) { requested ->
            when (requested) {
                KeycloakSessionService::class.java -> service
                else -> null
            }
        }
        service = KeycloakSessionService(project)
        val client = JenkinsStatusClient(
            project = project,
            autoLoginAttemptOverride = { _, _ -> false },
            interactiveLoginOverride = { _, _ -> false },
        )

        val manual = JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
            client.invokePrivate<Boolean>("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p")
        }
        val background = JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) {
            client.invokePrivate<Boolean>("recoverAuthentication", String::class.java to "https://jenkins.example.org/job/p")
        }

        assertFalse(manual)
        assertFalse(background)
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

    private fun textResponse(status: Int, body: String, headers: Map<String, String> = emptyMap()): (HttpExchange, String) -> Unit = { exchange, _ ->
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun countingRoute(
        first: (HttpExchange, String) -> Unit,
        then: (HttpExchange, String) -> Unit,
    ): (HttpExchange, String) -> Unit {
        var count = 0
        return { exchange, baseUrl ->
            count += 1
            if (count == 1) first(exchange, baseUrl) else then(exchange, baseUrl)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> JenkinsStatusClient.invokePrivate(name: String, vararg args: Pair<Class<*>, Any?>): T {
        val method = JenkinsStatusClient::class.java.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
        method.isAccessible = true
        return try {
            method.invoke(this, *args.map { it.second }.toTypedArray()) as T
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw (error.targetException ?: error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> com.google.gson.JsonObject.invokeExtension(name: String, key: String): T {
        val method = Class.forName("com.damorosodaragona.jenkinsnotifier.JenkinsStatusClientKt").getDeclaredMethod(name, com.google.gson.JsonObject::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, this, key) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> com.google.gson.JsonElement.invokeElementExtension(name: String): T {
        val method = Class.forName("com.damorosodaragona.jenkinsnotifier.JenkinsStatusClientKt").getDeclaredMethod(name, com.google.gson.JsonElement::class.java)
        method.isAccessible = true
        return method.invoke(null, this) as T
    }

    private fun response(status: Int, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val httpHeaders = HttpHeaders.of(headers.mapValues { listOf(it.value) }) { _, _ -> true }
        return Proxy.newProxyInstance(
            HttpResponse::class.java.classLoader,
            arrayOf(HttpResponse::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "statusCode" -> status
                "headers" -> httpHeaders
                "body" -> ""
                "request" -> null
                "previousResponse" -> java.util.Optional.empty<Any>()
                "sslSession" -> java.util.Optional.empty<Any>()
                "uri" -> URI.create("https://jenkins.example.org/job/test")
                "version" -> java.net.http.HttpClient.Version.HTTP_1_1
                "toString" -> "Response($status)"
                else -> null
            }
        } as HttpResponse<String>
    }

    private fun proxyProject(settings: CiStatusSettings, resolver: (Class<*>) -> Any?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getService" -> {
                    val requested = args?.firstOrNull() as? Class<*>
                    if (requested == CiStatusSettings::class.java) settings else requested?.let(resolver)
                }
                "getName" -> "test-project"
                "isDisposed" -> false
                "isOpen" -> true
                "toString" -> "Project(test-project)"
                else -> null
            }
        } as Project
    }

    private fun <T> httpResponse(status: Int, body: T, headers: Map<String, String> = emptyMap()): HttpResponse<T> {
        val httpHeaders = HttpHeaders.of(headers.mapValues { listOf(it.value) }) { _, _ -> true }
        return Proxy.newProxyInstance(
            HttpResponse::class.java.classLoader,
            arrayOf(HttpResponse::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "statusCode" -> status
                "headers" -> httpHeaders
                "body" -> body
                "request" -> null
                "previousResponse" -> Optional.empty<Any>()
                "sslSession" -> Optional.empty<Any>()
                "uri" -> URI.create("https://jenkins.example.org/job/test")
                "version" -> HttpClient.Version.HTTP_1_1
                else -> null
            }
        } as HttpResponse<T>
    }

    private class FakeHttpClient(
        internal var sendHandler: (HttpRequest) -> HttpResponse<String>,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            return sendHandler(request) as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException("Not needed in tests")
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException("Not needed in tests")
        }
    }
}
