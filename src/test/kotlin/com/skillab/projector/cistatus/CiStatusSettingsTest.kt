package com.skillab.projector.cistatus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CiStatusSettingsTest {
    @Test
    fun `default state is safe for first plugin startup`() {
        val settings = CiStatusSettings()

        assertTrue(settings.enabled)
        assertEquals("github", settings.provider)
        assertEquals("", settings.repository)
        assertEquals("", settings.jenkinsBaseUrl)
        assertEquals("", settings.jenkinsJobPath)
        assertEquals("", settings.jenkinsUsername)
        assertEquals(60, settings.pollIntervalSeconds)
        assertFalse(settings.notifyPending)
        assertTrue(settings.notifySuccess)
        assertTrue(settings.notifyFailure)
        assertFalse(settings.experimentalKeycloakInteractiveFallback)
        assertFalse(settings.experimentalKeycloakAutoLogin)
        assertFalse(settings.experimentalKeycloakDebug)
        assertEquals("", settings.keycloakWebUsername)
    }

    @Test
    fun `provider accepts only github or jenkins and normalizes invalid values to github`() {
        val settings = CiStatusSettings()

        settings.provider = "JENKINS"
        assertEquals("jenkins", settings.provider)

        settings.provider = "github"
        assertEquals("github", settings.provider)

        settings.provider = "gitlab"
        assertEquals("github", settings.provider)
    }

    @Test
    fun `repository and Jenkins fields are trimmed and normalized`() {
        val settings = CiStatusSettings()

        settings.repository = "  owner/repo  "
        settings.jenkinsBaseUrl = " https://jenkins.example.org/// "
        settings.jenkinsJobPath = " /folder/projector/ "
        settings.jenkinsUsername = "  robot  "
        settings.keycloakWebUsername = "  dario  "

        assertEquals("owner/repo", settings.repository)
        assertEquals("https://jenkins.example.org", settings.jenkinsBaseUrl)
        assertEquals("folder/projector", settings.jenkinsJobPath)
        assertEquals("robot", settings.jenkinsUsername)
        assertEquals("dario", settings.keycloakWebUsername)
    }

    @Test
    fun `poll interval is clamped to supported range`() {
        val settings = CiStatusSettings()

        settings.pollIntervalSeconds = 1
        assertEquals(15, settings.pollIntervalSeconds)

        settings.pollIntervalSeconds = 120
        assertEquals(120, settings.pollIntervalSeconds)

        settings.pollIntervalSeconds = 99999
        assertEquals(3600, settings.pollIntervalSeconds)
    }

    @Test
    fun `loadState replaces persisted values`() {
        val settings = CiStatusSettings()
        val state = CiStatusSettings.State(
            enabled = false,
            provider = "jenkins",
            repository = "owner/repo",
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
        )

        settings.loadState(state)

        assertFalse(settings.enabled)
        assertEquals("jenkins", settings.provider)
        assertEquals("owner/repo", settings.repository)
        assertEquals("https://jenkins.example.org", settings.jenkinsBaseUrl)
        assertEquals("folder/projector", settings.jenkinsJobPath)
        assertEquals("robot", settings.jenkinsUsername)
        assertEquals(30, settings.pollIntervalSeconds)
        assertTrue(settings.notifyPending)
        assertFalse(settings.notifySuccess)
        assertFalse(settings.notifyFailure)
        assertTrue(settings.experimentalKeycloakInteractiveFallback)
        assertTrue(settings.experimentalKeycloakAutoLogin)
        assertTrue(settings.experimentalKeycloakDebug)
        assertEquals("dario", settings.keycloakWebUsername)
    }
}
