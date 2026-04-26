package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LegacySettingsMigrationTest {
    @Test
    fun `legacy settings are empty until user-facing configuration exists`() {
        val legacy = LegacyCiStatusSettings()

        assertTrue(legacy.isEmpty())

        legacy.loadState(LegacyCiStatusSettings.State(repository = "owner/repo"))
        assertFalse(legacy.isEmpty())
    }

    @Test
    fun `legacy settings are not empty when any Jenkins or Keycloak field exists`() {
        listOf(
            LegacyCiStatusSettings.State(jenkinsBaseUrl = "https://jenkins.example.org"),
            LegacyCiStatusSettings.State(jenkinsJobPath = "job/projector"),
            LegacyCiStatusSettings.State(jenkinsUsername = "robot"),
            LegacyCiStatusSettings.State(keycloakWebUsername = "dario"),
        ).forEach { state ->
            val legacy = LegacyCiStatusSettings()
            legacy.loadState(state)
            assertFalse(legacy.isEmpty())
        }
    }

    @Test
    fun `legacy snapshot is a copy of the loaded state`() {
        val legacy = LegacyCiStatusSettings()
        val state = LegacyCiStatusSettings.State(
            enabled = false,
            provider = "jenkins",
            repository = "owner/repo",
            jenkinsBaseUrl = "https://jenkins.example.org",
            jenkinsJobPath = "job/projector",
            jenkinsUsername = "robot",
            pollIntervalSeconds = 30,
            notifyPending = true,
            notifySuccess = false,
            notifyFailure = false,
            experimentalKeycloakInteractiveFallback = true,
            experimentalKeycloakAutoLogin = true,
            experimentalKeycloakDebug = true,
            keycloakWebUsername = "dario",
        )

        legacy.loadState(state)
        val snapshot = legacy.snapshot()

        assertNotSame(state, snapshot)
        assertEquals(state, snapshot)
    }

    @Test
    fun `legacy getState returns loaded state`() {
        val legacy = LegacyCiStatusSettings()
        val state = LegacyCiStatusSettings.State(repository = "owner/repo")

        legacy.loadState(state)

        assertEquals(state, legacy.getState())
    }

    @Test
    fun `legacy credential keys keep old plugin identity for migration lookup`() {
        val legacy = LegacyCiStatusSettings()
        legacy.loadState(
            LegacyCiStatusSettings.State(
                repository = "owner/repo",
                jenkinsBaseUrl = "https://jenkins.example.org",
                jenkinsUsername = "robot",
                keycloakWebUsername = "dario",
            )
        )

        assertEquals(
            "SkillabCiStatusNotifier:owner/repo",
            legacy.githubCredentialAttributes().serviceName,
        )
        assertEquals(
            "SkillabCiStatusNotifier:Jenkins:https://jenkins.example.org:robot",
            legacy.jenkinsCredentialAttributes().serviceName,
        )
        assertEquals(
            "SkillabCiStatusNotifier:Keycloak:https://jenkins.example.org:dario",
            legacy.keycloakCredentialAttributes().serviceName,
        )
    }

    @Test
    fun `legacy credential keys fall back to default segments`() {
        val legacy = LegacyCiStatusSettings()

        assertEquals(
            "SkillabCiStatusNotifier:default",
            legacy.githubCredentialAttributes().serviceName,
        )
        assertEquals(
            "SkillabCiStatusNotifier:Jenkins:default:default",
            legacy.jenkinsCredentialAttributes().serviceName,
        )
        assertEquals(
            "SkillabCiStatusNotifier:Keycloak:default:default",
            legacy.keycloakCredentialAttributes().serviceName,
        )
    }

    @Test
    fun `migration state persists migrated flag`() {
        val migrationState = LegacySettingsMigrationState()

        assertFalse(migrationState.migrated)

        migrationState.migrated = true
        assertTrue(migrationState.getState().migrated)

        migrationState.loadState(LegacySettingsMigrationState.State(migrated = false))
        assertFalse(migrationState.migrated)
    }

    @Test
    fun `migration run is skipped when already migrated`() {
        val current = CiStatusSettings()
        val legacy = LegacyCiStatusSettings()
        legacy.loadState(LegacyCiStatusSettings.State(repository = "owner/repo"))
        val migrationState = LegacySettingsMigrationState()
        migrationState.migrated = true

        LegacySettingsMigration.run(projectWithServices(current, legacy, migrationState))

        assertEquals("", current.repository)
        assertTrue(migrationState.migrated)
    }

    @Test
    fun `migration run marks migrated when legacy settings are empty`() {
        val current = CiStatusSettings()
        val legacy = LegacyCiStatusSettings()
        val migrationState = LegacySettingsMigrationState()

        LegacySettingsMigration.run(projectWithServices(current, legacy, migrationState))

        assertEquals("", current.repository)
        assertTrue(migrationState.migrated)
    }

    @Test
    fun `migration run does not overwrite current user configuration`() {
        val current = CiStatusSettings()
        current.repository = "current/repo"
        val legacy = LegacyCiStatusSettings()
        legacy.loadState(LegacyCiStatusSettings.State(repository = "legacy/repo"))
        val migrationState = LegacySettingsMigrationState()

        LegacySettingsMigration.run(projectWithServices(current, legacy, migrationState))

        assertEquals("current/repo", current.repository)
        assertTrue(migrationState.migrated)
    }

    @Test
    fun `private hasUserConfiguration detects all user managed fields`() {
        val method = LegacySettingsMigration::class.java.getDeclaredMethod("hasUserConfiguration", CiStatusSettings::class.java)
        method.isAccessible = true

        listOf(
            CiStatusSettings().apply { repository = "owner/repo" },
            CiStatusSettings().apply { jenkinsBaseUrl = "https://jenkins.example.org" },
            CiStatusSettings().apply { jenkinsJobPath = "folder/projector" },
            CiStatusSettings().apply { jenkinsUsername = "robot" },
            CiStatusSettings().apply { keycloakWebUsername = "dario" },
        ).forEach { settings ->
            assertTrue(method.invoke(LegacySettingsMigration, settings) as Boolean)
        }

        assertFalse(method.invoke(LegacySettingsMigration, CiStatusSettings()) as Boolean)
    }

    @Test
    fun `private credential attribute builders use current values and defaults`() {
        val githubMethod = LegacySettingsMigration::class.java.getDeclaredMethod("newGithubCredentialAttributes", CiStatusSettings::class.java)
        val jenkinsMethod = LegacySettingsMigration::class.java.getDeclaredMethod("newJenkinsCredentialAttributes", CiStatusSettings::class.java)
        val keycloakMethod = LegacySettingsMigration::class.java.getDeclaredMethod("newKeycloakCredentialAttributes", CiStatusSettings::class.java)
        githubMethod.isAccessible = true
        jenkinsMethod.isAccessible = true
        keycloakMethod.isAccessible = true

        val configured = CiStatusSettings().apply {
            repository = "owner/repo"
            jenkinsBaseUrl = "https://jenkins.example.org"
            jenkinsUsername = "robot"
            keycloakWebUsername = "dario"
        }
        val blank = CiStatusSettings()

        assertEquals(
            "JenkinsCiNotifier:owner/repo",
            (githubMethod.invoke(LegacySettingsMigration, configured) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )
        assertEquals(
            "JenkinsCiNotifier:Jenkins:https://jenkins.example.org:robot",
            (jenkinsMethod.invoke(LegacySettingsMigration, configured) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )
        assertEquals(
            "JenkinsCiNotifier:Keycloak:https://jenkins.example.org:dario",
            (keycloakMethod.invoke(LegacySettingsMigration, configured) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )

        assertEquals(
            "JenkinsCiNotifier:default",
            (githubMethod.invoke(LegacySettingsMigration, blank) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )
        assertEquals(
            "JenkinsCiNotifier:Jenkins:default:default",
            (jenkinsMethod.invoke(LegacySettingsMigration, blank) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )
        assertEquals(
            "JenkinsCiNotifier:Keycloak:default:default",
            (keycloakMethod.invoke(LegacySettingsMigration, blank) as com.intellij.credentialStore.CredentialAttributes).serviceName,
        )
    }
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
