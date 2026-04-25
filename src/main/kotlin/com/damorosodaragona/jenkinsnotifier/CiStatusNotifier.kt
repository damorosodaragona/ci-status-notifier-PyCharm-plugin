package com.damorosodaragona.jenkinsnotifier

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager

class CiStatusNotifier(private val project: Project) {
    fun notify(summary: CommitStatusSummary) {
        val notificationType = when (summary.state) {
            "success" -> NotificationType.INFORMATION
            "failure", "error" -> NotificationType.ERROR
            else -> NotificationType.WARNING
        }

        val title = when (summary.state) {
            "success" -> "CI passed"
            "failure" -> "CI failed"
            "error" -> "CI errored"
            else -> "CI is ${summary.state.ifBlank { "pending" }}"
        }

        val content = buildContent(summary)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jenkins CI Notifier")
            .createNotification(title, content, notificationType)

        notification.addAction(NotificationAction.createSimple("Show Jenkins CI") {
            showCiStatus()
        })

        summary.targetUrl?.let { targetUrl ->
            notification.addAction(NotificationAction.createSimple("Open details") {
                BrowserUtil.browse(targetUrl)
            })
        }

        notification.notify(project)
    }

    fun notify(summary: JenkinsBuildSummary) {
        val notificationType = when (summary.state) {
            "SUCCESS" -> NotificationType.INFORMATION
            "FAILURE", "FAILED", "ERROR", "ABORTED" -> NotificationType.ERROR
            else -> NotificationType.WARNING
        }

        val failedStage = summary.stages.firstOrNull { it.status in setOf("FAILED", "FAILURE", "ERROR") }
        val title = when (summary.state) {
            "SUCCESS" -> "Jenkins build passed"
            "FAILURE", "FAILED" -> "Jenkins build failed"
            "ERROR" -> "Jenkins build errored"
            "ABORTED" -> "Jenkins build aborted"
            "RUNNING" -> "Jenkins build running"
            else -> "Jenkins build is ${summary.state.lowercase()}"
        }

        val artifact = summary.artifacts.firstOrNull { it.isHtml } ?: summary.artifacts.firstOrNull()
        val content = buildString {
            append("<b>${StringUtil.escapeXmlEntities(summary.fullDisplayName.ifBlank { summary.displayName })}</b>")
            if (failedStage != null) {
                append("<br/>Failed stage: ${StringUtil.escapeXmlEntities(failedStage.name)}")
            }
            if (summary.artifacts.isNotEmpty()) {
                append("<br/>Artifacts: ${summary.artifacts.size}")
            }
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jenkins CI Notifier")
            .createNotification(title, content, notificationType)

        notification.addAction(NotificationAction.createSimple("Show Jenkins CI") {
            showCiStatus()
        })
        notification.addAction(NotificationAction.createSimple("Open Jenkins") {
            BrowserUtil.browse(summary.url)
        })
        artifact?.let {
            notification.addAction(NotificationAction.createSimple("Open report") {
                BrowserUtil.browse(it.url)
            })
        }

        notification.notify(project)
    }


    fun notifyJenkinsAuthenticationExpired(onLogin: () -> Unit) {
        CiStatusDebugLog.keycloak(project, "auth-notify EMIT: Jenkins authentication notification created")
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Jenkins CI Notifier")
            .createNotification(
                "Jenkins authentication expired",
                "Automatic monitoring was paused because Jenkins requires a new login.",
                NotificationType.WARNING,
            )
        notification.addAction(NotificationAction.createSimple("Login") {
            CiStatusDebugLog.keycloak(project, "auth-notify ACTION: Login clicked")
            onLogin()
            notification.expire()
        })
        notification.notify(project)
        CiStatusDebugLog.keycloak(project, "auth-notify SENT: Jenkins authentication notification shown")
    }

    fun notifyConfigurationProblem(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Jenkins CI Notifier")
            .createNotification("Jenkins CI Notifier", message, NotificationType.WARNING)
            .notify(project)
    }

    private fun showCiStatus() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Jenkins CI") ?: return@invokeLater
            toolWindow.show()
            project.messageBus.syncPublisher(CiStatusRefreshListener.TOPIC).refreshRequested("notification")
        }
    }

    private fun buildContent(summary: CommitStatusSummary): String {
        val sha = summary.sha.take(12)
        val important = summary.statuses
            .filter { it.context.startsWith("Jenkins /") || it.context.startsWith("continuous-integration/jenkins") }
            .take(5)
            .joinToString("<br/>") { status ->
                val description = StringUtil.escapeXmlEntities(status.description.ifBlank { status.state })
                "<b>${StringUtil.escapeXmlEntities(status.context)}</b>: $description"
            }

        return if (important.isBlank()) {
            "Commit <code>$sha</code>: ${summary.state}. ${summary.totalCount} statuses."
        } else {
            "Commit <code>$sha</code>: ${summary.state}.<br/>$important"
        }
    }
}
