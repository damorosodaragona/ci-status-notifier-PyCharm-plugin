package com.skillab.projector.cistatus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CiStatusStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val watcher = CiStatusWatcher(project)
        Disposer.register(project, watcher)
        watcher.start()
    }
}

private class CiStatusWatcher(private val project: Project) : Disposable {
    private val log = Logger.getInstance(CiStatusWatcher::class.java)
    private val settings = CiStatusSettings.getInstance(project)
    private val shaReader = GitShaReader(project)
    private val github = GitHubStatusClient()
    private val jenkins = JenkinsStatusClient()
    private val notifier = CiStatusNotifier(project)
    private val running = AtomicBoolean(false)

    @Volatile
    private var future: ScheduledFuture<*>? = null

    @Volatile
    private var lastFingerprint: String? = null

    @Volatile
    private var lastOutgoingCommitCount: Int? = null

    @Volatile
    private var lastObservedSha: String? = null

    @Volatile
    private var lastObservedBranch: String? = null

    @Volatile
    private var lastDetectedPushedSha: String? = null

    @Volatile
    private var lastSeenBuildKey: String? = null

    @Volatile
    private var lastSeenBuildState: String? = null

    @Volatile
    private var lastRunningNotificationKey: String? = null

    @Volatile
    private var trackedRunningBuildKey: String? = null

    @Volatile
    private var observedJenkinsJobUrl: String? = null

    @Volatile
    private var nextLightPollAtMillis: Long = 0L

    @Volatile
    private var nextHeavyPollAtMillis: Long = 0L

    @Volatile
    private var heavyPollingActive: Boolean = false

    @Volatile
    private var boostedPollingUntilMillis: Long = 0L

    companion object {
        private const val BASE_TICK_SECONDS = 5L
        private const val INITIAL_DELAY_SECONDS = 3L
        private const val HEAVY_POLL_SECONDS = 10L
        private const val POST_GIT_ACTIVITY_POLL_SECONDS = 180L
    }

    fun start() {
        project.messageBus.connect(this).subscribe(CiStatusJenkinsBuildListener.TOPIC, object : CiStatusJenkinsBuildListener {
            override fun buildObserved(jobUrl: String, summary: JenkinsBuildSummary) {
                observedJenkinsJobUrl = jobUrl.trimEnd('/')
                handleJenkinsSummary(summary, "tool-window-observed")
            }
        })

        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { pollSafely() },
            INITIAL_DELAY_SECONDS,
            BASE_TICK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun pollSafely() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            poll()
        } catch (error: Exception) {
            log.warn("Could not poll CI statuses", error)
        } finally {
            running.set(false)
        }
    }

    private fun poll() {
        if (project.isDisposed || !settings.enabled) {
            return
        }

        if (settings.provider == "jenkins") {
            pollJenkinsLoop()
        } else {
            pollGitHub()
        }
    }

    private fun pollGitHub() {
        val repository = settings.repository.ifBlank { shaReader.originRepository().orEmpty() }
        if (!repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            return
        }

        val sha = shaReader.currentSha() ?: return
        val summary = github.fetch(repository, sha, settings.getToken())
        if (!shouldNotify(summary.state)) {
            lastFingerprint = fingerprint(summary)
            return
        }

        val fingerprint = fingerprint(summary)
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    notifier.notify(summary)
                }
            }
        }
    }

    private fun pollJenkinsLoop() {
        if (settings.jenkinsBaseUrl.isBlank()) {
            return
        }

        val now = System.currentTimeMillis()
        val headChanged = detectHeadChange()
        val pushDetected = detectPush()
        if (headChanged || pushDetected) {
            if (headChanged) {
                observedJenkinsJobUrl = null
            }
            startBoostedPolling(now)
            fetchAndHandleJenkinsSummary(if (pushDetected) "push-detected" else "head-changed")
            nextHeavyPollAtMillis = now + TimeUnit.SECONDS.toMillis(HEAVY_POLL_SECONDS)
            return
        }

        if (heavyPollingActive || isBoostedPolling(now)) {
            if (now >= nextHeavyPollAtMillis) {
                fetchAndHandleJenkinsSummary(if (heavyPollingActive) "heavy-poll" else "post-git-activity-poll")
                nextHeavyPollAtMillis = now + TimeUnit.SECONDS.toMillis(HEAVY_POLL_SECONDS)
            }
            return
        }

        if (nextLightPollAtMillis == 0L || now >= nextLightPollAtMillis) {
            fetchAndHandleJenkinsSummary("light-poll")
            nextLightPollAtMillis = now + lightPollMillis()
        }
    }

    private fun detectHeadChange(): Boolean {
        val currentSha = shaReader.currentSha() ?: return false
        val currentBranch = shaReader.currentBranch()
        val previousSha = lastObservedSha
        val previousBranch = lastObservedBranch
        lastObservedSha = currentSha
        lastObservedBranch = currentBranch
        return previousSha != null && (currentSha != previousSha || currentBranch != previousBranch)
    }

    private fun detectPush(): Boolean {
        val currentSha = shaReader.currentSha() ?: return false
        val current = shaReader.outgoingCommitCount() ?: return false
        val previous = lastOutgoingCommitCount
        lastOutgoingCommitCount = current
        if (previous != null && previous > 0 && current == 0) {
            lastDetectedPushedSha = currentSha
            return true
        }

        if (current > 0 && lastDetectedPushedSha != currentSha && shaReader.originBranchSha() == currentSha) {
            lastDetectedPushedSha = currentSha
            return true
        }

        return false
    }

    private fun fetchAndHandleJenkinsSummary(reason: String) {
        val monitoredJobUrl = observedJenkinsJobUrl
        val summary = if (!monitoredJobUrl.isNullOrBlank()) {
            jenkins.fetchLatestBuildForJobUrl(monitoredJobUrl, settings.jenkinsUsername, settings.getJenkinsToken())
        } else {
            jenkins.fetchLatestBuild(
                settings.jenkinsBaseUrl,
                settings.jenkinsJobPath,
                settings.jenkinsUsername,
                settings.getJenkinsToken(),
                shaReader.currentBranch(),
            )
        }
        handleJenkinsSummary(summary, reason)
    }

    private fun handleJenkinsSummary(summary: JenkinsBuildSummary, reason: String) {
        val buildKey = buildKey(summary)
        val state = summary.state
        val previousBuildKey = lastSeenBuildKey
        val previousState = lastSeenBuildState
        val isNewBuild = buildKey != previousBuildKey
        val stateChanged = state != previousState
        val fingerprint = fingerprint(summary)

        if (isNewBuild || stateChanged || reason == "push-detected") {
            requestToolWindowRefresh(reason)
        }

        if (state == "RUNNING") {
            heavyPollingActive = true
            trackedRunningBuildKey = buildKey
            nextHeavyPollAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(HEAVY_POLL_SECONDS)
            if ((isNewBuild || stateChanged) && lastRunningNotificationKey != buildKey) {
                lastRunningNotificationKey = buildKey
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        notifier.notify(summary)
                    }
                }
            }
        } else if (trackedRunningBuildKey == buildKey && previousState == "RUNNING" && isFinalJenkinsState(state)) {
            heavyPollingActive = false
            trackedRunningBuildKey = null
            nextLightPollAtMillis = System.currentTimeMillis() + lightPollMillis()
            if (shouldNotifyJenkins(state) && fingerprint != lastFingerprint) {
                lastFingerprint = fingerprint
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        notifier.notify(summary)
                    }
                }
            }
        }

        if (!heavyPollingActive && state != "RUNNING") {
            trackedRunningBuildKey = null
        }

        lastSeenBuildKey = buildKey
        lastSeenBuildState = state
        if (!stateChanged && !isNewBuild && reason != "push-detected") {
            lastFingerprint = fingerprint
        }
    }

    private fun startBoostedPolling(now: Long) {
        boostedPollingUntilMillis = now + TimeUnit.SECONDS.toMillis(POST_GIT_ACTIVITY_POLL_SECONDS)
    }

    private fun isBoostedPolling(now: Long): Boolean = now < boostedPollingUntilMillis

    private fun requestToolWindowRefresh(reason: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(CiStatusRefreshListener.TOPIC).refreshRequested(reason)
            }
        }
    }

    private fun lightPollMillis(): Long = TimeUnit.SECONDS.toMillis(settings.pollIntervalSeconds.toLong())

    private fun buildKey(summary: JenkinsBuildSummary): String = "${summary.url}#${summary.number}"

    private fun shouldNotify(state: String): Boolean {
        return when (state) {
            "pending" -> settings.notifyPending
            "success" -> settings.notifySuccess
            "failure", "error" -> settings.notifyFailure
            else -> true
        }
    }

    private fun shouldNotifyJenkins(state: String): Boolean {
        return when (state) {
            "RUNNING" -> settings.notifyPending
            "SUCCESS" -> settings.notifySuccess
            "FAILURE", "FAILED", "ERROR", "ABORTED" -> settings.notifyFailure
            else -> true
        }
    }

    private fun isFinalJenkinsState(state: String): Boolean = state.uppercase() in setOf("SUCCESS", "FAILURE", "FAILED", "ERROR", "ABORTED", "UNSTABLE", "NOT_BUILT")

    private fun fingerprint(summary: CommitStatusSummary): String {
        val statusFingerprint = summary.statuses
            .sortedBy { it.context }
            .joinToString("|") { "${it.context}:${it.state}:${it.description}:${it.targetUrl}" }
        return "${summary.sha}:${summary.state}:$statusFingerprint"
    }

    private fun fingerprint(summary: JenkinsBuildSummary): String {
        val stageFingerprint = summary.stages
            .joinToString("|") { "${it.id}:${it.name}:${it.status}:${it.durationMillis}" }
        val artifactFingerprint = summary.artifacts
            .joinToString("|") { "${it.path}:${it.url}:${it.size}" }
        return "${summary.number}:${summary.state}:$stageFingerprint:$artifactFingerprint"
    }

    override fun dispose() {
        future?.cancel(true)
        future = null
    }
}
