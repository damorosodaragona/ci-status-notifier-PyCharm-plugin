package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

internal data class JenkinsDiagnosticsRequest(
    val baseUrl: String,
    val jobPath: String,
    val username: String,
    val apiToken: String,
    val preferredBranch: String?,
)

internal fun interface JenkinsDiagnosticsService {
    fun diagnose(request: JenkinsDiagnosticsRequest): List<JenkinsDiagnosticStep>
}

internal class RealJenkinsDiagnosticsService(
    private val project: Project,
) : JenkinsDiagnosticsService {
    override fun diagnose(request: JenkinsDiagnosticsRequest): List<JenkinsDiagnosticStep> {
        return JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
            JenkinsStatusClient(project).diagnose(
                request.baseUrl,
                request.jobPath,
                request.username,
                request.apiToken,
                request.preferredBranch,
            )
        }
    }
}

internal fun interface JenkinsBranchProvider {
    fun currentBranch(): String?
}

internal class RealJenkinsBranchProvider(
    private val project: Project,
) : JenkinsBranchProvider {
    override fun currentBranch(): String? = GitShaReader(project).currentBranch()
}

internal interface JenkinsDiagnosticsExecutor {
    fun executeOnBackgroundThread(action: () -> Unit)
    fun invokeLater(action: () -> Unit)
}

internal object IntelliJDiagnosticsExecutor : JenkinsDiagnosticsExecutor {
    override fun executeOnBackgroundThread(action: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread { action() }
    }

    override fun invokeLater(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { action() }
    }
}

internal interface JenkinsDiagnosticsUi {
    fun showMissingJenkinsUrl()
    fun showReport(report: String)
    fun showError(message: String)
}

internal class DialogJenkinsDiagnosticsUi(
    private val project: Project,
) : JenkinsDiagnosticsUi {
    override fun showMissingJenkinsUrl() {
        Messages.showWarningDialog(
            project,
            "Configure Jenkins URL before running diagnostics.",
            "Jenkins Diagnostics",
        )
    }

    override fun showReport(report: String) {
        JenkinsDiagnosticsDialog(project, report).show()
    }

    override fun showError(message: String) {
        Messages.showErrorDialog(
            project,
            message,
            "Jenkins Diagnostics",
        )
    }
}

internal fun buildJenkinsDiagnosticReport(
    steps: List<JenkinsDiagnosticStep>,
    usernamePresent: Boolean,
    tokenPresent: Boolean,
): String {
    return buildString {
        appendLine("Jenkins diagnostics")
        appendLine("Username configured: ${if (usernamePresent) "yes" else "no"}")
        appendLine("Token present in Password Safe: ${if (tokenPresent) "yes" else "no"}")
        appendLine("Authorization header sent: ${if (usernamePresent && tokenPresent) "yes" else "no"}")
        appendLine()

        steps.forEach { step ->
            appendLine("${if (step.ok) "OK" else "FAIL"} ${step.name}")
            appendLine("URL: ${step.url}")
            appendLine("Status: ${step.statusCode ?: "-"}")
            appendLine("Auth header sent: ${if (step.authHeaderSent) "yes" else "no"}")
            if (!step.location.isNullOrBlank()) appendLine("Redirect: ${step.location}")
            if (!step.wwwAuthenticate.isNullOrBlank()) appendLine("WWW-Authenticate: ${step.wwwAuthenticate}")
            if (!step.contentType.isNullOrBlank()) appendLine("Content-Type: ${step.contentType}")
            if (!step.error.isNullOrBlank()) appendLine("Error: ${step.error}")
            if (step.bodyPreview.isNotBlank()) appendLine("Body: ${step.bodyPreview}")
            appendLine()
        }
    }
}

private class JenkinsDiagnosticsDialog(
    project: Project,
    private val report: String,
) : DialogWrapper(project) {

    init {
        title = "Jenkins Diagnostics"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JBScrollPane(
            JTextArea(report).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = EmptyBorder(8, 8, 8, 8)
                font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            },
        ).apply {
            preferredSize = Dimension(760, 500)
        }
    }
}
