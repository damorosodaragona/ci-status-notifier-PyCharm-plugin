package com.damorosodaragona.jenkinsnotifier

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.plaf.basic.BasicSplitPaneUI

class CiStatusToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboard = JenkinsDashboardPanel(project, toolWindow)
        Disposer.register(project, dashboard)
        val content = ContentFactory.getInstance().createContent(dashboard.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class JenkinsDashboardPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {
    private val settings = CiStatusSettings.getInstance(project)
    private val jenkins = JenkinsStatusClient(project)
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
    private val artifactsStatus = secondaryLabel("")
    private val artifactsContent = JPanel(BorderLayout(0, 6))
    private val previewContainer = JPanel(BorderLayout())
    private val previewContent = JPanel(BorderLayout())
    private lateinit var leftColumn: JComponent
    private lateinit var previewSection: JComponent
    private lateinit var contentHost: JPanel
    private val statusBadge = pillLabel("NOT LOADED")
    private val buildTitle = JLabel("No build selected")
    private val buildMeta = secondaryLabel("Select a Jenkins job to inspect stages and artifacts.")
    private val kpiPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    private val artifactCacheRoot: Path = Path.of(PathManager.getSystemPath(), "ci-status-notifier", "jenkins-artifacts")
    private val refreshButton = toolbarButton("Refresh", AllIcons.Actions.Refresh)
    private val testJenkinsButton = toolbarButton("Test Jenkins", AllIcons.Actions.Lightning)
    private val openJenkinsButton = toolbarButton("Open Jenkins", AllIcons.General.Web)
    private val openArtifactButton = toolbarButton("View on Jenkins", AllIcons.General.OpenDisk)
    private val closePreviewButton = toolbarButton("", AllIcons.Actions.Close)

    private var latestBuild: JenkinsBuildSummary? = null
    private var browser: JBCefBrowser? = null
    private var lastError: String? = null
    private var previewOpen = true
    private var previewedArtifact: JenkinsArtifact? = null
    private var selectedJobUrl: String? = null
    private var lastPanelSha: String? = null
    private var lastPanelBranch: String? = null
    private var lastPanelRemoteBranchSha: String? = null
    private var lastPanelOutgoingCommitCount: Int? = null
    private var gitBoostedRefreshUntilMillis: Long = 0L
    private val autoRefreshRunning = AtomicBoolean(false)
    private var autoRefreshFuture: ScheduledFuture<*>? = null
    private val baseToolWindowIcon: Icon? = toolWindow.icon
    private var authenticationPaused: Boolean = false

    val component: JComponent = buildComponent()

    init {
        configureTrees()
        configureStages()
        closePreviewButton.addActionListener { closePreview() }
        project.messageBus.connect(this).subscribe(CiStatusRefreshListener.TOPIC, object : CiStatusRefreshListener {
            override fun refreshRequested(reason: String) {
                if (!project.isDisposed) {
                    refresh(manual = false)
                }
            }
        })
        refresh()
        startAutoRefresh()
    }

    private fun buildComponent(): JComponent {
        refreshButton.addActionListener { refresh(manual = true) }
        testJenkinsButton.addActionListener { testJenkinsConnection() }
        openJenkinsButton.addActionListener { latestBuild?.url?.let(BrowserUtil::browse) }
        openArtifactButton.addActionListener { selectedArtifact()?.url?.let(BrowserUtil::browse) }

        buildTitle.font = buildTitle.font.deriveFont(Font.BOLD, buildTitle.font.size2D + 4f)
        summary.border = EmptyBorder(2, 0, 0, 0)
        closePreviewButton.toolTipText = "Close preview"
        closePreviewButton.margin = Insets(2, 6, 2, 6)
        statusBadge.preferredSize = Dimension(118, 44)
        statusBadge.minimumSize = Dimension(118, 44)
        statusBadge.maximumSize = Dimension(118, 44)

        val headerText = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            minimumSize = Dimension(280, 80)
            add(buildTitle)
            add(Box.createVerticalStrut(4))
            add(buildMeta)
            add(Box.createVerticalStrut(10))
            add(kpiPanel)
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(refreshButton)
            add(testJenkinsButton)
            add(openJenkinsButton)
            add(openArtifactButton)
        }
        actions.minimumSize = Dimension(520, 44)

        val statusHolder = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(130, 48)
            minimumSize = Dimension(130, 48)
            add(statusBadge)
        }

        val header = sectionPanel(
            title = null,
            content = JPanel(BorderLayout(16, 0)).apply {
                isOpaque = false
                add(statusHolder, BorderLayout.WEST)
                add(headerText, BorderLayout.CENTER)
                add(actions, BorderLayout.EAST)
            },
            padding = EmptyBorder(12, 12, 12, 12),
        ).apply {
            minimumSize = Dimension(860, 112)
        }

        val leftTop = sectionPanel("Jenkins", JBScrollPane(jobsTree).apply { border = null })
        val stagesSection = sectionPanel("Stages", JBScrollPane(stages).apply { border = null })
        artifactsStatus.border = EmptyBorder(6, 8, 0, 8)
        artifactsStatus.isVisible = false
        artifactsContent.isOpaque = false
        artifactsContent.add(artifactsStatus, BorderLayout.NORTH)
        artifactsContent.add(JBScrollPane(artifactsTree).apply { border = null }, BorderLayout.CENTER)
        val artifactsSection = sectionPanel("Artifacts", artifactsContent)

        val bottomLeft = splitPane(JSplitPane.VERTICAL_SPLIT, stagesSection, artifactsSection, 0.32)
        leftColumn = splitPane(JSplitPane.VERTICAL_SPLIT, leftTop, bottomLeft, 0.42).apply {
            minimumSize = Dimension(320, 200)
        }

        val previewHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Preview").apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }, BorderLayout.WEST)
            add(closePreviewButton, BorderLayout.EAST)
        }
        previewContainer.isOpaque = false
        previewContent.isOpaque = false
        previewContainer.add(emptyState("Select an HTML or text artifact to preview."), BorderLayout.CENTER)
        previewSection = sectionPanel(
            title = null,
            content = JPanel(BorderLayout(0, 8)).apply {
                isOpaque = false
                add(previewHeader, BorderLayout.NORTH)
                add(previewContainer, BorderLayout.CENTER)
            },
            padding = EmptyBorder(12, 12, 12, 12),
        )
        previewSection.minimumSize = Dimension(480, 300)

        contentHost = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        updateMainContent()

        return JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            border = EmptyBorder(8, 8, 8, 8)
            minimumSize = Dimension(860, 620)
            add(header, BorderLayout.NORTH)
            add(contentHost, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
    }

    private fun configureTrees() {
        jobsTree.isRootVisible = false
        jobsTree.rowHeight = 28
        jobsTree.background = UIManager.getColor("Tree.background")
        jobsTree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val node = (value as? DefaultMutableTreeNode)?.userObject as? JenkinsJobNode
                if (node != null) {
                    append(
                        "${statusDot(node)} ${node.name}${node.lastBuildNumber?.let { "  #$it" }.orEmpty()}",
                        treeTextAttributes(selected, jobColor(node, tree.foreground)),
                    )
                } else {
                    append(value?.toString().orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
        jobsTree.addTreeSelectionListener {
            selectedJob()?.takeIf { it.isBuildJob }?.let(::loadBuild)
        }

        artifactsTree.isRootVisible = false
        artifactsTree.rowHeight = 26
        artifactsTree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val userObject = (value as? DefaultMutableTreeNode)?.userObject
                val artifact = userObject as? JenkinsArtifact
                if (artifact != null) {
                    val text = buildString {
                        append(artifact.path.substringAfterLast('/').ifBlank { artifact.name })
                        artifact.size?.let { append("  ${formatBytes(it)}") }
                    }
                    append(
                        text,
                        treeTextAttributes(selected, if (artifact.isHtml) accentColor() else tree.foreground),
                    )
                } else if (userObject is String) {
                    append(userObject, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    append(value?.toString().orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
        artifactsTree.background = UIManager.getColor("Tree.background")
        artifactsTree.addTreeSelectionListener {
            val artifact = selectedArtifact()
            if (previewOpen && artifact != null && isPreviewSupported(artifact)) {
                previewArtifact(artifact, forceOpen = false)
            }
            updateActions()
        }
        artifactsTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    selectedArtifact()?.takeIf(::isPreviewSupported)?.let { previewArtifact(it, forceOpen = true) }
                }
            }
        })
        updateActions()
    }

    private fun configureStages() {
        stages.fixedCellHeight = 30
        stages.background = UIManager.getColor("List.background")
        stages.cellRenderer = StageRenderer()
    }

    private fun refresh(manual: Boolean = false) {
        authenticationPaused = false
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
                JenkinsStatusClient.withRequestMode(if (manual) JenkinsRequestMode.MANUAL else JenkinsRequestMode.BACKGROUND) {
                    jenkins.fetchJobTree(
                        settings.jenkinsBaseUrl,
                        settings.jenkinsJobPath,
                        settings.jenkinsUsername,
                        settings.getJenkinsToken(),
                        branch,
                    )
                }
            }

            SwingUtilities.invokeLater {
                result.onSuccess { showJobTree(it, branch) }
                    .onFailure {
                        when {
                            !manual && it is JenkinsAuthenticationExpiredException -> {
                                handleBackgroundAuthenticationExpired("Could not scan Jenkins jobs")
                            }
                            manual && it is JenkinsAuthenticationExpiredException -> {
                                handleManualAuthenticationExpired("Could not scan Jenkins jobs")
                            }
                            else -> {
                                showError("Could not scan Jenkins jobs", it)
                            }
                        }
                    }
            }
        }
    }

    private fun testJenkinsConnection() {
        if (settings.jenkinsBaseUrl.isBlank()) {
            showMessage("Configure Jenkins URL before running diagnostics.", updateSummary = true)
            return
        }

        showMessage("Testing Jenkins connection...", updateSummary = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val branch = shaReader.currentBranch()
            val token = settings.getJenkinsToken()
            val result = runCatching {
                JenkinsStatusClient.withRequestMode(JenkinsRequestMode.MANUAL) {
                    jenkins.diagnose(
                        settings.jenkinsBaseUrl,
                        settings.jenkinsJobPath,
                        settings.jenkinsUsername,
                        token,
                        branch,
                    )
                }
            }

            SwingUtilities.invokeLater {
                result.onSuccess { steps ->
                    showDiagnosticReport(steps, settings.jenkinsUsername.isNotBlank(), token.isNotBlank())
                }.onFailure {
                    showError("Could not run Jenkins diagnostics", it)
                }
            }
        }
    }

    private fun showDiagnosticReport(
        steps: List<JenkinsDiagnosticStep>,
        usernamePresent: Boolean,
        tokenPresent: Boolean,
    ) {
        lastError = null
        browser?.let(Disposer::dispose)
        browser = null
        val report = buildString {
            appendLine("Jenkins diagnostics")
            appendLine("Username configured: ${if (usernamePresent) "yes" else "no"}")
            appendLine("Token present in Password Safe: ${if (tokenPresent) "yes" else "no"}")
            appendLine("Authorization header sent: ${if (usernamePresent && tokenPresent) "yes" else "no"}")
            appendLine()
            steps.forEach { step ->
                appendLine("${if (step.ok) "OK" else "FAIL"} ${step.name}")
                appendLine("URL: ${step.url}")
                appendLine("Status: ${step.statusCode ?: "-"}")
                appendLine("Auth header sent: ${if (step.authHeaderSent) "yes" else "no"}")
                if (!step.location.isNullOrBlank()) appendLine("Redirect: ${step.location}")
                if (!step.wwwAuthenticate.isNullOrBlank()) appendLine("WWW-Authenticate: ${step.wwwAuthenticate}")
                if (!step.contentType.isNullOrBlank()) appendLine("Content-Type: ${step.contentType}")
                if (!step.error.isNullOrBlank()) appendLine("Error: ${step.error}")
                if (step.bodyPreview.isNotBlank()) appendLine("Body: ${step.bodyPreview}")
                appendLine()
            }
        }

        previewContent.removeAll()
        previewContent.add(JBScrollPane(JTextArea(report).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = EmptyBorder(8, 8, 8, 8)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }).apply { border = null }, BorderLayout.CENTER)
        if (previewOpen) {
            showPreviewContent()
        } else {
            previewOpen = true
            updateMainContent()
            showPreviewContent()
        }
        summary.text = "Jenkins diagnostics complete."
        updateActions()
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
        selectedJobUrl = job.url
        showMessage("Loading ${job.name} latest build...", updateSummary = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                jenkins.fetchLatestBuildForJobUrl(job.url, settings.jenkinsUsername, settings.getJenkinsToken())
            }

            SwingUtilities.invokeLater {
                result.onSuccess { showBuild(job.url, it) }
                    .onFailure { showError("Could not load ${job.name}", it) }
            }
        }
    }

    private fun showBuild(jobUrl: String, build: JenkinsBuildSummary, refreshPreview: Boolean = true) {
        selectedJobUrl = jobUrl
        latestBuild = build
        project.messageBus.syncPublisher(CiStatusJenkinsBuildListener.TOPIC).buildObserved(jobUrl, build)
        summary.text = "${build.fullDisplayName.ifBlank { build.displayName }} - ${build.state} - ${formatDuration(build.durationMillis)}"
        updateHeader(build)
        updateToolWindowIcon(build.state)
        updateActions()
        stagesModel.clear()
        build.stages.forEach(stagesModel::addElement)
        scrollStagesToCurrent(build)
        val preferredArtifact = preferredPreviewArtifact(build.artifacts)
        val preferredNode = rebuildArtifactTree(build.artifacts, preferredArtifact)
        preferredNode?.let(::selectArtifactNode)
        updateArtifactsAvailability(build.state)

        if (!refreshPreview || build.state == "RUNNING") {
            if (refreshPreview && build.state == "RUNNING" && previewOpen) {
                showMessage("Build is running. Artifact preview will load when the build finishes.")
            }
            return
        }

        val failedStage = build.stages.firstOrNull { it.status.uppercase() in setOf("FAILED", "FAILURE", "ERROR") }
        when {
            preferredArtifact != null && previewOpen -> previewArtifact(preferredArtifact, forceOpen = false)
            preferredArtifact != null && !previewOpen -> renderPreviewClosedState()
            failedStage != null && previewOpen -> showMessage("Failed stage: ${failedStage.name}")
            else -> {
                if (previewOpen) showMessage("No previewable artifact found for this build.")
                else renderPreviewClosedState()
            }
        }
    }

    private fun rebuildArtifactTree(
        artifacts: List<JenkinsArtifact>,
        selectedArtifact: JenkinsArtifact?,
    ): DefaultMutableTreeNode? {
        artifactsRoot.removeAllChildren()
        var selectedNode: DefaultMutableTreeNode? = null
        artifacts.forEach { artifact ->
            var parent = artifactsRoot
            val parts = artifact.path.split('/').filter { it.isNotBlank() }
            parts.dropLast(1).forEach { folder ->
                parent = findOrCreateFolder(parent, folder)
            }
            val artifactNode = DefaultMutableTreeNode(artifact)
            parent.add(artifactNode)
            if (artifact == selectedArtifact) {
                selectedNode = artifactNode
            }
        }
        artifactsModel.reload()
        collapseAll(artifactsTree)
        selectedNode?.let { expandToNode(it) }
        return selectedNode
    }

    private fun selectArtifactNode(node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        artifactsTree.selectionPath = path
        artifactsTree.scrollPathToVisible(path)
    }

    private fun selectArtifactInTree(artifact: JenkinsArtifact) {
        findArtifactNode(artifactsRoot, artifact)?.let(::selectArtifactNode)
    }

    private fun findArtifactNode(parent: DefaultMutableTreeNode, artifact: JenkinsArtifact): DefaultMutableTreeNode? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) as DefaultMutableTreeNode
            when (val userObject = child.userObject) {
                artifact -> return child
                is String -> findArtifactNode(child, artifact)?.let { return it }
            }
        }
        return null
    }

    private fun clearBuild() {
        latestBuild = null
        selectedJobUrl = null
        updateHeader(null)
        updateToolWindowIcon(null)
        updateActions()
        stagesModel.clear()
        previewedArtifact = null
        artifactsRoot.removeAllChildren()
        artifactsModel.reload()
        updateArtifactsAvailability(null)
        showMessage("Select a build job from the Jenkins tree.")
    }

    private fun scrollStagesToCurrent(build: JenkinsBuildSummary) {
        if (build.stages.isEmpty()) {
            return
        }
        val activeIndex = build.stages.indexOfLast {
            it.status.uppercase() in setOf("IN_PROGRESS", "PAUSED_PENDING_INPUT", "RUNNING")
        }
        stages.ensureIndexIsVisible(if (activeIndex >= 0) activeIndex else build.stages.lastIndex)
    }

    private fun updateArtifactsAvailability(state: String?) {
        val running = state == "RUNNING"
        artifactsTree.isEnabled = !running
        artifactsStatus.isVisible = running
        artifactsStatus.text = if (running) {
            "Artifacts are locked while Jenkins is still producing this build."
        } else {
            ""
        }
        updateActions()
    }

    private fun startAutoRefresh() {
        autoRefreshFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { autoRefreshSafely() },
            10,
            10,
            TimeUnit.SECONDS,
        )
    }

    private fun autoRefreshSafely() {
        if (!autoRefreshRunning.compareAndSet(false, true)) {
            return
        }
        try {
            autoRefresh()
        } finally {
            autoRefreshRunning.set(false)
        }
    }

    private fun autoRefresh() {
        if (project.isDisposed || settings.provider != "jenkins" || settings.jenkinsBaseUrl.isBlank() || authenticationPaused) {
            return
        }

        if (detectPanelGitActivity()) {
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    refresh()
                }
            }
            return
        }

        val build = latestBuild
        val jobUrl = selectedJobUrl
        if (build?.state == "RUNNING" && !jobUrl.isNullOrBlank()) {
            refreshSelectedBuild(jobUrl)
            return
        }

        val now = System.currentTimeMillis()
        if (now < gitBoostedRefreshUntilMillis) {
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    refresh()
                }
            }
        }
    }

    private fun detectPanelGitActivity(): Boolean {
        val currentSha = shaReader.currentSha()
        val currentBranch = shaReader.currentBranch()
        val currentOutgoing = shaReader.outgoingCommitCount()
        val currentRemoteBranchSha = shaReader.originBranchSha()
        val headChanged = lastPanelSha != null && (currentSha != lastPanelSha || currentBranch != lastPanelBranch)
        val remoteChanged = lastPanelRemoteBranchSha != null && currentRemoteBranchSha != lastPanelRemoteBranchSha
        val previousOutgoing = lastPanelOutgoingCommitCount
        val pushDetected = previousOutgoing != null &&
            previousOutgoing > 0 &&
            currentOutgoing == 0

        lastPanelSha = currentSha
        lastPanelBranch = currentBranch
        lastPanelRemoteBranchSha = currentRemoteBranchSha
        lastPanelOutgoingCommitCount = currentOutgoing

        if (headChanged || remoteChanged || pushDetected) {
            gitBoostedRefreshUntilMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            return true
        }
        return false
    }

    private fun refreshSelectedBuild(jobUrl: String) {
        val result = runCatching {
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) {
                jenkins.fetchLatestBuildForJobUrl(jobUrl, settings.jenkinsUsername, settings.getJenkinsToken())
            }
        }
        SwingUtilities.invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            result.onSuccess { build ->
                showBuild(jobUrl, build, refreshPreview = build.state != "RUNNING")
            }.onFailure {
                if (it is JenkinsAuthenticationExpiredException) {
                    handleBackgroundAuthenticationExpired("Jenkins authentication expired")
                } else {
                    showError("Could not refresh running Jenkins build", it, notify = false)
                }
            }
        }
    }


    private fun handleBackgroundAuthenticationExpired(title: String) {
        authenticationPaused = true
        summary.text = "Monitoring paused - Jenkins authentication expired."
        updateToolWindowIcon(null)
        buildMeta.text = "Monitoring paused until you login again."
        CiStatusNotifier(project).notifyJenkinsAuthenticationExpired {
            ApplicationManager.getApplication().executeOnPooledThread {
                val recovered = KeycloakSessionService.getInstance(project).ensureLoggedIn(settings.jenkinsBaseUrl)
                if (recovered) {
                    authenticationPaused = false
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            refresh(manual = false)
                        }
                    }
                }
            }
        }
    }

    private fun handleManualAuthenticationExpired(title: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val recovered = KeycloakSessionService.getInstance(project).ensureLoggedIn(settings.jenkinsBaseUrl)
            if (recovered) {
                authenticationPaused = false
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        refresh(manual = false)
                    }
                }
            } else {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        showError(title, JenkinsAuthenticationExpiredException(settings.jenkinsBaseUrl))
                    }
                }
            }
        }
    }

    override fun dispose() {
        autoRefreshFuture?.cancel(true)
        autoRefreshFuture = null
        browser?.let(Disposer::dispose)
        browser = null
    }

    private fun updateMainContent() {
        contentHost.removeAll()
        val mainContent = if (previewOpen) {
            splitPane(JSplitPane.HORIZONTAL_SPLIT, leftColumn, previewSection, 0.30)
        } else {
            leftColumn
        }
        contentHost.add(mainContent, BorderLayout.CENTER)
        contentHost.revalidate()
        contentHost.repaint()
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

    private fun previewArtifact(artifact: JenkinsArtifact, forceOpen: Boolean) {
        if (!isPreviewSupported(artifact)) {
            showMessage("Selected artifact cannot be previewed.")
            return
        }
        if (!previewOpen && !forceOpen) {
            return
        }
        val wasClosed = !previewOpen
        previewOpen = true
        previewedArtifact = artifact
        if (wasClosed) {
            updateMainContent()
        }
        if (artifact.isHtml) {
            previewHtmlArtifact(artifact)
        } else {
            previewTextArtifact(artifact)
        }
    }

    private fun previewHtmlArtifact(artifact: JenkinsArtifact) {
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
                    previewContent.removeAll()
                    browser = JBCefBrowser().also {
                        it.loadURL(localFile.toUri().toString())
                        previewContent.add(it.component, BorderLayout.CENTER)
                    }
                    showPreviewContent()
                }.onFailure {
                    showError("Could not prepare artifact preview", it, notify = false)
                }
            }
        }
    }

    private fun previewTextArtifact(artifact: JenkinsArtifact) {
        val build = latestBuild ?: run {
            showMessage("No build selected.")
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
                cacheDir.resolve(artifact.path).normalize().toFile().readText()
            }

            SwingUtilities.invokeLater {
                result.onSuccess { content ->
                    browser?.let(Disposer::dispose)
                    browser = null
                    previewContent.removeAll()
                    previewContent.add(JBScrollPane(JTextArea(content).apply {
                        isEditable = false
                        lineWrap = false
                        wrapStyleWord = false
                        border = EmptyBorder(8, 8, 8, 8)
                        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
                    }).apply { border = null }, BorderLayout.CENTER)
                    showPreviewContent()
                }.onFailure {
                    showError("Could not prepare artifact preview", it, notify = false)
                }
            }
        }
    }

    private fun showPreviewContent() {
        if (!previewOpen) {
            renderPreviewClosedState()
            return
        }
        previewContainer.removeAll()
        previewContainer.add(previewContent, BorderLayout.CENTER)
        previewContainer.revalidate()
        previewContainer.repaint()
    }

    private fun closePreview() {
        previewOpen = false
        browser?.let(Disposer::dispose)
        browser = null
        previewContent.removeAll()
        updateMainContent()
    }

    private fun renderPreviewClosedState() {
        updateMainContent()
    }

    private fun showMessage(message: String, updateSummary: Boolean = false) {
        lastError = null
        browser?.let(Disposer::dispose)
        browser = null
        previewContent.removeAll()
        previewContent.add(emptyState(message), BorderLayout.CENTER)
        if (previewOpen) {
            showPreviewContent()
        } else {
            renderPreviewClosedState()
        }
        if (updateSummary) {
            summary.text = message
        }
        updateActions()
    }

    private fun showError(title: String, error: Throwable, notify: Boolean = true) {
        val message = buildString {
            append(title)
            append(": ")
            append(error.message ?: error::class.java.simpleName)
        }
        lastError = message
        browser?.let(Disposer::dispose)
        browser = null
        previewContent.removeAll()
        previewContent.add(JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(JButton("Copy details", AllIcons.Actions.Copy).apply {
                margin = Insets(2, 8, 2, 8)
                isFocusable = false
                addActionListener { copyLastError() }
            }, BorderLayout.NORTH)
            add(JBScrollPane(JTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = 8
                background = UIManager.getColor("Panel.background")
                foreground = JBColor.RED
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            }).apply { border = null }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        if (previewOpen) {
            showPreviewContent()
        } else {
            renderPreviewClosedState()
        }
        summary.text = title
        if (notify) {
            notifyError(title, message)
        }
        updateActions()
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

    private fun updateActions() {
        openJenkinsButton.isEnabled = latestBuild != null
        openArtifactButton.isEnabled = latestBuild?.state != "RUNNING" && selectedArtifact() != null
    }

    private fun updateToolWindowIcon(state: String?) {
        val base = baseToolWindowIcon ?: return
        toolWindow.setIcon(when (state) {
            "RUNNING" -> StatusBadgeIcon(base, JBColor(0xF2C94C, 0xF2C94C))
            "SUCCESS" -> StatusBadgeIcon(base, JBColor(0x2E7D32, 0x7BC47F))
            "FAILURE", "FAILED", "ERROR", "ABORTED" -> StatusBadgeIcon(base, JBColor(0xC62828, 0xFF6B6B))
            else -> base
        })
    }

    private fun treeTextAttributes(selected: Boolean, color: Color): SimpleTextAttributes =
        if (selected) {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        } else {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
        }

    private class StatusBadgeIcon(
        private val delegate: Icon,
        private val color: Color,
    ) : Icon {
        override fun getIconWidth(): Int = delegate.iconWidth

        override fun getIconHeight(): Int = delegate.iconHeight

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            delegate.paintIcon(c, g, x, y)
            val g2 = g.create() as Graphics2D
            try {
                val size = (minOf(iconWidth, iconHeight) / 3).coerceAtLeast(6)
                val dotX = x + iconWidth - size
                val dotY = y
                g2.color = JBColor.PanelBackground
                g2.fillOval(dotX - 1, dotY - 1, size + 2, size + 2)
                g2.color = color
                g2.fillOval(dotX, dotY, size, size)
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class StageRenderer : JPanel(BorderLayout(8, 0)), ListCellRenderer<JenkinsStage> {
        private val name = JLabel()
        private val duration = secondaryLabel("")

        private val timeline = TimelineMarker()
        private val textPanel = JPanel(BorderLayout(8, 2))

        init {
            border = EmptyBorder(4, 8, 4, 8)
            timeline.border = EmptyBorder(0, 2, 0, 6)
            textPanel.isOpaque = false
            textPanel.add(name, BorderLayout.CENTER)
            textPanel.add(duration, BorderLayout.EAST)
            add(timeline, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out JenkinsStage>,
            value: JenkinsStage?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val stage = value ?: return this
            val selectedBackground = UIManager.getColor("List.selectionBackground")
            val selectedForeground = UIManager.getColor("List.selectionForeground")
            background = if (isSelected) selectedBackground else list.background
            foreground = if (isSelected) selectedForeground else list.foreground

            val color = stageColor(stage.status, foreground)
            timeline.markerColor = if (isSelected) selectedForeground else color
            timeline.drawTopLine = index > 0
            timeline.drawBottomLine = index < list.model.size - 1
            name.text = stage.name
            name.foreground = foreground
            duration.text = formatDuration(stage.durationMillis)
            duration.foreground = if (isSelected) selectedForeground else JBColor.GRAY
            toolTipText = "${stage.status}: ${stage.name}"
            return this
        }
    }

    private class TimelineMarker : JComponent() {
        var markerColor: Color = JBColor.GRAY
        var drawTopLine: Boolean = false
        var drawBottomLine: Boolean = false

        init {
            preferredSize = Dimension(18, 30)
            minimumSize = Dimension(18, 30)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create()
            try {
                val cx = width / 2
                val cy = height / 2
                val lineColor = UIManager.getColor("Separator.separatorColor") ?: JBColor(0xD0D4DA, 0x4B4F55)
                g2.color = lineColor
                if (drawTopLine) {
                    g2.drawLine(cx, 0, cx, cy - 6)
                }
                if (drawBottomLine) {
                    g2.drawLine(cx, cy + 6, cx, height)
                }
                g2.color = markerColor
                g2.fillOval(cx - 4, cy - 4, 8, 8)
            } finally {
                g2.dispose()
            }
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

    private fun collapseAll(tree: JTree) {
        var row = tree.rowCount - 1
        while (row >= 0) {
            tree.collapseRow(row)
            row -= 1
        }
    }

    private fun expandToNode(node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        var current: TreePath? = path.parentPath
        while (current != null) {
            artifactsTree.expandPath(current)
            current = current.parentPath
        }
        artifactsTree.scrollPathToVisible(path)
    }

    private fun splitPane(orientation: Int, first: Component, second: Component, resizeWeight: Double): JSplitPane =
        JSplitPane(orientation, first, second).apply {
            this.resizeWeight = resizeWeight
            dividerSize = 1
            border = null
            isContinuousLayout = true
            background = panelLineColor()
            foreground = panelLineColor()
            (ui as? BasicSplitPaneUI)?.divider?.apply {
                background = panelLineColor()
                foreground = panelLineColor()
                border = BorderFactory.createMatteBorder(
                    if (orientation == JSplitPane.VERTICAL_SPLIT) 1 else 0,
                    if (orientation == JSplitPane.HORIZONTAL_SPLIT) 1 else 0,
                    0,
                    0,
                    panelLineColor(),
                )
            }
        }

    private fun sectionPanel(title: String?, content: JComponent, padding: EmptyBorder = EmptyBorder(8, 8, 8, 8)): JPanel =
        JPanel(BorderLayout(0, 0)).apply {
            border = BorderFactory.createMatteBorder(1, 1, 1, 1, panelLineColor())
            isOpaque = true
            background = UIManager.getColor("Panel.background")
            title?.let {
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = EmptyBorder(8, 8, 6, 8)
                    add(JLabel(it).apply {
                        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                        border = EmptyBorder(0, 0, 0, 0)
                        isOpaque = false
                    }, BorderLayout.WEST)
                    add(JSeparator(SwingConstants.HORIZONTAL).apply {
                        foreground = panelLineColor()
                        background = panelLineColor()
                    }, BorderLayout.SOUTH)
                }, BorderLayout.NORTH)
            }
            add(content.apply { border = padding }, BorderLayout.CENTER)
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

    private fun toolbarButton(text: String, icon: Icon): JButton =
        JButton(text, icon).apply {
            margin = Insets(6, 10, 6, 10)
            isFocusable = false
            toolTipText = text
        }

    private fun updateHeader(build: JenkinsBuildSummary?) {
        kpiPanel.removeAll()
        if (build == null) {
            statusBadge.text = "NOT LOADED"
            statusBadge.background = JBColor(0xE0E0E0, 0x4E5257)
            statusBadge.foreground = JBColor.foreground()
            buildTitle.text = "No build selected"
            buildMeta.text = "Select a Jenkins job to inspect stages and artifacts."
            kpiPanel.add(metricChip("State", "Idle"))
            kpiPanel.revalidate()
            kpiPanel.repaint()
            return
        }
        statusBadge.text = build.state
        statusBadge.background = statusBackground(build.state)
        statusBadge.foreground = Color.WHITE
        buildTitle.text = build.fullDisplayName.ifBlank { build.displayName }
        buildMeta.text = "Build #${build.number} • ${formatDuration(build.durationMillis)}"
        kpiPanel.add(metricChip("Status", build.state))
        kpiPanel.add(metricChip("Stages", build.stages.size.toString()))
        kpiPanel.add(metricChip("Artifacts", build.artifacts.size.toString()))
        val previewableCount = build.artifacts.count { isPreviewSupported(it) }
        kpiPanel.add(metricChip("Previewable", previewableCount.toString()))
        kpiPanel.revalidate()
        kpiPanel.repaint()
    }

    private fun preferredPreviewArtifact(artifacts: List<JenkinsArtifact>): JenkinsArtifact? {
        val previewable = artifacts.filter(::isPreviewSupported)
        return previewable.firstOrNull { it.path.endsWith("index.html") && "quality" in it.path.lowercase() }
            ?: previewable.firstOrNull { it.path.endsWith("index.html") && "coverage" in it.path.lowercase() }
            ?: previewable.firstOrNull { it.path.endsWith("index.html") && "test" in it.path.lowercase() }
            ?: previewable.firstOrNull { it.path.endsWith("index.html") }
            ?: previewable.firstOrNull { it.isHtml }
            ?: previewable.firstOrNull()
    }

    private fun isPreviewSupported(artifact: JenkinsArtifact): Boolean = artifact.isHtml || isTextArtifact(artifact)

    private fun isTextArtifact(artifact: JenkinsArtifact): Boolean {
        val path = artifact.path.lowercase()
        return listOf(
            ".txt", ".log", ".md", ".json", ".xml", ".yml", ".yaml", ".csv", ".ini", ".cfg", ".properties",
            ".py", ".kt", ".kts", ".java", ".js", ".ts", ".tsx", ".jsx", ".css", ".html", ".sh",
        ).any(path::endsWith)
    }

    private fun metricChip(label: String, value: String): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        isOpaque = false
        add(secondaryLabel("$label:"))
        add(JLabel(value).apply { font = font.deriveFont(Font.BOLD) })
    }

    private fun pillLabel(text: String): JLabel = JLabel(text, SwingConstants.CENTER).apply {
        isOpaque = true
        border = EmptyBorder(6, 10, 6, 10)
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
    }

    private fun secondaryLabel(text: String): JLabel = JLabel(text).apply {
        foreground = JBColor.GRAY
    }

    private fun panelLineColor(): Color = JBColor(0x2D2F33, 0x3C3F41)

    private fun emptyState(message: String): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIManager.getColor("Panel.background")
        add(JLabel(message).apply {
            horizontalAlignment = SwingConstants.CENTER
            border = EmptyBorder(24, 24, 24, 24)
        }, BorderLayout.CENTER)
    }

    private fun accentColor(): Color = JBColor(0x2F6FBD, 0x8AB4F8)

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
