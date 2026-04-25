package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CiStatusDebugLog {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val logFile: Path = Path.of(PathManager.getLogPath(), "jenkins-ci-notifier-keycloak-debug.log")

    fun keycloak(project: Project?, message: String) {
        runCatching {
            Files.createDirectories(logFile.parent)
            val projectName = project?.name ?: "no-project"
            val line = "${LocalDateTime.now().format(formatter)} [$projectName] $message\n"
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
