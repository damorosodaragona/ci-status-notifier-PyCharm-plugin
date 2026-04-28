package com.damorosodaragona.jenkinsnotifier

import org.junit.jupiter.api.Tag
import javax.swing.JButton
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("smoke")
class CiStatusConfigurableUiSmokeTest {
    @Test
    fun `settings UI exposes stable automation names and toggles provider panels`() = withTestPasswordSafe {
        val settings = CiStatusSettings().apply { provider = "github" }
        val configurable = CiStatusConfigurable(projectWithSettings(settings))

        configurable.createComponent()

        assertAutomationName(configurable.component("githubSettingsPanel"), "ciStatus.githubSettingsPanel")
        assertAutomationName(configurable.component("jenkinsSettingsPanel"), "ciStatus.jenkinsSettingsPanel")
        assertAutomationName(configurable.comboBox("provider"), "ciStatus.provider")
        assertAutomationName(configurable.button("testJenkinsButton"), "ciStatus.testJenkinsConnection")

        assertTrue(configurable.component("githubSettingsPanel").isVisible)
        assertFalse(configurable.component("jenkinsSettingsPanel").isVisible)

        configurable.comboBox("provider").selectedItem = "jenkins"

        assertFalse(configurable.component("githubSettingsPanel").isVisible)
        assertTrue(configurable.component("jenkinsSettingsPanel").isVisible)
    }

    @Test
    fun `settings UI handles missing Jenkins URL without starting diagnostics`() = withTestPasswordSafe {
        val diagnosticsUi = RecordingSmokeDiagnosticsUi()
        val configurable = CiStatusConfigurable(
            project = projectWithSettings(CiStatusSettings().apply { provider = "jenkins" }),
            diagnosticsService = JenkinsDiagnosticsService { error("diagnostics should not run") },
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = UiSmokeSynchronousJenkinsDiagnosticsExecutor,
            branchProvider = JenkinsBranchProvider { "main" },
        )

        configurable.createComponent()
        val button = configurable.button("testJenkinsButton")

        button.doClick()

        assertEquals(1, diagnosticsUi.missingUrlWarnings)
        assertEquals("Test Jenkins connection", button.text)
        assertTrue(button.isEnabled)
    }

    private fun assertAutomationName(component: JComponent, expected: String) {
        assertEquals(expected, component.name)
        assertEquals(expected, component.accessibleContext.accessibleName)
    }
}

private fun CiStatusConfigurable.button(name: String): JButton =
    privateField(name)

private class RecordingSmokeDiagnosticsUi : JenkinsDiagnosticsUi {
    var missingUrlWarnings = 0

    override fun showMissingJenkinsUrl() {
        missingUrlWarnings += 1
    }

    override fun showReport(report: String) = error("report should not be shown: $report")

    override fun showError(message: String) = error("error should not be shown: $message")
}

private object UiSmokeSynchronousJenkinsDiagnosticsExecutor : JenkinsDiagnosticsExecutor {
    override fun executeOnBackgroundThread(action: () -> Unit) {
        action()
    }

    override fun invokeLater(action: () -> Unit) {
        action()
    }
}
