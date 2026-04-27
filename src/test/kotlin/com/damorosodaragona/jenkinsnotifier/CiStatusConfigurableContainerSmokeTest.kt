package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@Tag("smoke")
class CiStatusConfigurableContainerSmokeTest {

    @Test
    fun `settings form is saved restored and Test Jenkins connection works against controlled Jenkins`() = withTestPasswordSafe {
        smokeLogClear()
        smokeLog("Smoke test starting")

        assumeTrue(
            env("JCN_CONTAINER_SMOKE_ENABLED").equals("true", ignoreCase = true),
            "Skipping controlled Jenkins smoke test. Set JCN_CONTAINER_SMOKE_ENABLED=true to enable it.",
        )

        assumeDockerAvailableOrStarted()

        val image = ImageFromDockerfile("jenkins-notifier-smoke:local", false)
            .withFileFromPath(
                ".",
                Paths.get("src/test/resources/jenkins-smoke"),
            )
        smokeLog("Using controlled Jenkins Docker image built from src/test/resources/jenkins-smoke")

        val container = JenkinsSmokeContainer(image)
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/login")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)),
            )

        try {
            smokeLog("Starting Jenkins container")
            container.start()

            val baseUrl = "http://${container.host}:${container.getMappedPort(8080)}"
            val username = "robot"
            val token = "jenkins-token"
            val jobPath = "job/smoke-job"

            smokeLog("Jenkins container started: baseUrl=$baseUrl containerId=${container.containerId}")

            waitForAuthenticatedJenkins(baseUrl, username, token)
            waitForJobRoot(baseUrl, username, token)
            triggerSeedBuild(baseUrl, username, token)
            waitForSeedBuild(baseUrl, username, token)

            val settings = CiStatusSettings()
            val project = smokeProjectWithSettings(settings)

            smokeLog("Creating settings configurable and saving Jenkins fields")
            val saveConfigurable = smokeConfigurable(project)
            saveConfigurable.createComponent()
            saveConfigurable.smokeComboBox("provider").selectedItem = "jenkins"
            saveConfigurable.smokeTextField("jenkinsBaseUrl").text = " $baseUrl/ "
            saveConfigurable.smokeTextField("jenkinsJobPath").text = " /$jobPath/ "
            saveConfigurable.smokeTextField("jenkinsUsername").text = " $username "
            saveConfigurable.smokePasswordField("jenkinsToken").text = token

            assertTrue(saveConfigurable.isModified())
            saveConfigurable.apply()

            assertEquals("jenkins", settings.provider)
            assertEquals(baseUrl, settings.jenkinsBaseUrl)
            assertEquals(jobPath, settings.jenkinsJobPath)
            assertEquals(username, settings.jenkinsUsername)
            assertEquals(token, settings.getJenkinsToken())
            smokeLog("Settings saved correctly")

            val validUi = ContainerSmokeRecordingJenkinsDiagnosticsUi()
            val reloadedConfigurable = smokeConfigurable(project = project, diagnosticsUi = validUi)
            reloadedConfigurable.createComponent()

            assertEquals(baseUrl, reloadedConfigurable.smokeTextField("jenkinsBaseUrl").text)
            assertEquals(jobPath, reloadedConfigurable.smokeTextField("jenkinsJobPath").text)
            assertEquals(username, reloadedConfigurable.smokeTextField("jenkinsUsername").text)
            assertEquals(token, String(reloadedConfigurable.smokePasswordField("jenkinsToken").password))
            assertFalse(reloadedConfigurable.isModified())
            smokeLog("Settings reloaded correctly")

            smokeLog("Clicking Test Jenkins connection with valid credentials")
            reloadedConfigurable.smokeButton("testJenkinsButton").doClick()

            assertEquals(emptyList(), validUi.errors)
            assertEquals(1, validUi.reports.size)
            validUi.reports.single().assertAllDiagnosticsOk()
            smokeLog("Valid Jenkins diagnostics reported OK")

            val wrongTokenUi = ContainerSmokeRecordingJenkinsDiagnosticsUi()
            val wrongTokenConfigurable = smokeConfigurable(project = project, diagnosticsUi = wrongTokenUi)
            wrongTokenConfigurable.createComponent()
            wrongTokenConfigurable.smokeTextField("jenkinsBaseUrl").text = baseUrl
            wrongTokenConfigurable.smokeTextField("jenkinsJobPath").text = jobPath
            wrongTokenConfigurable.smokeTextField("jenkinsUsername").text = username
            wrongTokenConfigurable.smokePasswordField("jenkinsToken").text = "wrong-token"

            smokeLog("Clicking Test Jenkins connection with wrong token")
            wrongTokenConfigurable.smokeButton("testJenkinsButton").doClick()

            assertEquals(emptyList(), wrongTokenUi.errors)
            assertEquals(1, wrongTokenUi.reports.size)
            wrongTokenUi.reports.single().assertHasFailedDiagnostic()
            smokeLog("Wrong-token Jenkins diagnostics reported FAIL as expected")

            val wrongUrlUi = ContainerSmokeRecordingJenkinsDiagnosticsUi()
            val wrongUrlConfigurable = smokeConfigurable(project = project, diagnosticsUi = wrongUrlUi)
            wrongUrlConfigurable.createComponent()
            wrongUrlConfigurable.smokeTextField("jenkinsBaseUrl").text = "$baseUrl/__missing_jenkins_context__"
            wrongUrlConfigurable.smokeTextField("jenkinsJobPath").text = jobPath
            wrongUrlConfigurable.smokeTextField("jenkinsUsername").text = username
            wrongUrlConfigurable.smokePasswordField("jenkinsToken").text = token

            smokeLog("Clicking Test Jenkins connection with wrong base URL")
            wrongUrlConfigurable.smokeButton("testJenkinsButton").doClick()

            assertEquals(emptyList(), wrongUrlUi.errors)
            assertEquals(1, wrongUrlUi.reports.size)
            wrongUrlUi.reports.single().assertHasFailedDiagnostic()
            smokeLog("Wrong-URL Jenkins diagnostics reported FAIL as expected")
        } catch (error: Throwable) {
            val jenkinsLogs = runCatching { container.logs.takeLast(8000) }.getOrDefault("<container logs unavailable>")
            fail(
                buildString {
                    appendLine(error.message ?: error::class.java.name)
                    appendLine()
                    appendLine("Smoke debug log:")
                    appendLine(smokeLogDump())
                    appendLine()
                    appendLine("Recent Jenkins container logs:")
                    appendLine(jenkinsLogs)
                },
            )
        } finally {
            smokeLog("Stopping Jenkins container")
            runCatching { container.stop() }
        }
    }

    private fun smokeConfigurable(
        project: Project,
        diagnosticsUi: JenkinsDiagnosticsUi = ContainerSmokeRecordingJenkinsDiagnosticsUi(),
    ): CiStatusConfigurable {
        return CiStatusConfigurable(
            project = project,
            diagnosticsService = RealJenkinsDiagnosticsService(project),
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = ContainerSmokeSynchronousJenkinsDiagnosticsExecutor,
            branchProvider = JenkinsBranchProvider { null },
        )
    }
}

private fun waitForAuthenticatedJenkins(baseUrl: String, username: String, token: String) {
    smokeLog("Waiting for authenticated Jenkins API: $baseUrl/api/json")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    val deadline = Instant.now().plus(Duration.ofSeconds(90))
    var lastStatus: Int? = null
    var lastBody = ""

    while (Instant.now().isBefore(deadline)) {
        val response = runCatching {
            client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/api/json"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", basicAuth(username, token))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.onFailure { smokeLog("Authenticated Jenkins API request failed: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()

        if (response != null) {
            lastStatus = response.statusCode()
            lastBody = response.body().singleLinePreview()
            smokeLogHttp("GET", "$baseUrl/api/json", response)

            if (response.statusCode() in 200..299) {
                smokeLog("Authenticated Jenkins API is ready")
                return
            }
        }

        Thread.sleep(1000)
    }

    failWithSmokeLog("Controlled Jenkins did not become reachable with smoke user. Last status=$lastStatus body=$lastBody")
}

private fun waitForJobRoot(baseUrl: String, username: String, token: String) {
    smokeLog("Waiting for smoke-job root")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    val deadline = Instant.now().plus(Duration.ofSeconds(60))
    var lastStatus: Int? = null
    var lastBody = ""

    while (Instant.now().isBefore(deadline)) {
        val response = runCatching {
            client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/job/smoke-job/api/json"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", basicAuth(username, token))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.onFailure { smokeLog("smoke-job root request failed: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()

        if (response != null) {
            lastStatus = response.statusCode()
            lastBody = response.body().singleLinePreview()
            smokeLogHttp("GET", "$baseUrl/job/smoke-job/api/json", response)

            if (response.statusCode() in 200..299) {
                smokeLog("smoke-job root is ready")
                return
            }
        }

        Thread.sleep(1000)
    }

    failWithSmokeLog("Controlled Jenkins did not expose smoke-job root. Last status=$lastStatus body=$lastBody")
}

private fun triggerSeedBuild(baseUrl: String, username: String, token: String) {
    smokeLog("Triggering smoke-job build")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    val response = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/job/smoke-job/build?delay=0sec"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", basicAuth(username, token))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    smokeLogHttp("POST", "$baseUrl/job/smoke-job/build?delay=0sec", response)

    if (response.statusCode() !in listOf(200, 201, 302, 303)) {
        failWithSmokeLog("Controlled Jenkins did not accept smoke-job build trigger. Status=${response.statusCode()} body=${response.body().singleLinePreview()}")
    }
}

private fun waitForSeedBuild(baseUrl: String, username: String, token: String) {
    smokeLog("Waiting for smoke-job latest build")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    val deadline = Instant.now().plus(Duration.ofSeconds(90))
    var lastStatus: Int? = null
    var lastBody = ""

    while (Instant.now().isBefore(deadline)) {
        val response = runCatching {
            client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$baseUrl/job/smoke-job/lastBuild/api/json"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", basicAuth(username, token))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.onFailure { smokeLog("latest build request failed: ${it::class.java.simpleName}: ${it.message}") }
            .getOrNull()

        if (response != null) {
            lastStatus = response.statusCode()
            lastBody = response.body().singleLinePreview()
            smokeLogHttp("GET", "$baseUrl/job/smoke-job/lastBuild/api/json", response)

            if (response.statusCode() in 200..299) {
                smokeLog("smoke-job latest build is available")
                return
            }
        }

        Thread.sleep(1000)
    }

    failWithSmokeLog("Controlled Jenkins did not create smoke-job latest build. Last status=$lastStatus body=$lastBody")
}

private fun assumeDockerAvailableOrStarted() {
    smokeLog("Checking Docker availability")

    if (dockerInfoWorks()) {
        smokeLog("Docker is already running")
        return
    }

    if (!dockerCliExists()) {
        assumeTrue(
            false,
            "Skipping controlled Jenkins smoke test. Docker CLI is not installed or not available in PATH.",
        )
    }

    val started = tryStartDockerDesktopIfAvailable()

    if (started) {
        smokeLog("Docker Desktop start requested; waiting for Docker readiness")
        val available = waitForDocker(Duration.ofSeconds(90))
        assumeTrue(
            available,
            "Skipping controlled Jenkins smoke test. Docker Desktop was started but Docker did not become ready in time.",
        )
        smokeLog("Docker became ready")
        return
    }

    assumeTrue(
        false,
        "Skipping controlled Jenkins smoke test. Docker is not running. Start Docker Desktop and rerun the test.",
    )
}

private fun dockerCliExists(): Boolean =
    commandSucceeds(Duration.ofSeconds(5), "docker", "--version")

private fun dockerInfoWorks(): Boolean =
    commandSucceeds(Duration.ofSeconds(5), "docker", "info")

private fun waitForDocker(timeout: Duration): Boolean {
    val deadline = Instant.now().plus(timeout)

    while (Instant.now().isBefore(deadline)) {
        if (dockerInfoWorks()) {
            return true
        }

        Thread.sleep(2000)
    }

    return false
}

private fun tryStartDockerDesktopIfAvailable(): Boolean {
    val osName = System.getProperty("os.name").lowercase()

    if (!osName.contains("mac")) {
        smokeLog("Automatic Docker Desktop startup is only implemented for macOS")
        return false
    }

    val dockerApps = listOf(
        File("/Applications/Docker.app"),
        File(System.getProperty("user.home"), "Applications/Docker.app"),
    )

    if (dockerApps.none { it.exists() }) {
        smokeLog("Docker.app not found in standard macOS locations")
        return false
    }

    smokeLog("Starting Docker Desktop through macOS open command")
    return commandSucceeds(Duration.ofSeconds(10), "open", "-a", "Docker")
}

private fun commandSucceeds(timeout: Duration, vararg command: String): Boolean {
    return runCatching {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val completed = process.waitFor(timeout.seconds, TimeUnit.SECONDS)

        if (!completed) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}

private fun smokeProjectWithSettings(settings: CiStatusSettings): Project {
    return Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
            "getName" -> "container-smoke-test-project"
            "isDisposed" -> false
            "toString" -> "ContainerSmokeTestProject"
            else -> null
        }
    } as Project
}

@Suppress("UNCHECKED_CAST")
private fun <T> CiStatusConfigurable.smokePrivateField(name: String): T {
    val field = CiStatusConfigurable::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this) as T
}

private fun CiStatusConfigurable.smokeTextField(name: String): JBTextField =
    smokePrivateField(name)

private fun CiStatusConfigurable.smokePasswordField(name: String): JBPasswordField =
    smokePrivateField(name)

private fun CiStatusConfigurable.smokeComboBox(name: String): ComboBox<*> =
    smokePrivateField(name)

private fun CiStatusConfigurable.smokeButton(name: String): JButton =
    smokePrivateField(name)

private fun basicAuth(username: String, token: String): String {
    val encoded = Base64.getEncoder().encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8))
    return "Basic $encoded"
}

private fun String.assertAllDiagnosticsOk() {
    assertContains(this, "Jenkins diagnostics")
    assertContains(this, "Username configured: yes")
    assertContains(this, "Token present in Password Safe: yes")
    assertContains(this, "Authorization header sent: yes")
    assertTrue(
        lines().any { it.startsWith("OK ") },
        "Expected at least one successful diagnostic step. Report:\n$this\n\nSmoke debug log:\n${smokeLogDump()}",
    )
    assertFalse(
        lines().any { it.startsWith("FAIL ") },
        "Expected all diagnostic steps to be OK. Report:\n$this\n\nSmoke debug log:\n${smokeLogDump()}",
    )
}

private fun String.assertHasFailedDiagnostic() {
    assertContains(this, "Jenkins diagnostics")
    assertTrue(
        lines().any { it.startsWith("FAIL ") },
        "Expected at least one failed diagnostic step. Report:\n$this\n\nSmoke debug log:\n${smokeLogDump()}",
    )
}

private object ContainerSmokeSynchronousJenkinsDiagnosticsExecutor : JenkinsDiagnosticsExecutor {
    override fun executeOnBackgroundThread(action: () -> Unit) {
        action()
    }

    override fun invokeLater(action: () -> Unit) {
        action()
    }
}

private class ContainerSmokeRecordingJenkinsDiagnosticsUi : JenkinsDiagnosticsUi {
    var missingJenkinsUrlWarnings: Int = 0
        private set
    val reports = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun showMissingJenkinsUrl() {
        smokeLog("Diagnostics UI: missing Jenkins URL warning")
        missingJenkinsUrlWarnings++
    }

    override fun showReport(report: String) {
        smokeLog("Diagnostics UI: report received")
        smokeLog(report.lines().joinToString(separator = " | ") { it.take(160) })
        reports += report
    }

    override fun showError(message: String) {
        smokeLog("Diagnostics UI: error received: $message")
        errors += message
    }
}

private fun smokeLogHttp(method: String, url: String, response: HttpResponse<String>) {
    smokeLog(
        "$method $url -> status=${response.statusCode()} " +
            "location=${response.headers().firstValue("Location").orElse("-")} " +
            "contentType=${response.headers().firstValue("Content-Type").orElse("-")} " +
            "body=${response.body().singleLinePreview()}",
    )
}

private fun String.singleLinePreview(limit: Int = 300): String =
    replace(Regex("\\s+"), " ").take(limit)

private fun failWithSmokeLog(message: String): Nothing {
    fail(
        buildString {
            appendLine(message)
            appendLine()
            appendLine("Smoke debug log:")
            appendLine(smokeLogDump())
        },
    )
}

private val smokeLogLines = mutableListOf<String>()

private fun smokeLogClear() {
    synchronized(smokeLogLines) {
        smokeLogLines.clear()
    }
}

private fun smokeLog(message: String) {
    val line = "[container-smoke] ${Instant.now()} $message"
    synchronized(smokeLogLines) {
        smokeLogLines += line
    }
    println(line)
}

private fun smokeLogDump(): String =
    synchronized(smokeLogLines) {
        smokeLogLines.joinToString(separator = "\n")
    }
private class JenkinsSmokeContainer(
    image: ImageFromDockerfile,
) : GenericContainer<JenkinsSmokeContainer>(image)
private fun env(name: String): String = System.getenv(name).orEmpty()
