package com.damorosodaragona.jenkinsnotifier

import javax.swing.JButton
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CiStatusConfigurableTestJenkinsButtonTest {

    @Test
    fun `test Jenkins button shows warning when Jenkins URL is missing`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val diagnosticsService = FakeJenkinsDiagnosticsService { error("Diagnostics should not be called") }
        val diagnosticsUi = FakeJenkinsDiagnosticsUi()
        val diagnosticsExecutor = ManualJenkinsDiagnosticsExecutor()
        val configurable = configurable(
            settings = settings,
            diagnosticsService = diagnosticsService,
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = diagnosticsExecutor,
        )
        configurable.createComponent()

        configurable.textField("jenkinsBaseUrl").text = "   "
        configurable.button("testJenkinsButton").doClick()

        assertEquals(1, diagnosticsUi.missingJenkinsUrlWarnings)
        assertEquals(0, diagnosticsService.calls)
        assertEquals(0, diagnosticsExecutor.backgroundActionsCount)
        assertTrue(configurable.button("testJenkinsButton").isEnabled)
        assertEquals("Test Jenkins connection", configurable.button("testJenkinsButton").text)
    }

    @Test
    fun `test Jenkins button shows successful diagnostic report`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val diagnosticsService = FakeJenkinsDiagnosticsService {
            listOf(
                JenkinsDiagnosticStep(
                    name = "Jenkins API",
                    url = "https://jenkins.example.org/api/json",
                    statusCode = 200,
                    location = null,
                    wwwAuthenticate = null,
                    contentType = "application/json",
                    authHeaderSent = true,
                    bodyPreview = "{\"mode\":\"NORMAL\"}",
                    error = null,
                ),
            )
        }
        val diagnosticsUi = FakeJenkinsDiagnosticsUi()
        val diagnosticsExecutor = ManualJenkinsDiagnosticsExecutor()
        val configurable = configurable(
            settings = settings,
            diagnosticsService = diagnosticsService,
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = diagnosticsExecutor,
            branchProvider = JenkinsBranchProvider { "feature/test" },
        )
        configurable.createComponent()
        configureJenkinsFields(configurable)

        val button = configurable.button("testJenkinsButton")
        button.doClick()

        assertFalse(button.isEnabled)
        assertEquals("Testing...", button.text)
        assertEquals(1, diagnosticsExecutor.backgroundActionsCount)
        assertEquals(0, diagnosticsService.calls)

        diagnosticsExecutor.runNextBackgroundAction()

        assertFalse(button.isEnabled)
        assertEquals(1, diagnosticsService.calls)
        assertEquals(
            JenkinsDiagnosticsRequest(
                baseUrl = "https://jenkins.example.org",
                jobPath = "job/projector",
                username = "robot",
                apiToken = "jenkins-token",
                preferredBranch = "feature/test",
            ),
            diagnosticsService.lastRequest,
        )
        assertEquals(1, diagnosticsExecutor.uiActionsCount)

        diagnosticsExecutor.runNextUiAction()

        assertTrue(button.isEnabled)
        assertEquals("Test Jenkins connection", button.text)
        assertEquals(1, diagnosticsUi.reports.size)
        assertContains(diagnosticsUi.reports.single(), "Jenkins diagnostics")
        assertContains(diagnosticsUi.reports.single(), "Username configured: yes")
        assertContains(diagnosticsUi.reports.single(), "Token present in Password Safe: yes")
        assertContains(diagnosticsUi.reports.single(), "Authorization header sent: yes")
        assertContains(diagnosticsUi.reports.single(), "OK Jenkins API")
        assertContains(diagnosticsUi.reports.single(), "Status: 200")
        assertContains(diagnosticsUi.reports.single(), "Content-Type: application/json")
    }

    @Test
    fun `test Jenkins button shows failed diagnostic report`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val diagnosticsService = FakeJenkinsDiagnosticsService {
            listOf(
                JenkinsDiagnosticStep(
                    name = "Jenkins API",
                    url = "https://jenkins.example.org/api/json",
                    statusCode = 403,
                    location = "https://jenkins.example.org/securityRealm/commenceLogin",
                    wwwAuthenticate = null,
                    contentType = "text/html",
                    authHeaderSent = true,
                    bodyPreview = "Login required",
                    error = null,
                ),
            )
        }
        val diagnosticsUi = FakeJenkinsDiagnosticsUi()
        val diagnosticsExecutor = ManualJenkinsDiagnosticsExecutor()
        val configurable = configurable(
            settings = settings,
            diagnosticsService = diagnosticsService,
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = diagnosticsExecutor,
        )
        configurable.createComponent()
        configureJenkinsFields(configurable)

        val button = configurable.button("testJenkinsButton")
        button.doClick()
        diagnosticsExecutor.runNextBackgroundAction()
        diagnosticsExecutor.runNextUiAction()

        assertTrue(button.isEnabled)
        assertEquals(1, diagnosticsUi.reports.size)
        assertContains(diagnosticsUi.reports.single(), "FAIL Jenkins API")
        assertContains(diagnosticsUi.reports.single(), "Status: 403")
        assertContains(diagnosticsUi.reports.single(), "Redirect: https://jenkins.example.org/securityRealm/commenceLogin")
        assertContains(diagnosticsUi.reports.single(), "Content-Type: text/html")
        assertContains(diagnosticsUi.reports.single(), "Body: Login required")
    }

    @Test
    fun `test Jenkins button shows error when diagnostics throw`() = withTestPasswordSafe {
        val settings = CiStatusSettings()
        val diagnosticsService = FakeJenkinsDiagnosticsService {
            throw IllegalStateException("boom")
        }
        val diagnosticsUi = FakeJenkinsDiagnosticsUi()
        val diagnosticsExecutor = ManualJenkinsDiagnosticsExecutor()
        val configurable = configurable(
            settings = settings,
            diagnosticsService = diagnosticsService,
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = diagnosticsExecutor,
        )
        configurable.createComponent()
        configureJenkinsFields(configurable)

        val button = configurable.button("testJenkinsButton")
        button.doClick()

        assertFalse(button.isEnabled)
        diagnosticsExecutor.runNextBackgroundAction()
        diagnosticsExecutor.runNextUiAction()

        assertTrue(button.isEnabled)
        assertEquals("Test Jenkins connection", button.text)
        assertEquals(listOf("Could not run Jenkins diagnostics:\nboom"), diagnosticsUi.errors)
        assertEquals(emptyList(), diagnosticsUi.reports)
    }

    private fun configurable(
        settings: CiStatusSettings,
        diagnosticsService: JenkinsDiagnosticsService,
        diagnosticsUi: JenkinsDiagnosticsUi,
        diagnosticsExecutor: JenkinsDiagnosticsExecutor,
        branchProvider: JenkinsBranchProvider = JenkinsBranchProvider { "main" },
    ): CiStatusConfigurable {
        return CiStatusConfigurable(
            project = projectWithSettings(settings),
            diagnosticsService = diagnosticsService,
            diagnosticsUi = diagnosticsUi,
            diagnosticsExecutor = diagnosticsExecutor,
            branchProvider = branchProvider,
        )
    }

    private fun configureJenkinsFields(configurable: CiStatusConfigurable) {
        configurable.textField("jenkinsBaseUrl").text = " https://jenkins.example.org/ "
        configurable.textField("jenkinsJobPath").text = " /job/projector/ "
        configurable.textField("jenkinsUsername").text = " robot "
        configurable.passwordField("jenkinsToken").text = "jenkins-token"
    }
}

private class FakeJenkinsDiagnosticsService(
    private val handler: (JenkinsDiagnosticsRequest) -> List<JenkinsDiagnosticStep>,
) : JenkinsDiagnosticsService {
    var calls: Int = 0
        private set
    var lastRequest: JenkinsDiagnosticsRequest? = null
        private set

    override fun diagnose(request: JenkinsDiagnosticsRequest): List<JenkinsDiagnosticStep> {
        calls++
        lastRequest = request
        return handler(request)
    }
}

private class FakeJenkinsDiagnosticsUi : JenkinsDiagnosticsUi {
    var missingJenkinsUrlWarnings: Int = 0
        private set
    val reports = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun showMissingJenkinsUrl() {
        missingJenkinsUrlWarnings++
    }

    override fun showReport(report: String) {
        reports += report
    }

    override fun showError(message: String) {
        errors += message
    }
}

private class ManualJenkinsDiagnosticsExecutor : JenkinsDiagnosticsExecutor {
    private val backgroundActions = mutableListOf<() -> Unit>()
    private val uiActions = mutableListOf<() -> Unit>()

    val backgroundActionsCount: Int
        get() = backgroundActions.size

    val uiActionsCount: Int
        get() = uiActions.size

    override fun executeOnBackgroundThread(action: () -> Unit) {
        backgroundActions += action
    }

    override fun invokeLater(action: () -> Unit) {
        uiActions += action
    }

    fun runNextBackgroundAction() {
        backgroundActions.removeAt(0).invoke()
    }

    fun runNextUiAction() {
        uiActions.removeAt(0).invoke()
    }
}

private fun CiStatusConfigurable.button(name: String): JButton =
    privateField(name)
