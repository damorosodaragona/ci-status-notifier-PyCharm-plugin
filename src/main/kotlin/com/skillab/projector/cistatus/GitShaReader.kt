package com.skillab.projector.cistatus

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

class GitShaReader(private val project: Project) {
    fun currentSha(): String? {
        return runGit("rev-parse", "HEAD")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
    }

    fun currentBranch(): String? {
        return runGit("branch", "--show-current")
            ?: runGit("rev-parse", "--abbrev-ref", "HEAD")?.takeUnless { it == "HEAD" }
    }

    fun originRepository(): String? {
        val remote = runGit("config", "--get", "remote.origin.url") ?: return null
        val match = Regex("github\\.com[:/]([^/]+/[^/.]+)(?:\\.git)?$").find(remote)
        return match?.groupValues?.get(1)
    }

    private fun runGit(vararg args: String): String? {
        val basePath = project.basePath ?: return null
        val process = ProcessBuilder(listOf("git", *args))
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) {
            return null
        }

        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
    }
}
