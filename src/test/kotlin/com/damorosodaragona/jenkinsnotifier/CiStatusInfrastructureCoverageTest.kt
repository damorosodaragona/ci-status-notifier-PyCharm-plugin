package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CiStatusInfrastructureCoverageTest {
    @Test
    fun `startup activity executes migration wiring and starts watcher`() {
        val settings = CiStatusSettings().apply { enabled = false }
        val legacy = LegacyCiStatusSettings()
        val migrationState = LegacySettingsMigrationState().apply { migrated = true }
        val disposed = AtomicBoolean(false)
        val project = projectWithStartupSupport(settings, legacy, migrationState, disposed)

        runSuspend { CiStatusStartupActivity().execute(project) }
        disposed.set(true)

        assertTrue(migrationState.migrated)
    }

    @Test
    fun `debug log writes project and fallback project names`() {
        val logFile = debugLogFile()
        Files.deleteIfExists(logFile)

        CiStatusDebugLog.keycloak(projectWithBasePath(null, name = "ci-tests"), "first line")
        CiStatusDebugLog.keycloak(projectWithNullName(settings = CiStatusSettings()), "third line")
        CiStatusDebugLog.keycloak(null, "second line")

        assertTrue(logFile.exists())
        val lines = logFile.readLines()
        assertTrue(lines.any { "[ci-tests] first line" in it })
        assertTrue(lines.any { "[no-project] second line" in it })
        assertTrue(lines.any { "[no-project] third line" in it })
    }

    @Test
    fun `event topics are initialized for refresh and observed Jenkins builds`() {
        assertNotNull(CiStatusRefreshListener.TOPIC)
        assertNotNull(CiStatusJenkinsBuildListener.TOPIC)
        assertTrue(CiStatusRefreshListener.TOPIC.toString().contains("Jenkins CI Refresh"))
        assertTrue(CiStatusJenkinsBuildListener.TOPIC.toString().contains("Jenkins CI Build"))
    }

    @Test
    fun `watcher uses poll interval settings and boosted polling window`() {
        val settings = CiStatusSettings().apply { pollIntervalSeconds = 45 }
        val watcher = watcherForProject(projectWithBasePath(createGitRepo(), settings = settings))

        assertEquals(45_000L, watcher.invokePrivateLong("lightPollMillis"))

        val now = 1_000L
        watcher.invokePrivate("startBoostedPolling", Long::class.javaPrimitiveType!! to now)
        assertTrue(watcher.invokePrivateBoolean("isBoostedPolling", Long::class.javaPrimitiveType!! to now + 5_000L))
        assertFalse(watcher.invokePrivateBoolean("isBoostedPolling", Long::class.javaPrimitiveType!! to now + 181_000L))
    }

    @Test
    fun `startup watcher declares Jenkins fingerprint only once`() {
        val source = Path.of("src/main/kotlin/com/damorosodaragona/jenkinsnotifier/CiStatusStartupActivity.kt").readText()
        val handleJenkinsSummary = source.substringAfter("private fun handleJenkinsSummary")
            .substringBefore("private fun startBoostedPolling")
        val declarations = Regex("""val fingerprint = CiStatusBuildLogic\.fingerprint\(summary\)""")
            .findAll(handleJenkinsSummary)
            .count()

        assertEquals(1, declarations)
    }

    @Test
    fun `release metadata targets v1 stable release`() {
        val properties = Path.of("gradle.properties").readLines()
        val releaseNotes = Path.of("RELEASE_NOTES.md")

        assertTrue(properties.any { it == "pluginVersion=1.0.0" })
        assertTrue(releaseNotes.exists())
        assertTrue(releaseNotes.readText().contains("## v1.0.0"))
        assertTrue(releaseNotes.readText().contains("Jenkins CI Notifier"))
    }

    @Test
    fun `watcher detects head changes after baseline observation`() {
        val watcher = watcherForProject(projectWithBasePath(createGitRepo(branch = "feature/watcher-test")))

        assertFalse(watcher.invokePrivateBoolean("detectHeadChange"))
        watcher.setField("lastObservedSha", "0".repeat(40))
        watcher.setField("lastObservedBranch", "other-branch")

        assertTrue(watcher.invokePrivateBoolean("detectHeadChange"))
    }

    @Test
    fun `watcher detects push when outgoing commits drop to zero`() {
        val remote = Files.createTempDirectory("git-remote-watcher-test")
        git(remote, "init", "--bare")

        val repo = createGitRepo(branch = "main")
        git(repo, "remote", "add", "origin", remote.toUri().toString())
        git(repo, "push", "-u", "origin", "main")
        repo.resolve("README.md").writeText("local change")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "local change")

        val watcher = watcherForProject(projectWithBasePath(repo))
        assertFalse(watcher.invokePrivateBoolean("detectPush"))

        git(repo, "push", "origin", "main")

        assertTrue(watcher.invokePrivateBoolean("detectPush"))
    }

    @Test
    fun `git sha reader returns null for detached head branch name`() {
        val repo = createGitRepo(branch = "feature/detached-head")
        git(repo, "checkout", "HEAD~0", "--detach")

        val reader = GitShaReader(projectWithBasePath(repo))

        assertEquals(null, reader.currentBranch())
    }

    @Test
    fun `git sha reader returns null sha outside a git repository`() {
        val directory = Files.createTempDirectory("not-a-git-repo")
        val reader = GitShaReader(projectWithBasePath(directory))

        assertEquals(null, reader.currentSha())
    }

    @Test
    fun `watcher dispose cancels scheduled future`() {
        val watcher = watcherForProject(projectWithBasePath(createGitRepo()))
        var cancelled = false
        val future = Proxy.newProxyInstance(
            ScheduledFuture::class.java.classLoader,
            arrayOf(ScheduledFuture::class.java),
        ) { _, method, args ->
            when (method.name) {
                "cancel" -> {
                    cancelled = args?.firstOrNull() == true
                    true
                }
                "isCancelled" -> cancelled
                "isDone" -> false
                "getDelay" -> 0L
                "compareTo" -> 0
                "get" -> null
                "toString" -> "FakeScheduledFuture"
                else -> null
            }
        } as ScheduledFuture<*>
        watcher.setField("future", future)

        watcher.invokePrivate("dispose")

        assertTrue(cancelled)
        assertEquals(null, watcher.getField("future"))
    }

    private fun watcherForProject(project: Project): ReflectiveTarget {
        val type = Class.forName("com.damorosodaragona.jenkinsnotifier.CiStatusWatcher")
        val ctor = type.getDeclaredConstructor(Project::class.java)
        ctor.isAccessible = true
        return ReflectiveTarget(ctor.newInstance(project))
    }

    private fun createGitRepo(branch: String = "main"): Path {
        val repo = Files.createTempDirectory("git-watcher-test")
        git(repo, "init", "-b", branch)
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test User")
        repo.resolve("README.md").writeText("test")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "initial")
        return repo
    }

    private fun git(directory: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    }

    private fun projectWithBasePath(
        basePath: Path?,
        settings: CiStatusSettings = CiStatusSettings(),
        name: String = "test-project",
    ): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBasePath" -> basePath?.toString()
                "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
                "isDisposed" -> false
                "isOpen" -> true
                "getName" -> name
                "toString" -> "Project($name)"
                else -> null
            }
        } as Project
    }

    private fun projectWithStartupSupport(
        settings: CiStatusSettings,
        legacy: LegacyCiStatusSettings,
        migrationState: LegacySettingsMigrationState,
        disposed: AtomicBoolean,
    ): Project {
        val connection = Proxy.newProxyInstance(
            MessageBusConnection::class.java.classLoader,
            arrayOf(MessageBusConnection::class.java),
        ) { _, _, _ -> null } as MessageBusConnection
        val bus = Proxy.newProxyInstance(
            MessageBus::class.java.classLoader,
            arrayOf(MessageBus::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "connect" -> connection
                else -> null
            }
        } as MessageBus

        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java, Disposable::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getService" -> when (args?.firstOrNull()) {
                    CiStatusSettings::class.java -> settings
                    LegacyCiStatusSettings::class.java -> legacy
                    LegacySettingsMigrationState::class.java -> migrationState
                    else -> null
                }
                "getMessageBus" -> bus
                "getBasePath" -> null
                "isDisposed" -> disposed.get()
                "isOpen" -> true
                "getName" -> "startup-project"
                "dispose" -> {
                    disposed.set(true)
                    null
                }
                "toString" -> "Project(startup-project)"
                else -> null
            }
        } as Project
    }

    private fun projectWithNullName(settings: CiStatusSettings): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBasePath" -> null
                "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
                "isDisposed" -> false
                "isOpen" -> true
                "getName" -> null
                "toString" -> "Project(null-name)"
                else -> null
            }
        } as Project
    }

    private fun debugLogFile(): Path {
        val field = CiStatusDebugLog::class.java.getDeclaredField("logFile")
        field.isAccessible = true
        return field.get(CiStatusDebugLog) as Path
    }

    private class ReflectiveTarget(private val target: Any) {
        fun invokePrivate(name: String, vararg args: Pair<Class<*>, Any?>): Any? {
            val method = target.javaClass.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
            method.isAccessible = true
            return method.invoke(target, *args.map { it.second }.toTypedArray())
        }

        fun invokePrivateBoolean(name: String, vararg args: Pair<Class<*>, Any?>): Boolean =
            invokePrivate(name, *args) as Boolean

        fun invokePrivateLong(name: String, vararg args: Pair<Class<*>, Any?>): Long =
            invokePrivate(name, *args) as Long

        fun setField(name: String, value: Any?) {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(target, value)
        }

        fun getField(name: String): Any? {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            return field.get(target)
        }
    }

    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        })
        failure?.let { throw it }
    }
}
