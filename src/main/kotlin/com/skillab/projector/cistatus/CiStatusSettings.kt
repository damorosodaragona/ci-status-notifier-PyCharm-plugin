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
        var repository: String = "",
        var pollIntervalSeconds: Int = 60,
        var notifyPending: Boolean = false,
        var notifySuccess: Boolean = true,
        var notifyFailure: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    var repository: String
        get() = state.repository
        set(value) {
            state.repository = value.trim()
        }

    var pollIntervalSeconds: Int
        get() = state.pollIntervalSeconds
        set(value) {
            state.pollIntervalSeconds = value.coerceIn(15, 3600)
        }

    var notifyPending: Boolean
        get() = state.notifyPending
        set(value) {
            state.notifyPending = value
        }

    var notifySuccess: Boolean
        get() = state.notifySuccess
        set(value) {
            state.notifySuccess = value
        }

    var notifyFailure: Boolean
        get() = state.notifyFailure
        set(value) {
            state.notifyFailure = value
        }

    fun getToken(): String = PasswordSafe.instance.getPassword(credentialAttributes()) ?: ""

    fun setToken(token: String) {
        val credentials = if (token.isBlank()) null else Credentials("github", token)
        PasswordSafe.instance.set(credentialAttributes(), credentials)
    }

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:${repository.ifBlank { "default" }}")

    companion object {
        fun getInstance(project: Project): CiStatusSettings = project.getService(CiStatusSettings::class.java)
    }
}
