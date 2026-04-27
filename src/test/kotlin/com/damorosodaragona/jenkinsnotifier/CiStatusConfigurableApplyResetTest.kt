package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CiStatusConfigurableApplyResetTest {

    @Test
    fun `invalid poll interval is saved as default and restored`() = withTestPasswordSafe {
        val settings = CiStatusSettings().apply {
            pollIntervalSeconds = 30
        }
        val project = projectWithSettings(settings)

        val configurable = CiStatusConfigurable(project)
        configurable.createComponent()

        configurable.textField("pollInterval").text = "abc"

        assertTrue(configurable.isModified())

        configurable.apply()

        assertEquals(60, settings.pollIntervalSeconds)

        val reloadedConfigurable = CiStatusConfigurable(project)
        reloadedConfigurable.createComponent()

        assertEquals("60", reloadedConfigurable.textField("pollInterval").text)
        assertFalse(reloadedConfigurable.isModified())
    }

    @Test
    fun `filled settings form is saved and restored`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val project = projectWithSettings(settings)

        val configurable = CiStatusConfigurable(project)
        configurable.createComponent()

        configurable.checkBox("enabled").isSelected = false
        configurable.comboBox("provider").selectedItem = "jenkins"
        configurable.textField("repository").text = " owner/repo "
        configurable.passwordField("token").text = "gh-token"

        configurable.textField("jenkinsBaseUrl").text = " https://jenkins.example.org/ "
        configurable.textField("jenkinsJobPath").text = " /job/projector/ "
        configurable.textField("jenkinsUsername").text = " robot "
        configurable.passwordField("jenkinsToken").text = "jenkins-token"

        configurable.checkBox("experimentalKeycloakInteractiveFallback").isSelected = true
        configurable.checkBox("experimentalKeycloakAutoLogin").isSelected = true
        configurable.checkBox("experimentalKeycloakDebug").isSelected = true
        configurable.textField("keycloakWebUsername").text = " dario "
        configurable.passwordField("keycloakWebPassword").text = "keycloak-password"

        configurable.textField("pollInterval").text = "120"
        configurable.checkBox("notifyPending").isSelected = true
        configurable.checkBox("notifySuccess").isSelected = false
        configurable.checkBox("notifyFailure").isSelected = true

        assertTrue(configurable.isModified())

        configurable.apply()

        assertFalse(settings.enabled)
        assertEquals("jenkins", settings.provider)
        assertEquals("owner/repo", settings.repository)
        assertEquals("gh-token", settings.getToken())

        assertEquals("https://jenkins.example.org", settings.jenkinsBaseUrl)
        assertEquals("job/projector", settings.jenkinsJobPath)
        assertEquals("robot", settings.jenkinsUsername)
        assertEquals("jenkins-token", settings.getJenkinsToken())

        assertTrue(settings.experimentalKeycloakInteractiveFallback)
        assertTrue(settings.experimentalKeycloakAutoLogin)
        assertTrue(settings.experimentalKeycloakDebug)
        assertEquals("dario", settings.keycloakWebUsername)
        assertEquals("keycloak-password", settings.getKeycloakWebPassword())

        assertEquals(120, settings.pollIntervalSeconds)
        assertTrue(settings.notifyPending)
        assertFalse(settings.notifySuccess)
        assertTrue(settings.notifyFailure)

        val reloadedConfigurable = CiStatusConfigurable(project)
        reloadedConfigurable.createComponent()

        assertFalse(reloadedConfigurable.checkBox("enabled").isSelected)
        assertEquals("jenkins", reloadedConfigurable.comboBox("provider").selectedItem)
        assertEquals("owner/repo", reloadedConfigurable.textField("repository").text)
        assertEquals("gh-token", String(reloadedConfigurable.passwordField("token").password))

        assertEquals("https://jenkins.example.org", reloadedConfigurable.textField("jenkinsBaseUrl").text)
        assertEquals("job/projector", reloadedConfigurable.textField("jenkinsJobPath").text)
        assertEquals("robot", reloadedConfigurable.textField("jenkinsUsername").text)
        assertEquals("jenkins-token", String(reloadedConfigurable.passwordField("jenkinsToken").password))

        assertTrue(reloadedConfigurable.checkBox("experimentalKeycloakInteractiveFallback").isSelected)
        assertTrue(reloadedConfigurable.checkBox("experimentalKeycloakAutoLogin").isSelected)
        assertTrue(reloadedConfigurable.checkBox("experimentalKeycloakDebug").isSelected)
        assertEquals("dario", reloadedConfigurable.textField("keycloakWebUsername").text)
        assertEquals("keycloak-password", String(reloadedConfigurable.passwordField("keycloakWebPassword").password))

        assertEquals("120", reloadedConfigurable.textField("pollInterval").text)
        assertTrue(reloadedConfigurable.checkBox("notifyPending").isSelected)
        assertFalse(reloadedConfigurable.checkBox("notifySuccess").isSelected)
        assertTrue(reloadedConfigurable.checkBox("notifyFailure").isSelected)

        assertFalse(reloadedConfigurable.isModified())
    }
}

internal fun projectWithSettings(settings: CiStatusSettings): Project {
    return Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
            "getName" -> "test-project"
            "isDisposed" -> false
            "hashCode" -> 1
            "equals" -> false
            "toString" -> "TestProject"
            else -> null
        }
    } as Project
}



@Suppress("UNCHECKED_CAST")
internal fun <T> CiStatusConfigurable.privateField(name: String): T {
    val field = CiStatusConfigurable::class.java.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this) as T
}

internal fun CiStatusConfigurable.textField(name: String): JBTextField =
    privateField(name)

internal fun CiStatusConfigurable.passwordField(name: String): JBPasswordField =
    privateField(name)

internal fun CiStatusConfigurable.checkBox(name: String): JBCheckBox =
    privateField(name)

internal fun CiStatusConfigurable.comboBox(name: String): ComboBox<*> =
    privateField(name)
