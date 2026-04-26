package com.damorosodaragona.jenkinsnotifier

import kotlin.test.Test
import kotlin.test.assertEquals

class CiStatusSettingsPasswordSafeTest {
    @Test
    fun `token getters and setters round trip through Password Safe`() = withTestPasswordSafe {
        val settings = CiStatusSettings().apply {
            repository = "owner/repo"
            jenkinsBaseUrl = "https://jenkins.example.org"
            jenkinsUsername = "robot"
            keycloakWebUsername = "dario"
        }

        settings.setToken("gh-token")
        settings.setJenkinsToken("jenkins-token")
        settings.setKeycloakWebPassword("keycloak-password")

        assertEquals("gh-token", settings.getToken())
        assertEquals("jenkins-token", settings.getJenkinsToken())
        assertEquals("keycloak-password", settings.getKeycloakWebPassword())

        settings.setToken("")
        settings.setJenkinsToken("")
        settings.setKeycloakWebPassword("")

        assertEquals("", settings.getToken())
        assertEquals("", settings.getJenkinsToken())
        assertEquals("", settings.getKeycloakWebPassword())
    }

    @Test
    fun `blank usernames fall back to default credential usernames`() = withTestPasswordSafe { safe ->
        val settings = CiStatusSettings().apply {
            jenkinsBaseUrl = "https://jenkins.example.org"
        }

        settings.setJenkinsToken("jenkins-token")
        settings.setKeycloakWebPassword("keycloak-password")

        val jenkinsCredentials = safe.get(settings.credentialAttributesForPasswordSafeTest("jenkinsCredentialAttributes"))
        val keycloakCredentials = safe.get(settings.credentialAttributesForPasswordSafeTest("keycloakCredentialAttributes"))

        assertEquals("jenkins", jenkinsCredentials?.userName)
        assertEquals("jenkins-token", jenkinsCredentials?.getPasswordAsString())
        assertEquals("keycloak", keycloakCredentials?.userName)
        assertEquals("keycloak-password", keycloakCredentials?.getPasswordAsString())
    }
}

private fun CiStatusSettings.credentialAttributesForPasswordSafeTest(methodName: String) =
    CiStatusSettings::class.java.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(this) as com.intellij.credentialStore.CredentialAttributes

