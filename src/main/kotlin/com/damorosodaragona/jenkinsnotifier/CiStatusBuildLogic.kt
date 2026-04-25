package com.damorosodaragona.jenkinsnotifier

internal data class JenkinsTransitionDecision(
    val requestRefresh: Boolean,
    val notifyRunningStarted: Boolean,
    val trackedRunningFinished: Boolean,
    val notifyRunningFinished: Boolean,
    val storeFingerprintWhenUnchanged: Boolean,
)

internal object CiStatusBuildLogic {
    fun buildKey(summary: JenkinsBuildSummary): String = "${summary.url}#${summary.number}"

    fun shouldNotifyGitHub(state: String, notifyPending: Boolean, notifySuccess: Boolean, notifyFailure: Boolean): Boolean {
        return when (state) {
            "pending" -> notifyPending
            "success" -> notifySuccess
            "failure", "error" -> notifyFailure
            else -> true
        }
    }

    fun shouldNotifyJenkins(state: String, notifyPending: Boolean, notifySuccess: Boolean, notifyFailure: Boolean): Boolean {
        return when (state) {
            "RUNNING" -> notifyPending
            "SUCCESS" -> notifySuccess
            "FAILURE", "FAILED", "ERROR", "ABORTED" -> notifyFailure
            else -> true
        }
    }

    fun isFinalJenkinsState(state: String): Boolean {
        return state.uppercase() in setOf("SUCCESS", "FAILURE", "FAILED", "ERROR", "ABORTED", "UNSTABLE", "NOT_BUILT")
    }

    fun isNewBuild(currentBuildKey: String, previousBuildKey: String?): Boolean {
        return currentBuildKey != previousBuildKey
    }

    fun hasBuildStateChanged(currentState: String, previousState: String?): Boolean {
        return currentState != previousState
    }

    fun isTrackedRunningBuildFinished(
        currentBuildKey: String,
        currentState: String,
        previousState: String?,
        trackedRunningBuildKey: String?,
    ): Boolean {
        return trackedRunningBuildKey == currentBuildKey &&
            previousState == "RUNNING" &&
            isFinalJenkinsState(currentState)
    }

    fun evaluateJenkinsTransition(
        currentBuildKey: String,
        currentState: String,
        previousBuildKey: String?,
        previousState: String?,
        trackedRunningBuildKey: String?,
        lastRunningNotificationKey: String?,
        currentFingerprint: String,
        lastFingerprint: String?,
        reason: String,
        notifyPending: Boolean,
        notifySuccess: Boolean,
        notifyFailure: Boolean,
    ): JenkinsTransitionDecision {
        val newBuild = isNewBuild(currentBuildKey, previousBuildKey)
        val stateChanged = hasBuildStateChanged(currentState, previousState)
        val requestRefresh = newBuild || stateChanged || reason == "push-detected"

        val notifyRunningStarted = currentState == "RUNNING" &&
            (newBuild || stateChanged) &&
            lastRunningNotificationKey != currentBuildKey

        val trackedRunningFinished = isTrackedRunningBuildFinished(
            currentBuildKey = currentBuildKey,
            currentState = currentState,
            previousState = previousState,
            trackedRunningBuildKey = trackedRunningBuildKey,
        )

        val notifyRunningFinished = trackedRunningFinished &&
            shouldNotifyJenkins(currentState, notifyPending, notifySuccess, notifyFailure) &&
            currentFingerprint != lastFingerprint

        return JenkinsTransitionDecision(
            requestRefresh = requestRefresh,
            notifyRunningStarted = notifyRunningStarted,
            trackedRunningFinished = trackedRunningFinished,
            notifyRunningFinished = notifyRunningFinished,
            storeFingerprintWhenUnchanged = !stateChanged && !newBuild && reason != "push-detected",
        )
    }

    fun fingerprint(summary: CommitStatusSummary): String {
        val statusFingerprint = summary.statuses
            .sortedBy { it.context }
            .joinToString("|") { "${it.context}:${it.state}:${it.description}:${it.targetUrl}" }
        return "${summary.sha}:${summary.state}:$statusFingerprint"
    }

    fun fingerprint(summary: JenkinsBuildSummary): String {
        val stageFingerprint = summary.stages
            .joinToString("|") { "${it.id}:${it.name}:${it.status}:${it.durationMillis}" }
        val artifactFingerprint = summary.artifacts
            .joinToString("|") { "${it.path}:${it.url}:${it.size}" }
        return "${summary.number}:${summary.state}:$stageFingerprint:$artifactFingerprint"
    }
}
