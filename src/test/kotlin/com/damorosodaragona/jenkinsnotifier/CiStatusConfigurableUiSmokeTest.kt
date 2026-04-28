package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import org.junit.jupiter.api.Tag
import java.lang.reflect.Proxy
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
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

    @Test
    fun `tool window UI exposes stable automation names and renders build flow`() = withTestPasswordSafe {
        val settings = CiStatusSettings().apply {
            provider = "jenkins"
            jenkinsBaseUrl = ""
        }
        val observedBuilds = mutableListOf<Pair<String, JenkinsBuildSummary>>()
        val project = uiSmokeProject(settings, observedBuilds)
        val panel = UiSmokeDashboardPanel(project, uiSmokeToolWindow()).use()

        assertAutomationName(panel.field("jobsTree"), "ciStatus.dashboard.jobsTree")
        assertAutomationName(panel.field("stages"), "ciStatus.dashboard.stages")
        assertAutomationName(panel.field("artifactsTree"), "ciStatus.dashboard.artifactsTree")
        assertAutomationName(panel.field("previewContainer"), "ciStatus.dashboard.preview")
        assertAutomationName(panel.field("refreshButton"), "ciStatus.dashboard.refresh")
        assertAutomationName(panel.field("openJenkinsButton"), "ciStatus.dashboard.openJenkins")
        assertAutomationName(panel.field("openArtifactButton"), "ciStatus.dashboard.openArtifact")

        assertEquals("Configure Jenkins URL in Settings | Tools | Jenkins CI Notifier.", panel.label("summary").text)
        assertFalse(panel.button("openJenkinsButton").isEnabled)
        assertFalse(panel.button("openArtifactButton").isEnabled)

        panel.button("closePreviewButton").doClick()
        assertFalse(panel.field<Boolean>("previewOpen"))

        val build = JenkinsBuildSummary(
            number = 42,
            displayName = "#42",
            fullDisplayName = "folder » smoke #42",
            result = "SUCCESS",
            building = false,
            url = "https://jenkins.example/job/smoke/42/",
            timestampMillis = 0L,
            durationMillis = 12_000L,
            stages = listOf(
                JenkinsStage("checkout", "Checkout", "SUCCESS", 1_000L, null),
                JenkinsStage("test", "Test", "SUCCESS", 11_000L, null),
            ),
            artifacts = listOf(
                JenkinsArtifact("index.html", "reports/tests/index.html", "https://jenkins.example/artifact/reports/tests/index.html", 512L),
                JenkinsArtifact("build.log", "logs/build.log", "https://jenkins.example/artifact/logs/build.log", 128L),
            ),
        )

        panel.invoke(
            "showBuild",
            String::class.java to "https://jenkins.example/job/smoke/",
            JenkinsBuildSummary::class.java to build,
            Boolean::class.javaPrimitiveType!! to true,
        )

        assertEquals("SUCCESS", panel.label("statusBadge").text)
        assertEquals("smoke", panel.label("buildTitle").text)
        assertEquals(2, panel.stagesModel().size())
        assertTrue(panel.artifactsTree().containsArtifactPath("reports/tests/index.html"))
        assertTrue(panel.artifactsTree().containsArtifactPath("logs/build.log"))
        assertTrue(panel.button("openJenkinsButton").isEnabled)
        assertTrue(panel.button("openArtifactButton").isEnabled)
        assertEquals("https://jenkins.example/job/smoke/" to build, observedBuilds.single())

        assertFalse(panel.field<Boolean>("previewOpen"))

        val running = build.copy(result = null, building = true)
        panel.invoke(
            "showBuild",
            String::class.java to "https://jenkins.example/job/smoke/",
            JenkinsBuildSummary::class.java to running,
            Boolean::class.javaPrimitiveType!! to true,
        )

        assertEquals("RUNNING", panel.label("statusBadge").text)
        assertFalse(panel.artifactsTree().isEnabled)
        assertFalse(panel.button("openArtifactButton").isEnabled)
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

private class UiSmokeDashboardPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) {
    private val type = Class.forName("com.damorosodaragona.jenkinsnotifier.JenkinsDashboardPanel")
    private val instance = type
        .getDeclaredConstructor(Project::class.java, ToolWindow::class.java)
        .apply { isAccessible = true }
        .newInstance(project, toolWindow)

    fun use(): UiSmokeDashboardPanel = this

    fun <T> field(name: String): T {
        val field = type.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(instance) as T
    }

    fun label(name: String): JLabel = field(name)

    fun button(name: String): JButton = field(name)

    fun artifactsTree(): JTree = field("artifactsTree")

    fun stagesModel(): DefaultListModel<JenkinsStage> = field("stagesModel")

    fun invoke(name: String, vararg args: Pair<Class<*>, Any?>) {
        val method = type.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
        method.isAccessible = true
        method.invoke(instance, *args.map { it.second }.toTypedArray())
    }
}

private fun JTree.containsArtifactPath(path: String): Boolean {
    val root = (model as DefaultTreeModel).root as DefaultMutableTreeNode
    return root.depthFirstEnumeration().asSequence()
        .mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? JenkinsArtifact }
        .any { it.path == path }
}

private fun uiSmokeProject(
    settings: CiStatusSettings,
    observedBuilds: MutableList<Pair<String, JenkinsBuildSummary>>,
): Project {
    val refreshConnection = Proxy.newProxyInstance(
        MessageBusConnection::class.java.classLoader,
        arrayOf(MessageBusConnection::class.java),
    ) { _, _, _ -> null } as MessageBusConnection

    val observedPublisher = object : CiStatusJenkinsBuildListener {
        override fun buildObserved(jobUrl: String, summary: JenkinsBuildSummary) {
            observedBuilds += jobUrl to summary
        }
    }

    val bus = Proxy.newProxyInstance(
        MessageBus::class.java.classLoader,
        arrayOf(MessageBus::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "connect" -> refreshConnection
            "syncPublisher" -> observedPublisher
            else -> null
        }
    } as MessageBus

    return Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java, Disposable::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getService" -> if (args?.firstOrNull() == CiStatusSettings::class.java) settings else null
            "getMessageBus" -> bus
            "getName" -> "ui-smoke-project"
            "isDisposed" -> false
            "isOpen" -> true
            "hashCode" -> 2
            "equals" -> false
            "toString" -> "UiSmokeProject"
            else -> null
        }
    } as Project
}

private fun uiSmokeToolWindow(): ToolWindow =
    Proxy.newProxyInstance(
        ToolWindow::class.java.classLoader,
        arrayOf(ToolWindow::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getIcon" -> null
            "setIcon" -> null
            "toString" -> "UiSmokeToolWindow"
            else -> null
        }
    } as ToolWindow
