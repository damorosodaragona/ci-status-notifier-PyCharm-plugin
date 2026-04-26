package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

class CiStatusConfigurable(private val project: Project) : Configurable {
    private val settings = CiStatusSettings.getInstance(project)

    private val enabled = JBCheckBox("Poll CI statuses")
    private val provider = ComboBox(arrayOf("github", "jenkins"))
    private val repository = JBTextField()
    private val token = JBPasswordField()
    private val jenkinsBaseUrl = JBTextField()
    private val jenkinsJobPath = JBTextField()
    private val jenkinsUsername = JBTextField()
    private val jenkinsToken = JBPasswordField()
    private val pollInterval = JBTextField()
    private val notifyPending = JBCheckBox("Notify pending statuses")
    private val notifySuccess = JBCheckBox("Notify successful statuses")
    private val notifyFailure = JBCheckBox("Notify failed/error statuses")
    private val experimentalKeycloakInteractiveFallback = JBCheckBox("Enable Keycloak interactive login fallback (experimental)")
    private val experimentalKeycloakAutoLogin = JBCheckBox("Enable Keycloak auto-login (experimental)")
    private val experimentalKeycloakDebug = JBCheckBox("Enable Keycloak authentication debug log")
    private val keycloakWebUsername = JBTextField()
    private val keycloakWebPassword = JBPasswordField()
    private val testJenkinsButton = JButton("Test Jenkins connection")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Jenkins CI Notifier"
    private fun testJenkinsConnectionFromSettings() {
        val baseUrl = jenkinsBaseUrl.text.trim().trimEnd('/')
        val jobPath = jenkinsJobPath.text.trim().trim('/')
        val username = jenkinsUsername.text.trim()
        val apiToken = String(jenkinsToken.password)

        if (baseUrl.isBlank()) {
            Messages.showWarningDialog(
                project,
                "Configure Jenkins URL before running diagnostics.",
                "Jenkins Diagnostics",
            )
            return
        }

        testJenkinsButton.isEnabled = false
        testJenkinsButton.text = "Testing..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val client = JenkinsStatusClient(project)
            val branch = GitShaReader(project).currentBranch()

            val result = runCatching {
                JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
                    client.diagnose(
                        baseUrl,
                        jobPath,
                        username,
                        apiToken,
                        branch,
                    )
                }
            }

            ApplicationManager.getApplication().invokeLater {
                testJenkinsButton.isEnabled = true
                testJenkinsButton.text = "Test Jenkins connection"

                result.onSuccess { steps ->
                    showDiagnosticReport(
                        steps = steps,
                        usernamePresent = username.isNotBlank(),
                        tokenPresent = apiToken.isNotBlank(),
                    )
                }.onFailure { error ->
                    Messages.showErrorDialog(
                        project,
                        "Could not run Jenkins diagnostics:\n${error.message ?: error::class.java.simpleName}",
                        "Jenkins Diagnostics",
                    )
                }
            }
        }
    }

    private fun showDiagnosticReport(
        steps: List<JenkinsDiagnosticStep>,
        usernamePresent: Boolean,
        tokenPresent: Boolean,
    ) {
        val report = buildString {
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

        JenkinsDiagnosticsDialog(project, report).show()
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

    override fun createComponent(): JComponent {
        testJenkinsButton.addActionListener {
            testJenkinsConnectionFromSettings()
        }
        panel = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Provider", provider)
            .addLabeledComponent("GitHub repository", repository)
            .addLabeledComponent("GitHub token", token)
            .addComponent(JBLabel("GitHub repository format: owner/name. Tokens are stored in the JetBrains Password Safe."))
            .addLabeledComponent("Jenkins URL", jenkinsBaseUrl)
            .addLabeledComponent("Jenkins scan root", jenkinsJobPath)
            .addLabeledComponent("Jenkins username", jenkinsUsername)
            .addLabeledComponent("Jenkins API token", jenkinsToken)
            .addLabeledComponent("Jenkins API token", jenkinsToken)
            .addComponent(testJenkinsButton)
            .addComponent(JBLabel("Jenkins scan root is optional. Leave blank to scan from the Jenkins root, or set a raw (job/Folder/job/project) or slash-separated (Folder/project) path to narrow the scan. Root scans require Jenkins permissions that allow reading the global Jenkins root."))
            .addComponent(JBLabel("This Jenkins instance may require an active Keycloak (OIDC) session for API access."))
            .addComponent(JBLabel("Recommended: enable API access without OIDC session on the Jenkins server."))
            .addComponent(JBLabel("Otherwise, you can try the experimental Keycloak auto-login feature."))
            .addComponent(experimentalKeycloakInteractiveFallback)
            .addComponent(experimentalKeycloakAutoLogin)
            .addComponent(experimentalKeycloakDebug)
            .addLabeledComponent("Keycloak web username", keycloakWebUsername)
            .addLabeledComponent("Keycloak web password", keycloakWebPassword)
            .addLabeledComponent("Poll interval seconds", pollInterval)
            .addComponent(notifyPending)
            .addComponent(notifySuccess)
            .addComponent(notifyFailure)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        return enabled.isSelected != settings.enabled ||
            provider.selectedItem != settings.provider ||
            repository.text.trim() != settings.repository ||
            String(token.password) != settings.getToken() ||
            jenkinsBaseUrl.text.trim().trimEnd('/') != settings.jenkinsBaseUrl ||
            jenkinsJobPath.text.trim().trim('/') != settings.jenkinsJobPath ||
            jenkinsUsername.text.trim() != settings.jenkinsUsername ||
            String(jenkinsToken.password) != settings.getJenkinsToken() ||
            experimentalKeycloakInteractiveFallback.isSelected != settings.experimentalKeycloakInteractiveFallback ||
            experimentalKeycloakAutoLogin.isSelected != settings.experimentalKeycloakAutoLogin ||
            experimentalKeycloakDebug.isSelected != settings.experimentalKeycloakDebug ||
            keycloakWebUsername.text.trim() != settings.keycloakWebUsername ||
            String(keycloakWebPassword.password) != settings.getKeycloakWebPassword() ||
            pollInterval.text.trim() != settings.pollIntervalSeconds.toString() ||
            notifyPending.isSelected != settings.notifyPending ||
            notifySuccess.isSelected != settings.notifySuccess ||
            notifyFailure.isSelected != settings.notifyFailure
    }

    override fun apply() {
        settings.enabled = enabled.isSelected
        settings.provider = provider.selectedItem?.toString() ?: "github"
        settings.repository = repository.text
        settings.setToken(String(token.password))
        settings.jenkinsBaseUrl = jenkinsBaseUrl.text
        settings.jenkinsJobPath = jenkinsJobPath.text
        settings.jenkinsUsername = jenkinsUsername.text
        settings.setJenkinsToken(String(jenkinsToken.password))
        settings.experimentalKeycloakInteractiveFallback = experimentalKeycloakInteractiveFallback.isSelected
        settings.experimentalKeycloakAutoLogin = experimentalKeycloakAutoLogin.isSelected
        settings.experimentalKeycloakDebug = experimentalKeycloakDebug.isSelected
        settings.keycloakWebUsername = keycloakWebUsername.text
        settings.setKeycloakWebPassword(String(keycloakWebPassword.password))
        settings.pollIntervalSeconds = pollInterval.text.toIntOrNull() ?: 60
        settings.notifyPending = notifyPending.isSelected
        settings.notifySuccess = notifySuccess.isSelected
        settings.notifyFailure = notifyFailure.isSelected
    }

    override fun reset() {
        enabled.isSelected = settings.enabled
        provider.selectedItem = settings.provider
        repository.text = settings.repository
        token.text = settings.getToken()
        jenkinsBaseUrl.text = settings.jenkinsBaseUrl
        jenkinsJobPath.text = settings.jenkinsJobPath
        jenkinsUsername.text = settings.jenkinsUsername
        jenkinsToken.text = settings.getJenkinsToken()
        experimentalKeycloakInteractiveFallback.isSelected = settings.experimentalKeycloakInteractiveFallback
        experimentalKeycloakAutoLogin.isSelected = settings.experimentalKeycloakAutoLogin
        experimentalKeycloakDebug.isSelected = settings.experimentalKeycloakDebug
        keycloakWebUsername.text = settings.keycloakWebUsername
        keycloakWebPassword.text = settings.getKeycloakWebPassword()
        pollInterval.text = settings.pollIntervalSeconds.toString()
        notifyPending.isSelected = settings.notifyPending
        notifySuccess.isSelected = settings.notifySuccess
        notifyFailure.isSelected = settings.notifyFailure
    }
}
