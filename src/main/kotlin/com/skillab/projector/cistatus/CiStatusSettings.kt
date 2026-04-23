package com.skillab.projector.cistatus

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "SkillabCiStatusNotifier",
    storages = [Storage("skillab-ci-status-notifier.xml")]
)
class CiStatusSettings : PersistentStateComponent<CiStatusSettings.State> {
    data class State(
        var enabled: Boolean = true,
        var provider: String = "github",
        var repository: String = "",
        var jenkinsBaseUrl: String = "",
        var jenkinsJobPath: String = "",
        var jenkinsUsername: String = "",
        var pollIntervalSeconds: Int = 60,
        var notifyPending: Boolean = false,
        var notifySuccess: Boolean = true,
        var notifyFailure: Boolean = true,
        var experimentalKeycloakInteractiveFallback: Boolean = false,
        var experimentalKeycloakAutoLogin: Boolean = false,
        var keycloakWebUsername: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) { state.enabled = value }

    var repository: String
        get() = state.repository
        set(value) { state.repository = value.trim() }

    var provider: String
        get() = state.provider
        set(value) { state.provider = value.lowercase().takeIf { it in setOf("github", "jenkins") } ?: "github" }

    var jenkinsBaseUrl: String
        get() = state.jenkinsBaseUrl
        set(value) { state.jenkinsBaseUrl = value.trim().trimEnd('/') }

    var jenkinsJobPath: String
        get() = state.jenkinsJobPath
        set(value) { state.jenkinsJobPath = value.trim().trim('/') }

    var jenkinsUsername: String
        get() = state.jenkinsUsername
        set(value) { state.jenkinsUsername = value.trim() }

    var pollIntervalSeconds: Int
        get() = state.pollIntervalSeconds
        set(value) { state.pollIntervalSeconds = value.coerceIn(15, 3600) }

    var notifyPending: Boolean
        get() = state.notifyPending
        set(value) { state.notifyPending = value }

    var notifySuccess: Boolean
        get() = state.notifySuccess
        set(value) { state.notifySuccess = value }

    var notifyFailure: Boolean
        get() = state.notifyFailure
        set(value) { state.notifyFailure = value }

    var experimentalKeycloakInteractiveFallback: Boolean
        get() = state.experimentalKeycloakInteractiveFallback
        set(value) { state.experimentalKeycloakInteractiveFallback = value }

    var experimentalKeycloakAutoLogin: Boolean
        get() = state.experimentalKeycloakAutoLogin
        set(value) { state.experimentalKeycloakAutoLogin = value }

    var keycloakWebUsername: String
        get() = state.keycloakWebUsername
        set(value) { state.keycloakWebUsername = value.trim() }

    fun getToken(): String = PasswordSafe.instance.getPassword(credentialAttributes()) ?: ""

    fun setToken(token: String) {
        val credentials = if (token.isBlank()) null else Credentials("github", token)
        PasswordSafe.instance.set(credentialAttributes(), credentials)
    }

    fun getJenkinsToken(): String = PasswordSafe.instance.getPassword(jenkinsCredentialAttributes()) ?: ""

    fun setJenkinsToken(token: String) {
        val credentials = if (token.isBlank()) null else Credentials(jenkinsUsername.ifBlank { "jenkins" }, token)
        PasswordSafe.instance.set(jenkinsCredentialAttributes(), credentials)
    }

    fun getKeycloakWebPassword(): String = PasswordSafe.instance.getPassword(keycloakCredentialAttributes()) ?: ""

    fun setKeycloakWebPassword(password: String) {
        val credentials = if (password.isBlank()) null else Credentials(keycloakWebUsername.ifBlank { "keycloak" }, password)
        PasswordSafe.instance.set(keycloakCredentialAttributes(), credentials)
    }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:${repository.ifBlank { "default" }}")

    private fun jenkinsCredentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:Jenkins:${jenkinsBaseUrl.ifBlank { "default" }}:${jenkinsUsername.ifBlank { "default" }}")

    private fun keycloakCredentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:Keycloak:${jenkinsBaseUrl.ifBlank { "default" }}:${keycloakWebUsername.ifBlank { "default" }}")

    companion object {
        fun getInstance(project: Project): CiStatusSettings = project.getService(CiStatusSettings::class.java)
    }
}
