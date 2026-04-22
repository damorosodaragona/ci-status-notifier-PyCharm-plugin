package com.skillab.projector.cistatus

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

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
            .getNotificationGroup("CI Status Notifier")
            .createNotification(title, content, notificationType)

        summary.targetUrl?.let { targetUrl ->
            notification.addAction(NotificationAction.createSimple("Open details") {
                BrowserUtil.browse(targetUrl)
            })
        }

        notification.notify(project)
    }

    fun notifyConfigurationProblem(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CI Status Notifier")
            .createNotification("CI Status Notifier", message, NotificationType.WARNING)
            .notify(project)
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
