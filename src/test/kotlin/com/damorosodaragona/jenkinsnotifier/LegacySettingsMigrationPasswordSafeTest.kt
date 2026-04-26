package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacySettingsMigrationPasswordSafeTest {
    @Test
    fun `migration copies settings and passwords when current configuration is empty`() = withTestPasswordSafe { safe ->
        val current = CiStatusSettings()
        val legacy = LegacyCiStatusSettings().apply {
            loadState(
                LegacyCiStatusSettings.State(
                    enabled = false,
                    provider = "jenkins",
                    repository = "legacy/repo",
                    jenkinsBaseUrl = "https://jenkins.example.org",
                    jenkinsJobPath = "folder/projector",
                    jenkinsUsername = "robot",
                    pollIntervalSeconds = 30,
                    notifyPending = true,
                    notifySuccess = false,
                    notifyFailure = false,
                    experimentalKeycloakInteractiveFallback = true,
                    experimentalKeycloakAutoLogin = true,
                    experimentalKeycloakDebug = true,
                    keycloakWebUsername = "dario",
                ),
            )
        }
        val migrationState = LegacySettingsMigrationState()

        safe.set(legacy.githubCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "gh-legacy"))
        safe.set(legacy.jenkinsCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "jenkins-legacy"))
        safe.set(legacy.keycloakCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "keycloak-legacy"))

        LegacySettingsMigration.run(projectWithServices(current, legacy, migrationState))

        assertFalse(current.enabled)
        assertEquals("jenkins", current.provider)
        assertEquals("legacy/repo", current.repository)
        assertEquals("https://jenkins.example.org", current.jenkinsBaseUrl)
        assertEquals("folder/projector", current.jenkinsJobPath)
        assertEquals("robot", current.jenkinsUsername)
        assertEquals(30, current.pollIntervalSeconds)
        assertTrue(current.notifyPending)
        assertFalse(current.notifySuccess)
        assertFalse(current.notifyFailure)
        assertTrue(current.experimentalKeycloakInteractiveFallback)
        assertTrue(current.experimentalKeycloakAutoLogin)
        assertTrue(current.experimentalKeycloakDebug)
        assertEquals("dario", current.keycloakWebUsername)
        assertEquals("gh-legacy", current.getToken())
        assertEquals("jenkins-legacy", current.getJenkinsToken())
        assertEquals("keycloak-legacy", current.getKeycloakWebPassword())
        assertTrue(migrationState.migrated)
    }

    @Test
    fun `migration keeps current passwords when already present`() = withTestPasswordSafe { safe ->
        val current = CiStatusSettings().apply {
            repository = "current/repo"
            jenkinsBaseUrl = "https://jenkins.example.org"
            jenkinsUsername = "robot"
            keycloakWebUsername = "dario"
            setToken("gh-current")
            setJenkinsToken("jenkins-current")
            setKeycloakWebPassword("keycloak-current")
        }
        val legacy = LegacyCiStatusSettings().apply {
            loadState(
                LegacyCiStatusSettings.State(
                    repository = "legacy/repo",
                    jenkinsBaseUrl = "https://jenkins.example.org",
                    jenkinsUsername = "robot",
                    keycloakWebUsername = "dario",
                ),
            )
        }
        val migrationState = LegacySettingsMigrationState().apply { migrated = false }

        safe.set(legacy.githubCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "gh-legacy"))
        safe.set(legacy.jenkinsCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "jenkins-legacy"))
        safe.set(legacy.keycloakCredentialAttributes(), com.intellij.credentialStore.Credentials("legacy", "keycloak-legacy"))

        LegacySettingsMigration.run(projectWithServices(current, legacy, migrationState))

        assertEquals("gh-current", current.getToken())
        assertEquals("jenkins-current", current.getJenkinsToken())
        assertEquals("keycloak-current", current.getKeycloakWebPassword())
        assertTrue(migrationState.migrated)
    }

    private fun projectWithServices(
        settings: CiStatusSettings,
        legacy: LegacyCiStatusSettings,
        migrationState: LegacySettingsMigrationState,
    ): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getService" -> when (args?.firstOrNull()) {
                    CiStatusSettings::class.java -> settings
                    LegacyCiStatusSettings::class.java -> legacy
                    LegacySettingsMigrationState::class.java -> migrationState
                    else -> null
                }
                "getName" -> "test-project"
                "isDisposed" -> false
                "isOpen" -> true
                "toString" -> "Project(test-project)"
                else -> null
            }
        } as Project
    }
}
