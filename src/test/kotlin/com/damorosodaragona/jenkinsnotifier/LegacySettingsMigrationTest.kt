package com.damorosodaragona.jenkinsnotifier

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
}
