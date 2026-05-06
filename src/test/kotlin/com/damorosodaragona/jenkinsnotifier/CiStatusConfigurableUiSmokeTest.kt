package com.damorosodaragona.jenkinsnotifier

import org.junit.jupiter.api.Tag
import javax.swing.JButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("smoke")
class CiStatusConfigurableUiSmokeTest {
    @Test
    fun `settings UI preserves provider-specific input while switching panels`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val configurable = CiStatusConfigurable(projectWithSettings(settings))

        configurable.createComponent()

        assertEquals("github", configurable.comboBox("provider").selectedItem)
        assertTrue(configurable.component("githubSettingsPanel").isVisible)
        assertFalse(configurable.component("jenkinsSettingsPanel").isVisible)

        configurable.textField("repository").text = " damorosodaragona/ci-status-notifier-PyCharm-plugin "
        configurable.passwordField("token").text = "github-token"
        configurable.checkBox("notifyPending").isSelected = true
        configurable.checkBox("notifySuccess").isSelected = true
        configurable.checkBox("notifyFailure").isSelected = false

        configurable.comboBox("provider").selectedItem = "jenkins"

        assertFalse(configurable.component("githubSettingsPanel").isVisible)
        assertTrue(configurable.component("jenkinsSettingsPanel").isVisible)
        assertEquals(" damorosodaragona/ci-status-notifier-PyCharm-plugin ", configurable.textField("repository").text)
        assertEquals("github-token", String(configurable.passwordField("token").password))

        configurable.textField("jenkinsBaseUrl").text = " https://jenkins.example.org/ "
        configurable.textField("jenkinsJobPath").text = " /Folder/project/ "
        configurable.textField("jenkinsUsername").text = " robot "
        configurable.passwordField("jenkinsToken").text = "jenkins-token"
        configurable.checkBox("experimentalKeycloakInteractiveFallback").isSelected = true
        configurable.checkBox("experimentalKeycloakAutoLogin").isSelected = true
        configurable.checkBox("experimentalKeycloakDebug").isSelected = true
        configurable.textField("keycloakWebUsername").text = " oidc-user "
        configurable.passwordField("keycloakWebPassword").text = "oidc-password"
        configurable.textField("pollInterval").text = "45"

        assertTrue(configurable.button("testJenkinsButton").isVisible)
        assertTrue(configurable.isModified())

        configurable.apply()

        assertEquals("jenkins", settings.provider)
        assertEquals("damorosodaragona/ci-status-notifier-PyCharm-plugin", settings.repository)
        assertEquals("github-token", settings.getToken())
        assertEquals("https://jenkins.example.org", settings.jenkinsBaseUrl)
        assertEquals("Folder/project", settings.jenkinsJobPath)
        assertEquals("robot", settings.jenkinsUsername)
        assertEquals("jenkins-token", settings.getJenkinsToken())
        assertTrue(settings.experimentalKeycloakInteractiveFallback)
        assertTrue(settings.experimentalKeycloakAutoLogin)
        assertTrue(settings.experimentalKeycloakDebug)
        assertEquals("oidc-user", settings.keycloakWebUsername)
        assertEquals("oidc-password", settings.getKeycloakWebPassword())
        assertEquals(45, settings.pollIntervalSeconds)
        assertTrue(settings.notifyPending)
        assertTrue(settings.notifySuccess)
        assertFalse(settings.notifyFailure)

        val reloaded = CiStatusConfigurable(projectWithSettings(settings))
        reloaded.createComponent()

        assertEquals("jenkins", reloaded.comboBox("provider").selectedItem)
        assertFalse(reloaded.component("githubSettingsPanel").isVisible)
        assertTrue(reloaded.component("jenkinsSettingsPanel").isVisible)
        assertEquals("https://jenkins.example.org", reloaded.textField("jenkinsBaseUrl").text)
        assertEquals("Folder/project", reloaded.textField("jenkinsJobPath").text)
        assertEquals("robot", reloaded.textField("jenkinsUsername").text)
        assertEquals("jenkins-token", String(reloaded.passwordField("jenkinsToken").password))
        assertEquals("oidc-user", reloaded.textField("keycloakWebUsername").text)
        assertEquals("oidc-password", String(reloaded.passwordField("keycloakWebPassword").password))
        assertFalse(reloaded.isModified())
    }

    private fun CiStatusConfigurable.button(name: String): JButton =
        privateField(name)
}
