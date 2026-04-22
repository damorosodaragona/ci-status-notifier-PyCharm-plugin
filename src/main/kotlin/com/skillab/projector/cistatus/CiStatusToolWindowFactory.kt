package com.skillab.projector.cistatus

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

class CiStatusToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboard = JenkinsDashboardPanel(project)
        val content = ContentFactory.getInstance().createContent(dashboard.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class JenkinsDashboardPanel(private val project: Project) {
    private val settings = CiStatusSettings.getInstance(project)
    private val jenkins = JenkinsStatusClient()

    private val summary = JBLabel("Configure Jenkins in Settings | Tools | CI Status Notifier.")
    private val stagesModel = DefaultListModel<JenkinsStage>()
    private val artifactsModel = DefaultListModel<JenkinsArtifact>()
    private val stages = JBList(stagesModel)
    private val artifacts = JBList(artifactsModel)
    private val preview = JPanel(BorderLayout())

    private var latestBuild: JenkinsBuildSummary? = null
    private var browser: JBCefBrowser? = null

    val component: JComponent = buildComponent()

    init {
        configureLists()
        refresh()
    }

    private fun buildComponent(): JComponent {
        val refresh = JButton("Refresh")
        val openJenkins = JButton("Open Jenkins")
        val openArtifact = JButton("Open Artifact")
        val previewArtifact = JButton("Preview HTML")

        refresh.addActionListener { refresh() }
        openJenkins.addActionListener { latestBuild?.url?.let(BrowserUtil::browse) }
        openArtifact.addActionListener { selectedArtifact()?.url?.let(BrowserUtil::browse) }
        previewArtifact.addActionListener { selectedArtifact()?.let { previewArtifact(it) } }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(refresh)
            add(openJenkins)
            add(openArtifact)
            add(previewArtifact)
        }

        val lists = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(titledPanel("Stages", JBScrollPane(stages)))
            add(titledPanel("Artifacts", JBScrollPane(artifacts)))
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, lists, titledPanel("Preview", preview)).apply {
            resizeWeight = 0.45
            border = null
        }

        return JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            add(toolbar, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
            add(split, BorderLayout.CENTER)
        }
    }

    private fun configureLists() {
        stages.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val stage = value as? JenkinsStage ?: return component
                text = "${statusIcon(stage.status)} ${stage.name}  ${stage.status}  ${formatDuration(stage.durationMillis)}"
                foreground = when (stage.status.uppercase()) {
                    "FAILED", "FAILURE", "ERROR" -> JBColor.RED
                    "SUCCESS" -> JBColor(0x2E7D32, 0x7BC47F)
                    "IN_PROGRESS", "PAUSED_PENDING_INPUT" -> JBColor(0xB26A00, 0xF0B35A)
                    else -> foreground
                }
                return component
            }
        }

        artifacts.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val artifact = value as? JenkinsArtifact ?: return component
                text = "${artifact.name}  ${artifact.size?.let(::formatBytes).orEmpty()}"
                return component
            }
        }

        artifacts.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedArtifact()?.takeIf { artifact -> artifact.isHtml }?.let(::previewArtifact)
            }
        }
    }

    private fun refresh() {
        if (settings.provider != "jenkins") {
            showMessage("Select provider 'jenkins' in Settings | Tools | CI Status Notifier to use this dashboard.", updateSummary = true)
            return
        }

        if (settings.jenkinsBaseUrl.isBlank() || settings.jenkinsJobPath.isBlank()) {
            showMessage("Configure Jenkins URL and job path in Settings | Tools | CI Status Notifier.", updateSummary = true)
            return
        }

        showMessage("Loading Jenkins build...", updateSummary = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                jenkins.fetchLatestBuild(
                    settings.jenkinsBaseUrl,
                    settings.jenkinsJobPath,
                    settings.jenkinsUsername,
                    settings.getJenkinsToken(),
                )
            }

            SwingUtilities.invokeLater {
                result.onSuccess(::showBuild)
                    .onFailure { showMessage("Could not load Jenkins build: ${it.message}", updateSummary = true) }
            }
        }
    }

    private fun showBuild(build: JenkinsBuildSummary) {
        latestBuild = build
        summary.text = "${build.fullDisplayName.ifBlank { build.displayName }} - ${build.state} - ${formatDuration(build.durationMillis)}"
        stagesModel.clear()
        build.stages.forEach(stagesModel::addElement)
        artifactsModel.clear()
        build.artifacts.forEach(artifactsModel::addElement)

        val failedStage = build.stages.firstOrNull { it.status.uppercase() in setOf("FAILED", "FAILURE", "ERROR") }
        val htmlArtifact = build.artifacts.firstOrNull { it.isHtml }
        when {
            htmlArtifact != null -> previewArtifact(htmlArtifact)
            failedStage != null -> showMessage("Failed stage: ${failedStage.name}")
            else -> showMessage("No HTML artifact found for this build.")
        }
    }

    private fun previewArtifact(artifact: JenkinsArtifact) {
        browser?.let(Disposer::dispose)
        browser = null
        preview.removeAll()
        if (artifact.isHtml && JBCefApp.isSupported()) {
            browser = JBCefBrowser().also {
                it.loadURL(artifact.url)
                preview.add(it.component, BorderLayout.CENTER)
            }
        } else if (artifact.isHtml) {
            preview.add(JLabel("Embedded browser is not available. Use Open Artifact."), BorderLayout.NORTH)
        } else {
            showMessage("Selected artifact is not an HTML report. Use Open Artifact.")
            return
        }
        preview.revalidate()
        preview.repaint()
    }

    private fun showMessage(message: String, updateSummary: Boolean = false) {
        browser?.let(Disposer::dispose)
        browser = null
        preview.removeAll()
        preview.add(JLabel(message), BorderLayout.NORTH)
        preview.revalidate()
        preview.repaint()
        if (updateSummary) {
            summary.text = message
        }
    }

    private fun selectedArtifact(): JenkinsArtifact? = artifacts.selectedValue

    private fun titledPanel(title: String, content: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            add(JBLabel(title), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

    private fun statusIcon(status: String): String =
        when (status.uppercase()) {
            "SUCCESS" -> "[OK]"
            "FAILED", "FAILURE", "ERROR" -> "[FAIL]"
            "IN_PROGRESS" -> "[RUN]"
            "PAUSED_PENDING_INPUT" -> "[WAIT]"
            else -> "[--]"
        }

    private fun formatDuration(durationMillis: Long): String {
        if (durationMillis <= 0) return ""
        val seconds = durationMillis / 1000
        val minutes = seconds / 60
        return if (minutes > 0) "${minutes}m ${seconds % 60}s" else "${seconds}s"
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
}
