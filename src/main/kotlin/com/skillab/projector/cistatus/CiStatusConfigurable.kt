package com.skillab.projector.cistatus

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
    private val keycloakWebUsername = JBTextField()
    private val keycloakWebPassword = JBPasswordField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "CI Status Notifier"

    override fun createComponent(): JComponent {
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
            .addComponent(JBLabel("Jenkins scan root is optional. Leave blank to scan from the Jenkins root, or set a raw (job/Folder/job/project) or slash-separated (Folder/project) path to narrow the scan. Root scans require Jenkins permissions that allow reading the global Jenkins root."))
            .addComponent(JBLabel("This Jenkins instance may require an active Keycloak (OIDC) session for API access."))
            .addComponent(JBLabel("Recommended: enable API access without OIDC session on the Jenkins server."))
            .addComponent(JBLabel("Otherwise, you can try the experimental Keycloak auto-login feature."))
            .addComponent(experimentalKeycloakInteractiveFallback)
            .addComponent(experimentalKeycloakAutoLogin)
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
        keycloakWebUsername.text = settings.keycloakWebUsername
        keycloakWebPassword.text = settings.getKeycloakWebPassword()
        pollInterval.text = settings.pollIntervalSeconds.toString()
        notifyPending.isSelected = settings.notifyPending
        notifySuccess.isSelected = settings.notifySuccess
        notifyFailure.isSelected = settings.notifyFailure
    }
}
