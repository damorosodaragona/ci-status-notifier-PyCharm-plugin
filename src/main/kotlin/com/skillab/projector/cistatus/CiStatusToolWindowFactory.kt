package com.skillab.projector.cistatus

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.PathManager
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
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.DefaultListModel
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

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
    private val statusBadge = JLabel("NOT LOADED")
    private val buildTitle = JLabel("No build selected")
    private val buildMeta = JLabel("Select a Jenkins job to inspect stages and artifacts.")
    private val artifactCacheRoot: Path = Path.of(PathManager.getSystemPath(), "ci-status-notifier", "jenkins-artifacts")

    private var latestBuild: JenkinsBuildSummary? = null
    private var browser: JBCefBrowser? = null
    private var lastError: String? = null

    val component: JComponent = buildComponent()

    init {
        configureTrees()
        configureStages()
        refresh()
    }

    private fun buildComponent(): JComponent {
        val refresh = toolbarButton("Refresh")
        val openJenkins = toolbarButton("Jenkins")
        val openArtifact = toolbarButton("Artifact")
        val previewArtifact = toolbarButton("Preview")
        val copyError = toolbarButton("Copy Error")

        refresh.addActionListener { refresh() }
        openJenkins.addActionListener { latestBuild?.url?.let(BrowserUtil::browse) }
        openArtifact.addActionListener { selectedArtifact()?.url?.let(BrowserUtil::browse) }
        previewArtifact.addActionListener { selectedArtifact()?.let { previewArtifact(it) } }
        copyError.addActionListener { copyLastError() }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(refresh)
            add(openJenkins)
            add(openArtifact)
            add(previewArtifact)
            add(copyError)
        }
        val header = JPanel(BorderLayout(10, 0)).apply {
            border = EmptyBorder(4, 8, 4, 8)
            add(statusBadge.apply {
                isOpaque = true
                border = EmptyBorder(2, 8, 2, 8)
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(buildTitle.apply { font = font.deriveFont(Font.BOLD) })
                add(buildMeta)
            }, BorderLayout.CENTER)
            add(toolbar, BorderLayout.EAST)
        }

        val buildDetails = JPanel(GridLayout(1, 2, 4, 0)).apply {
            add(titledPanel("Stages", JBScrollPane(stages)))
            add(titledPanel("Artifacts", JBScrollPane(artifactsTree)))
        }
        val right = JSplitPane(JSplitPane.VERTICAL_SPLIT, buildDetails, titledPanel("Preview", preview)).apply {
            resizeWeight = 0.45
            dividerSize = 1
            isContinuousLayout = true
            border = null
        }
        val main = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, titledPanel("Jenkins", JBScrollPane(jobsTree)), right).apply {
            resizeWeight = 0.28
            dividerSize = 1
            isContinuousLayout = true
            border = null
        }

        return JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
            add(header, BorderLayout.NORTH)
            add(main, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
    }

    private fun configureTrees() {
        jobsTree.isRootVisible = true
        jobsTree.rowHeight = 26
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
                    text = "${statusDot(node)} ${node.name}${node.lastBuildNumber?.let { "  #$it" }.orEmpty()}"
                    foreground = if (selected) foreground else jobColor(node, foreground)
                }
                return component
            }
        }
        jobsTree.addTreeSelectionListener {
            selectedJob()?.takeIf { it.isBuildJob }?.let(::loadBuild)
        }

        artifactsTree.isRootVisible = false
        artifactsTree.rowHeight = 26
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
                val userObject = (value as? DefaultMutableTreeNode)?.userObject
                val artifact = userObject as? JenkinsArtifact
                if (artifact != null) {
                    text = buildString {
                        append(if (artifact.isHtml) "○ " else "  ")
                        append(artifact.path.substringAfterLast('/').ifBlank { artifact.name })
                        if (artifact.isHtml) append("  html")
                        artifact.size?.let { append("  ${formatBytes(it)}") }
                    }
                    foreground = if (selected) foreground else if (artifact.isHtml) JBColor(0x2F6FBD, 0x8AB4F8) else foreground
                } else if (userObject is String) {
                    text = "▾ $userObject"
                }
                return component
            }
        }
        artifactsTree.addTreeSelectionListener {
            selectedArtifact()?.takeIf { it.isHtml }?.let(::previewArtifact)
        }
    }

    private fun configureStages() {
        stages.fixedCellHeight = 28
        stages.cellRenderer = StageRenderer()
    }

    private fun refresh() {
        if (settings.provider != "jenkins") {
            showMessage("Select provider 'jenkins' in Settings | Tools | CI Status Notifier to use this dashboard.", updateSummary = true)
            return
        }

        if (settings.jenkinsBaseUrl.isBlank()) {
            showMessage("Configure Jenkins URL in Settings | Tools | CI Status Notifier.", updateSummary = true)
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
                    .onFailure { showError("Could not scan Jenkins jobs", it) }
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
                    .onFailure { showError("Could not load ${job.name}", it) }
            }
        }
    }

    private fun showBuild(build: JenkinsBuildSummary) {
        latestBuild = build
        summary.text = "${build.fullDisplayName.ifBlank { build.displayName }} - ${build.state} - ${formatDuration(build.durationMillis)}"
        updateHeader(build)
        stagesModel.clear()
        build.stages.forEach(stagesModel::addElement)
        rebuildArtifactTree(build.artifacts)

        val failedStage = build.stages.firstOrNull { it.status.uppercase() in setOf("FAILED", "FAILURE", "ERROR") }
        val htmlArtifact = preferredHtmlArtifact(build.artifacts)
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
        updateHeader(null)
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
        if (!artifact.isHtml) {
            showMessage("Selected artifact is not an HTML report. Use Open Artifact.")
            return
        }
        val build = latestBuild ?: run {
            showMessage("No build selected.")
            return
        }
        if (!JBCefApp.isSupported()) {
            showMessage("Embedded browser is not available. Use Open Artifact.")
            return
        }

        showMessage("Preparing local artifact preview...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val cacheDir = jenkins.downloadArtifacts(
                    build,
                    settings.jenkinsUsername,
                    settings.getJenkinsToken(),
                    artifactCacheRoot,
                )
                cacheDir.resolve(artifact.path).normalize()
            }

            SwingUtilities.invokeLater {
                result.onSuccess { localFile ->
                    browser?.let(Disposer::dispose)
                    browser = null
                    preview.removeAll()
                    browser = JBCefBrowser().also {
                        it.loadURL(localFile.toUri().toString())
                        preview.add(it.component, BorderLayout.CENTER)
                    }
                    preview.revalidate()
                    preview.repaint()
                }.onFailure {
                    showError("Could not prepare artifact preview", it)
                }
            }
        }
    }

    private fun showMessage(message: String, updateSummary: Boolean = false) {
        lastError = null
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

    private fun showError(title: String, error: Throwable) {
        val message = buildString {
            append(title)
            append(": ")
            append(error.message ?: error::class.java.simpleName)
        }
        lastError = message
        browser?.let(Disposer::dispose)
        browser = null
        preview.removeAll()
        preview.add(JBScrollPane(JTextArea(message).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 8
            background = preview.background
            foreground = JBColor.RED
        }), BorderLayout.CENTER)
        preview.revalidate()
        preview.repaint()
        summary.text = title
        notifyError(title, message)
    }

    private fun notifyError(title: String, message: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("CI Status Notifier")
            .createNotification(title, message.take(240), NotificationType.ERROR)
        notification.addAction(NotificationAction.createSimple("Copy details") {
            CopyPasteManager.getInstance().setContents(StringSelection(message))
        })
        notification.notify(project)
    }

    private fun copyLastError() {
        lastError?.let {
            CopyPasteManager.getInstance().setContents(StringSelection(it))
        }
    }

    private inner class StageRenderer : JPanel(BorderLayout(8, 0)), ListCellRenderer<JenkinsStage> {
        private val marker = JLabel("●")
        private val name = JLabel()
        private val duration = JLabel()

        init {
            border = EmptyBorder(3, 8, 3, 8)
            add(marker, BorderLayout.WEST)
            add(name, BorderLayout.CENTER)
            add(duration, BorderLayout.EAST)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out JenkinsStage>,
            value: JenkinsStage?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val stage = value ?: return this
            val selectedBackground = UIManager.getColor("List.selectionBackground")
            val selectedForeground = UIManager.getColor("List.selectionForeground")
            background = if (isSelected) selectedBackground else list.background
            foreground = if (isSelected) selectedForeground else list.foreground

            val color = stageColor(stage.status, foreground)
            marker.foreground = if (isSelected) selectedForeground else color
            marker.text = when (stage.status.uppercase()) {
                "SUCCESS" -> "●"
                "FAILED", "FAILURE", "ERROR" -> "●"
                "IN_PROGRESS", "PAUSED_PENDING_INPUT" -> "●"
                else -> "○"
            }
            name.text = stage.name
            name.foreground = foreground
            duration.text = formatDuration(stage.durationMillis)
            duration.foreground = JBColor.GRAY
            toolTipText = "${stage.status}: ${stage.name}"
            return this
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

    private fun statusText(job: JenkinsJobNode): String =
        when {
            job.lastBuildBuilding || job.color.endsWith("_anime") -> "RUNNING"
            job.lastBuildResult == "SUCCESS" || job.color == "blue" -> "SUCCESS"
            job.lastBuildResult in setOf("FAILURE", "FAILED", "ERROR") || job.color == "red" -> "FAILED"
            job.children.isNotEmpty() -> "GROUP"
            else -> "UNKNOWN"
        }

    private fun statusDot(job: JenkinsJobNode): String =
        when (statusText(job)) {
            "SUCCESS" -> "●"
            "FAILED" -> "●"
            "RUNNING" -> "●"
            "GROUP" -> "▾"
            else -> "○"
        }

    private fun toolbarButton(text: String): JButton =
        JButton(text).apply {
            margin = Insets(2, 8, 2, 8)
            isFocusable = false
        }

    private fun updateHeader(build: JenkinsBuildSummary?) {
        if (build == null) {
            statusBadge.text = "NOT LOADED"
            statusBadge.background = JBColor(0xE0E0E0, 0x4E5257)
            statusBadge.foreground = JBColor.foreground()
            buildTitle.text = "No build selected"
            buildMeta.text = "Select a Jenkins job to inspect stages and artifacts."
            return
        }
        statusBadge.text = "● ${build.state}"
        statusBadge.background = statusBackground(build.state)
        statusBadge.foreground = Color.WHITE
        buildTitle.text = build.fullDisplayName.ifBlank { build.displayName }
        buildMeta.text = "#${build.number}  ${formatDuration(build.durationMillis)}  ${build.artifacts.size} artifacts  ${build.stages.size} stages"
    }

    private fun preferredHtmlArtifact(artifacts: List<JenkinsArtifact>): JenkinsArtifact? {
        val html = artifacts.filter { it.isHtml }
        return html.firstOrNull { it.path.endsWith("index.html") && "quality" in it.path.lowercase() }
            ?: html.firstOrNull { it.path.endsWith("index.html") && "coverage" in it.path.lowercase() }
            ?: html.firstOrNull { it.path.endsWith("index.html") && "test" in it.path.lowercase() }
            ?: html.firstOrNull { it.path.endsWith("index.html") }
            ?: html.firstOrNull()
    }

    private fun jobColor(job: JenkinsJobNode, fallback: Color): Color =
        when (statusText(job)) {
            "SUCCESS" -> JBColor(0x2E7D32, 0x7BC47F)
            "FAILED" -> JBColor.RED
            "RUNNING" -> JBColor(0xB26A00, 0xF0B35A)
            else -> fallback
        }

    private fun stageColor(status: String, fallback: Color): Color =
        when (status.uppercase()) {
            "FAILED", "FAILURE", "ERROR" -> JBColor.RED
            "SUCCESS" -> JBColor(0x2E7D32, 0x7BC47F)
            "IN_PROGRESS", "PAUSED_PENDING_INPUT" -> JBColor(0xB26A00, 0xF0B35A)
            else -> fallback
        }

    private fun statusBackground(status: String): Color =
        when (status.uppercase()) {
            "SUCCESS" -> JBColor(0x2E7D32, 0x2E7D32)
            "FAILED", "FAILURE", "ERROR", "ABORTED" -> JBColor(0xB00020, 0xB00020)
            "RUNNING", "IN_PROGRESS" -> JBColor(0xB26A00, 0xB26A00)
            else -> JBColor(0x5F6368, 0x5F6368)
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
