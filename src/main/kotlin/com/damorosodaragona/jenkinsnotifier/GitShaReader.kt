package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

class GitShaReader(
    private val project: Project,
    private val processFactory: (List<String>, File) -> Process = { command, directory ->
        ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
    },
    private val waitTimeoutSeconds: Long = 5,
) {
    fun currentSha(): String? {
        return runGit("rev-parse", "HEAD")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
    }

    fun currentBranch(): String? {
        return runGit("branch", "--show-current")
            ?: runGit("rev-parse", "--abbrev-ref", "HEAD")?.takeUnless { it == "HEAD" }
    }

    fun outgoingCommitCount(): Int? {
        val upstream = runGit("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}") ?: return null
        val count = runGit("rev-list", "--count", "$upstream..HEAD") ?: return null
        return count.toIntOrNull()
    }

    fun originBranchSha(): String? {
        val branch = currentBranch() ?: return null
        val line = runGit("ls-remote", "--heads", "origin", "refs/heads/$branch") ?: return null
        return line.substringBefore('\t').takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
    }

    fun originRepository(): String? {
        val remote = runGit("config", "--get", "remote.origin.url") ?: return null
        val match = Regex("github\\.com[:/]([^/]+/[^/.]+)(?:\\.git)?$").find(remote)
        return match?.groupValues?.get(1)
    }

    private fun runGit(vararg args: String): String? {
        val basePath = project.basePath ?: return null
        val process = processFactory(listOf("git", *args), File(basePath))
        if (!process.waitFor(waitTimeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) {
            return null
        }

        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
    }
}
