package com.skillab.projector.cistatus

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitShaReaderTest {
    @Test
    fun `currentSha returns the 40 character HEAD sha`() {
        val repo = createGitRepo()
        val reader = GitShaReader(projectWithBasePath(repo))

        val sha = reader.currentSha()

        assertNotNull(sha)
        assertTrue(sha.matches(Regex("[0-9a-fA-F]{40}")))
    }

    @Test
    fun `currentBranch returns checked out branch name`() {
        val repo = createGitRepo(branch = "feature/minimal-tests")
        val reader = GitShaReader(projectWithBasePath(repo))

        assertEquals("feature/minimal-tests", reader.currentBranch())
    }

    @Test
    fun `originRepository parses HTTPS GitHub origin`() {
        val repo = createGitRepo()
        git(repo, "remote", "add", "origin", "https://github.com/damorosodaragona/ci-status-notifier-PyCharm-plugin.git")
        val reader = GitShaReader(projectWithBasePath(repo))

        assertEquals("damorosodaragona/ci-status-notifier-PyCharm-plugin", reader.originRepository())
    }

    @Test
    fun `originRepository parses SSH GitHub origin`() {
        val repo = createGitRepo()
        git(repo, "remote", "add", "origin", "git@github.com:damorosodaragona/ci-status-notifier-PyCharm-plugin.git")
        val reader = GitShaReader(projectWithBasePath(repo))

        assertEquals("damorosodaragona/ci-status-notifier-PyCharm-plugin", reader.originRepository())
    }

    @Test
    fun `originRepository returns null for non GitHub origin`() {
        val repo = createGitRepo()
        git(repo, "remote", "add", "origin", "https://example.com/team/repo.git")
        val reader = GitShaReader(projectWithBasePath(repo))

        assertNull(reader.originRepository())
    }

    @Test
    fun `outgoingCommitCount returns commits ahead of upstream`() {
        val remote = Files.createTempDirectory("git-remote-test")
        git(remote, "init", "--bare")

        val repo = Files.createTempDirectory("git-sha-reader-test")
        git(repo, "init", "-b", "main")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test User")
        repo.resolve("README.md").writeText("initial")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "initial")
        git(repo, "remote", "add", "origin", remote.toUri().toString())
        git(repo, "push", "-u", "origin", "main")

        repo.resolve("README.md").writeText("local change")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "local change")

        val reader = GitShaReader(projectWithBasePath(repo))

        assertEquals(1, reader.outgoingCommitCount())
    }

    @Test
    fun `reader returns null when project has no base path`() {
        val reader = GitShaReader(projectWithBasePath(null))

        assertNull(reader.currentSha())
        assertNull(reader.currentBranch())
        assertNull(reader.outgoingCommitCount())
        assertNull(reader.originRepository())
        assertNull(reader.originBranchSha())
    }

    private fun createGitRepo(branch: String = "main"): Path {
        val repo = Files.createTempDirectory("git-sha-reader-test")
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

    private fun projectWithBasePath(basePath: Path?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath?.toString()
                "isDisposed" -> false
                "isOpen" -> true
                "getName" -> "test-project"
                "toString" -> "Project(${basePath ?: "no-base-path"})"
                else -> null
            }
        } as Project
    }
}
