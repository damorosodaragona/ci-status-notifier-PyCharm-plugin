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

    fun start() {
        val interval = settings.pollIntervalSeconds.toLong()
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { pollSafely() },
            10,
            interval,
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
            pollJenkins()
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

    private fun pollJenkins() {
        if (settings.jenkinsBaseUrl.isBlank() || settings.jenkinsJobPath.isBlank()) {
            return
        }

        val summary = jenkins.fetchLatestBuild(
            settings.jenkinsBaseUrl,
            settings.jenkinsJobPath,
            settings.jenkinsUsername,
            settings.getJenkinsToken(),
            shaReader.currentBranch(),
        )
        if (!shouldNotifyJenkins(summary.state)) {
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
