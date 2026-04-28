package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class CiStatusConfigurable(
    private val project: Project,
) : Configurable {
    internal constructor(
        project: Project,
        diagnosticsService: JenkinsDiagnosticsService,
        diagnosticsUi: JenkinsDiagnosticsUi,
        diagnosticsExecutor: JenkinsDiagnosticsExecutor,
        branchProvider: JenkinsBranchProvider,
    ) : this(project) {
        this.diagnosticsService = diagnosticsService
        this.diagnosticsUi = diagnosticsUi
        this.diagnosticsExecutor = diagnosticsExecutor
        this.branchProvider = branchProvider
    }

    private val settings = CiStatusSettings.getInstance(project)
    private var diagnosticsService: JenkinsDiagnosticsService = RealJenkinsDiagnosticsService(project)
    private var diagnosticsUi: JenkinsDiagnosticsUi = DialogJenkinsDiagnosticsUi(project)
    private var diagnosticsExecutor: JenkinsDiagnosticsExecutor = IntelliJDiagnosticsExecutor
    private var branchProvider: JenkinsBranchProvider = RealJenkinsBranchProvider(project)

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

    private val githubSettingsPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("GitHub repository", repository)
        .addLabeledComponent("GitHub token", token)
        .addComponent(JBLabel("GitHub repository format: owner/name. Tokens are stored in the JetBrains Password Safe."))
        .panel

    private val jenkinsSettingsPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Jenkins URL", jenkinsBaseUrl)
        .addLabeledComponent("Jenkins scan root", jenkinsJobPath)
        .addLabeledComponent("Jenkins username", jenkinsUsername)
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
        .panel

    private var panel: JPanel? = null

    init {
        configureAutomationNames()
        testJenkinsButton.addActionListener {
            testJenkinsConnectionFromSettings()
        }
        provider.addActionListener {
            updateProviderSettingsVisibility()
        }
    }

    override fun getDisplayName(): String = "Jenkins CI Notifier"

    private fun testJenkinsConnectionFromSettings() {
        val baseUrl = jenkinsBaseUrl.text.trim().trimEnd('/')
        val jobPath = jenkinsJobPath.text.trim().trim('/')
        val username = jenkinsUsername.text.trim()
        val apiToken = String(jenkinsToken.password)

        if (baseUrl.isBlank()) {
            diagnosticsUi.showMissingJenkinsUrl()
            return
        }

        testJenkinsButton.isEnabled = false
        testJenkinsButton.text = "Testing..."

        diagnosticsExecutor.executeOnBackgroundThread {
            val branch = branchProvider.currentBranch()
            val result = runCatching {
                diagnosticsService.diagnose(
                    JenkinsDiagnosticsRequest(
                        baseUrl = baseUrl,
                        jobPath = jobPath,
                        username = username,
                        apiToken = apiToken,
                        preferredBranch = branch,
                    ),
                )
            }

            diagnosticsExecutor.invokeLater {
                testJenkinsButton.isEnabled = true
                testJenkinsButton.text = "Test Jenkins connection"

                result.onSuccess { steps ->
                    diagnosticsUi.showReport(
                        buildJenkinsDiagnosticReport(
                            steps = steps,
                            usernamePresent = username.isNotBlank(),
                            tokenPresent = apiToken.isNotBlank(),
                        ),
                    )
                }.onFailure { error ->
                    diagnosticsUi.showError(
                        "Could not run Jenkins diagnostics:\n${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Provider", provider)
            .addComponent(githubSettingsPanel)
            .addComponent(jenkinsSettingsPanel)
            .addLabeledComponent("Poll interval seconds", pollInterval)
            .addComponent(notifyPending)
            .addComponent(notifySuccess)
            .addComponent(notifyFailure)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    private fun updateProviderSettingsVisibility() {
        val selectedProvider = provider.selectedItem?.toString() ?: "github"
        githubSettingsPanel.isVisible = selectedProvider == "github"
        jenkinsSettingsPanel.isVisible = selectedProvider == "jenkins"
        panel?.revalidate()
        panel?.repaint()
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
        updateProviderSettingsVisibility()
    }

    private fun configureAutomationNames() {
        enabled.withAutomationName("ciStatus.enabled")
        provider.withAutomationName("ciStatus.provider")
        repository.withAutomationName("ciStatus.githubRepository")
        token.withAutomationName("ciStatus.githubToken")
        githubSettingsPanel.withAutomationName("ciStatus.githubSettingsPanel")
        jenkinsSettingsPanel.withAutomationName("ciStatus.jenkinsSettingsPanel")
        jenkinsBaseUrl.withAutomationName("ciStatus.jenkinsBaseUrl")
        jenkinsJobPath.withAutomationName("ciStatus.jenkinsScanRoot")
        jenkinsUsername.withAutomationName("ciStatus.jenkinsUsername")
        jenkinsToken.withAutomationName("ciStatus.jenkinsApiToken")
        testJenkinsButton.withAutomationName("ciStatus.testJenkinsConnection")
        experimentalKeycloakInteractiveFallback.withAutomationName("ciStatus.keycloakInteractiveFallback")
        experimentalKeycloakAutoLogin.withAutomationName("ciStatus.keycloakAutoLogin")
        experimentalKeycloakDebug.withAutomationName("ciStatus.keycloakDebug")
        keycloakWebUsername.withAutomationName("ciStatus.keycloakWebUsername")
        keycloakWebPassword.withAutomationName("ciStatus.keycloakWebPassword")
        pollInterval.withAutomationName("ciStatus.pollIntervalSeconds")
        notifyPending.withAutomationName("ciStatus.notifyPending")
        notifySuccess.withAutomationName("ciStatus.notifySuccess")
        notifyFailure.withAutomationName("ciStatus.notifyFailure")
    }

    private fun JComponent.withAutomationName(value: String) {
        name = value
        accessibleContext.accessibleName = value
    }
}
