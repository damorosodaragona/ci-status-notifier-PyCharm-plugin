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
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

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
    private val shaReader = GitShaReader(project)

    private val summary = JBLabel("Configure Jenkins in Settings | Tools | CI Status Notifier.")
    private val jobsRoot = DefaultMutableTreeNode("Jenkins")
    private val jobsModel = DefaultTreeModel(jobsRoot)
    private val jobsTree = JTree(jobsModel)
    private val stagesModel = DefaultListModel<JenkinsStage>()
    private val stages = JBList(stagesModel)
    private val artifactsRoot = DefaultMutableTreeNode("Artifacts")
    private val artifactsModel = DefaultTreeModel(artifactsRoot)
    private val artifactsTree = JTree(artifactsModel)
    private val preview = JPanel(BorderLayout())

    private var latestBuild: JenkinsBuildSummary? = null
    private var browser: JBCefBrowser? = null

    val component: JComponent = buildComponent()

    init {
        configureTrees()
        configureStages()
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

        val buildDetails = JPanel(GridLayout(1, 2, 8, 0)).apply {
            add(titledPanel("Stages", JBScrollPane(stages)))
            add(titledPanel("Artifacts", JBScrollPane(artifactsTree)))
        }
        val right = JSplitPane(JSplitPane.VERTICAL_SPLIT, buildDetails, titledPanel("Preview", preview)).apply {
            resizeWeight = 0.45
            border = null
        }
        val main = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, titledPanel("Jenkins", JBScrollPane(jobsTree)), right).apply {
            resizeWeight = 0.28
            border = null
        }

        return JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            add(toolbar, BorderLayout.NORTH)
            add(main, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
    }

    private fun configureTrees() {
        jobsTree.isRootVisible = true
        jobsTree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val node = (value as? DefaultMutableTreeNode)?.userObject as? JenkinsJobNode
                if (node != null) {
                    text = "${statusIcon(node)} ${node.name}${node.lastBuildNumber?.let { " #$it" }.orEmpty()}"
                }
                return component
            }
        }
        jobsTree.addTreeSelectionListener {
            selectedJob()?.takeIf { it.isBuildJob }?.let(::loadBuild)
        }

        artifactsTree.isRootVisible = false
        artifactsTree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val artifact = (value as? DefaultMutableTreeNode)?.userObject as? JenkinsArtifact
                if (artifact != null) {
                    text = "${artifact.name} ${artifact.size?.let(::formatBytes).orEmpty()}"
                }
                return component
            }
        }
        artifactsTree.addTreeSelectionListener {
            selectedArtifact()?.takeIf { it.isHtml }?.let(::previewArtifact)
        }
    }

    private fun configureStages() {
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

        showMessage("Scanning Jenkins jobs...", updateSummary = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val branch = shaReader.currentBranch()
            val result = runCatching {
                jenkins.fetchJobTree(
                    settings.jenkinsBaseUrl,
                    settings.jenkinsJobPath,
                    settings.jenkinsUsername,
                    settings.getJenkinsToken(),
                    branch,
                )
            }

            SwingUtilities.invokeLater {
                result.onSuccess { showJobTree(it, branch) }
                    .onFailure { showMessage("Could not scan Jenkins jobs: ${it.message}", updateSummary = true) }
            }
        }
    }

    private fun showJobTree(tree: JenkinsJobTree, branch: String?) {
        jobsRoot.removeAllChildren()
        val rootNode = buildJobTreeNode(tree.root)
        jobsRoot.add(rootNode)
        jobsModel.reload()
        expandAll(jobsTree)

        val selected = tree.autoSelected
        if (selected != null) {
            findTreePath(rootNode, selected.url)?.let {
                jobsTree.selectionPath = TreePath(arrayOf(jobsRoot, *it))
                jobsTree.scrollPathToVisible(jobsTree.selectionPath)
            }
            summary.text = "Auto-selected ${selected.name}${branch?.let { " for branch $it" }.orEmpty()}."
            loadBuild(selected)
        } else {
            summary.text = "No build job found under configured Jenkins root."
            clearBuild()
        }
    }

    private fun buildJobTreeNode(job: JenkinsJobNode): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(job)
        job.children.forEach { node.add(buildJobTreeNode(it)) }
        return node
    }

    private fun loadBuild(job: JenkinsJobNode) {
        showMessage("Loading ${job.name} latest build...", updateSummary = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                jenkins.fetchLatestBuildForJobUrl(job.url, settings.jenkinsUsername, settings.getJenkinsToken())
            }

            SwingUtilities.invokeLater {
                result.onSuccess(::showBuild)
                    .onFailure { showMessage("Could not load ${job.name}: ${it.message}", updateSummary = true) }
            }
        }
    }

    private fun showBuild(build: JenkinsBuildSummary) {
        latestBuild = build
        summary.text = "${build.fullDisplayName.ifBlank { build.displayName }} - ${build.state} - ${formatDuration(build.durationMillis)}"
        stagesModel.clear()
        build.stages.forEach(stagesModel::addElement)
        rebuildArtifactTree(build.artifacts)

        val failedStage = build.stages.firstOrNull { it.status.uppercase() in setOf("FAILED", "FAILURE", "ERROR") }
        val htmlArtifact = build.artifacts.firstOrNull { it.isHtml }
        when {
            htmlArtifact != null -> previewArtifact(htmlArtifact)
            failedStage != null -> showMessage("Failed stage: ${failedStage.name}")
            else -> showMessage("No HTML artifact found for this build.")
        }
    }

    private fun rebuildArtifactTree(artifacts: List<JenkinsArtifact>) {
        artifactsRoot.removeAllChildren()
        artifacts.forEach { artifact ->
            var parent = artifactsRoot
            val parts = artifact.path.split('/').filter { it.isNotBlank() }
            parts.dropLast(1).forEach { folder ->
                parent = findOrCreateFolder(parent, folder)
            }
            parent.add(DefaultMutableTreeNode(artifact))
        }
        artifactsModel.reload()
        expandAll(artifactsTree)
    }

    private fun clearBuild() {
        latestBuild = null
        stagesModel.clear()
        artifactsRoot.removeAllChildren()
        artifactsModel.reload()
        showMessage("Select a build job from the Jenkins tree.")
    }

    private fun findOrCreateFolder(parent: DefaultMutableTreeNode, name: String): DefaultMutableTreeNode {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) as DefaultMutableTreeNode
            if (child.userObject == name) {
                return child
            }
        }
        return DefaultMutableTreeNode(name).also(parent::add)
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

    private fun selectedJob(): JenkinsJobNode? =
        (jobsTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? JenkinsJobNode

    private fun selectedArtifact(): JenkinsArtifact? =
        (artifactsTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? JenkinsArtifact

    private fun findTreePath(node: DefaultMutableTreeNode, jobUrl: String): Array<Any>? {
        val job = node.userObject as? JenkinsJobNode
        if (job?.url == jobUrl) {
            return node.path.map { it as Any }.toTypedArray()
        }
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as DefaultMutableTreeNode
            val path = findTreePath(child, jobUrl)
            if (path != null) {
                return path
            }
        }
        return null
    }

    private fun expandAll(tree: JTree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row += 1
        }
    }

    private fun titledPanel(title: String, content: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            add(JBLabel(title), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

    private fun statusIcon(job: JenkinsJobNode): String =
        when {
            job.lastBuildBuilding || job.color.endsWith("_anime") -> "[RUN]"
            job.lastBuildResult == "SUCCESS" || job.color == "blue" -> "[OK]"
            job.lastBuildResult in setOf("FAILURE", "FAILED", "ERROR") || job.color == "red" -> "[FAIL]"
            job.children.isNotEmpty() -> "[DIR]"
            else -> "[--]"
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
