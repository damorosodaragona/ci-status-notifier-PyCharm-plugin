package com.damorosodaragona.jenkinsnotifier

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
    private val jenkins = JenkinsStatusClient(project)
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

    @Volatile
    private var authenticationPaused: Boolean = false

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
        project.messageBus.connect(this).subscribe(CiStatusRefreshListener.TOPIC, object : CiStatusRefreshListener {
            override fun refreshRequested(reason: String) {
                authenticationPaused = false
                nextLightPollAtMillis = 0L
                nextHeavyPollAtMillis = 0L
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
        } catch (error: JenkinsAuthenticationExpiredException) {
            authenticationPaused = true
            heavyPollingActive = false
            trackedRunningBuildKey = null
            notifier.notifyJenkinsAuthenticationExpired {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val recovered = KeycloakSessionService.getInstance(project).ensureLoggedIn(settings.jenkinsBaseUrl)
                    if (recovered) {
                        authenticationPaused = false
                        nextLightPollAtMillis = 0L
                        requestToolWindowRefresh("keycloak-login")
                    }
                }
            }
        } catch (error: Exception) {
            log.warn("Could not poll CI statuses", error)
        } finally {
            running.set(false)
        }
    }

    private fun poll() {
        if (project.isDisposed || !settings.enabled || authenticationPaused) {
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
        if (!CiStatusBuildLogic.shouldNotifyGitHub(summary.state, settings.notifyPending, settings.notifySuccess, settings.notifyFailure)) {
            lastFingerprint = CiStatusBuildLogic.fingerprint(summary)
            return
        }

        val fingerprint = CiStatusBuildLogic.fingerprint(summary)
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
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) {
                jenkins.fetchLatestBuildForJobUrl(monitoredJobUrl, settings.jenkinsUsername, settings.getJenkinsToken())
            }
        } else {
            JenkinsStatusClient.withRequestMode(JenkinsRequestMode.BACKGROUND) {
                jenkins.fetchLatestBuild(
                    settings.jenkinsBaseUrl,
                    settings.jenkinsJobPath,
                    settings.jenkinsUsername,
                    settings.getJenkinsToken(),
                    shaReader.currentBranch(),
                )
            }
        }
        handleJenkinsSummary(summary, reason)
    }

    private fun handleJenkinsSummary(summary: JenkinsBuildSummary, reason: String) {
        val buildKey = CiStatusBuildLogic.buildKey(summary)
        val state = summary.state
        val fingerprint = CiStatusBuildLogic.fingerprint(summary)
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = buildKey,
            currentState = state,
            previousBuildKey = lastSeenBuildKey,
            previousState = lastSeenBuildState,
            trackedRunningBuildKey = trackedRunningBuildKey,
            lastRunningNotificationKey = lastRunningNotificationKey,
            currentFingerprint = fingerprint,
            lastFingerprint = lastFingerprint,
            reason = reason,
            notifyPending = settings.notifyPending,
            notifySuccess = settings.notifySuccess,
            notifyFailure = settings.notifyFailure,
        )

        if (decision.requestRefresh) {
            requestToolWindowRefresh(reason)
        }

        if (state == "RUNNING") {
            heavyPollingActive = true
            trackedRunningBuildKey = buildKey
            nextHeavyPollAtMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(HEAVY_POLL_SECONDS)
            if (decision.notifyRunningStarted) {
                lastRunningNotificationKey = buildKey
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        notifier.notify(summary)
                    }
                }
            }
        } else if (decision.trackedRunningFinished) {
            heavyPollingActive = false
            trackedRunningBuildKey = null
            nextLightPollAtMillis = System.currentTimeMillis() + lightPollMillis()
            if (decision.notifyRunningFinished) {
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
        if (decision.storeFingerprintWhenUnchanged) {
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


    override fun dispose() {
        future?.cancel(true)
        future = null
    }
}
