package com.damorosodaragona.jenkinsnotifier

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * One-shot migration from the pre-MVP plugin identity to Jenkins CI Notifier.
 *
 * This file is intentionally isolated so it can be removed after the migration
 * window. When removing it, also remove its projectService entries from
 * plugin.xml and the call in CiStatusStartupActivity.
 */
object LegacySettingsMigration {
    fun run(project: Project) {
        val migrationState = project.getService(LegacySettingsMigrationState::class.java)
        if (migrationState.migrated) {
            return
        }

        val current = CiStatusSettings.getInstance(project)
        val legacy = project.getService(LegacyCiStatusSettings::class.java)
        if (legacy.isEmpty()) {
            migrationState.migrated = true
            return
        }

        if (current.hasUserConfiguration()) {
            migrationState.migrated = true
            return
        }

        val old = legacy.snapshot()
        current.enabled = old.enabled
        current.provider = old.provider
        current.repository = old.repository
        current.jenkinsBaseUrl = old.jenkinsBaseUrl
        current.jenkinsJobPath = old.jenkinsJobPath
        current.jenkinsUsername = old.jenkinsUsername
        current.pollIntervalSeconds = old.pollIntervalSeconds
        current.notifyPending = old.notifyPending
        current.notifySuccess = old.notifySuccess
        current.notifyFailure = old.notifyFailure
        current.experimentalKeycloakInteractiveFallback = old.experimentalKeycloakInteractiveFallback
        current.experimentalKeycloakAutoLogin = old.experimentalKeycloakAutoLogin
        current.experimentalKeycloakDebug = old.experimentalKeycloakDebug
        current.keycloakWebUsername = old.keycloakWebUsername

        migratePassword(legacy.githubCredentialAttributes(), newGithubCredentialAttributes(current))
        migratePassword(legacy.jenkinsCredentialAttributes(), newJenkinsCredentialAttributes(current))
        migratePassword(legacy.keycloakCredentialAttributes(), newKeycloakCredentialAttributes(current))

        migrationState.migrated = true
    }

    private fun CiStatusSettings.hasUserConfiguration(): Boolean =
        repository.isNotBlank() ||
            jenkinsBaseUrl.isNotBlank() ||
            jenkinsJobPath.isNotBlank() ||
            jenkinsUsername.isNotBlank() ||
            keycloakWebUsername.isNotBlank()

    private fun migratePassword(oldAttributes: CredentialAttributes, newAttributes: CredentialAttributes) {
        val oldPassword = PasswordSafe.instance.getPassword(oldAttributes).orEmpty()
        val newPassword = PasswordSafe.instance.getPassword(newAttributes).orEmpty()
        if (oldPassword.isNotBlank() && newPassword.isBlank()) {
            PasswordSafe.instance.set(newAttributes, Credentials(null, oldPassword))
        }
    }

    private fun newGithubCredentialAttributes(settings: CiStatusSettings): CredentialAttributes =
        CredentialAttributes("JenkinsCiNotifier:${settings.repository.ifBlank { "default" }}")

    private fun newJenkinsCredentialAttributes(settings: CiStatusSettings): CredentialAttributes =
        CredentialAttributes("JenkinsCiNotifier:Jenkins:${settings.jenkinsBaseUrl.ifBlank { "default" }}:${settings.jenkinsUsername.ifBlank { "default" }}")

    private fun newKeycloakCredentialAttributes(settings: CiStatusSettings): CredentialAttributes =
        CredentialAttributes("JenkinsCiNotifier:Keycloak:${settings.jenkinsBaseUrl.ifBlank { "default" }}:${settings.keycloakWebUsername.ifBlank { "default" }}")
}

@Service(Service.Level.PROJECT)
@State(
    name = "JenkinsCiNotifierLegacyMigration",
    storages = [Storage("jenkins-ci-notifier-migration.xml")]
)
class LegacySettingsMigrationState : PersistentStateComponent<LegacySettingsMigrationState.State> {
    data class State(
        var migrated: Boolean = false,
    )

    private var migrationState = State()

    var migrated: Boolean
        get() = migrationState.migrated
        set(value) { migrationState.migrated = value }

    override fun getState(): State = migrationState

    override fun loadState(state: State) {
        this.migrationState = state
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "SkillabCiStatusNotifier",
    storages = [
        Storage("ci-status-notifier.xml"),
        Storage("skillab-ci-status-notifier.xml"),
    ]
)
class LegacyCiStatusSettings : PersistentStateComponent<LegacyCiStatusSettings.State> {
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
        var experimentalKeycloakDebug: Boolean = false,
        var keycloakWebUsername: String = "",
    )

    private var legacyState = State()

    override fun getState(): State = legacyState

    override fun loadState(state: State) {
        this.legacyState = state
    }

    fun snapshot(): State = legacyState.copy()

    fun isEmpty(): Boolean =
        legacyState.repository.isBlank() &&
            legacyState.jenkinsBaseUrl.isBlank() &&
            legacyState.jenkinsJobPath.isBlank() &&
            legacyState.jenkinsUsername.isBlank() &&
            legacyState.keycloakWebUsername.isBlank()

    fun githubCredentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:${legacyState.repository.ifBlank { "default" }}")

    fun jenkinsCredentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:Jenkins:${legacyState.jenkinsBaseUrl.ifBlank { "default" }}:${legacyState.jenkinsUsername.ifBlank { "default" }}")

    fun keycloakCredentialAttributes(): CredentialAttributes =
        CredentialAttributes("SkillabCiStatusNotifier:Keycloak:${legacyState.jenkinsBaseUrl.ifBlank { "default" }}:${legacyState.keycloakWebUsername.ifBlank { "default" }}")
}
