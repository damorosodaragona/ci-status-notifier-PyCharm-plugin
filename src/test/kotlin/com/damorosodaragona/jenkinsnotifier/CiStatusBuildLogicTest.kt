package com.damorosodaragona.jenkinsnotifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CiStatusBuildLogicTest {
    @Test
    fun `jenkins build key uses url and build number`() {
        val summary = jenkinsSummary(number = 42, url = "https://jenkins.example/job/project/42/")

        assertEquals("https://jenkins.example/job/project/42/#42", CiStatusBuildLogic.buildKey(summary))
    }

    @Test
    fun `github fingerprint is stable regardless of status order`() {
        val first = CommitStatusSummary(
            sha = "abc123",
            state = "failure",
            totalCount = 2,
            targetUrl = null,
            statuses = listOf(
                CommitStatus("z-context", "success", "ok", "https://example.test/z"),
                CommitStatus("a-context", "failure", "broken", "https://example.test/a"),
            ),
        )
        val second = first.copy(statuses = first.statuses.reversed())

        assertEquals(CiStatusBuildLogic.fingerprint(first), CiStatusBuildLogic.fingerprint(second))
    }

    @Test
    fun `jenkins fingerprint changes when stage status changes`() {
        val running = jenkinsSummary(
            stages = listOf(JenkinsStage("1", "Build", "IN_PROGRESS", 10, null)),
        )
        val success = jenkinsSummary(
            stages = listOf(JenkinsStage("1", "Build", "SUCCESS", 10, null)),
        )

        assertTrue(CiStatusBuildLogic.fingerprint(running) != CiStatusBuildLogic.fingerprint(success))
    }

    @Test
    fun `jenkins fingerprint changes when artifact set changes`() {
        val withoutArtifact = jenkinsSummary(artifacts = emptyList())
        val withArtifact = jenkinsSummary(
            artifacts = listOf(JenkinsArtifact("index.html", "reports/index.html", "https://jenkins/artifact/reports/index.html", 100)),
        )

        assertTrue(CiStatusBuildLogic.fingerprint(withoutArtifact) != CiStatusBuildLogic.fingerprint(withArtifact))
    }

    @Test
    fun `github notification preferences are respected`() {
        assertFalse(CiStatusBuildLogic.shouldNotifyGitHub("pending", notifyPending = false, notifySuccess = true, notifyFailure = true))
        assertFalse(CiStatusBuildLogic.shouldNotifyGitHub("success", notifyPending = true, notifySuccess = false, notifyFailure = true))
        assertFalse(CiStatusBuildLogic.shouldNotifyGitHub("failure", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertFalse(CiStatusBuildLogic.shouldNotifyGitHub("error", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertTrue(CiStatusBuildLogic.shouldNotifyGitHub("unknown", notifyPending = false, notifySuccess = false, notifyFailure = false))
    }

    @Test
    fun `jenkins notification preferences are respected`() {
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("RUNNING", notifyPending = false, notifySuccess = true, notifyFailure = true))
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("SUCCESS", notifyPending = true, notifySuccess = false, notifyFailure = true))
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("FAILURE", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("FAILED", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("ERROR", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertFalse(CiStatusBuildLogic.shouldNotifyJenkins("ABORTED", notifyPending = true, notifySuccess = true, notifyFailure = false))
        assertTrue(CiStatusBuildLogic.shouldNotifyJenkins("UNSTABLE", notifyPending = false, notifySuccess = false, notifyFailure = false))
    }

    @Test
    fun `final jenkins states are recognized case-insensitively`() {
        listOf("SUCCESS", "failure", "FAILED", "error", "ABORTED", "unstable", "NOT_BUILT").forEach {
            assertTrue(CiStatusBuildLogic.isFinalJenkinsState(it), "$it should be final")
        }

        assertFalse(CiStatusBuildLogic.isFinalJenkinsState("RUNNING"))
        assertFalse(CiStatusBuildLogic.isFinalJenkinsState("UNKNOWN"))
    }

    @Test
    fun `new build is detected when build key changes or previous key is missing`() {
        assertTrue(CiStatusBuildLogic.isNewBuild("job#10", "job#9"))
        assertTrue(CiStatusBuildLogic.isNewBuild("job#10", null))
        assertFalse(CiStatusBuildLogic.isNewBuild("job#10", "job#10"))
    }

    @Test
    fun `build state change is detected against previous state`() {
        assertTrue(CiStatusBuildLogic.hasBuildStateChanged("SUCCESS", "RUNNING"))
        assertTrue(CiStatusBuildLogic.hasBuildStateChanged("RUNNING", null))
        assertFalse(CiStatusBuildLogic.hasBuildStateChanged("RUNNING", "RUNNING"))
    }

    @Test
    fun `tracked running build is finished only for same build moving from running to final state`() {
        assertTrue(
            CiStatusBuildLogic.isTrackedRunningBuildFinished(
                currentBuildKey = "job#10",
                currentState = "SUCCESS",
                previousState = "RUNNING",
                trackedRunningBuildKey = "job#10",
            ),
        )
        assertFalse(
            CiStatusBuildLogic.isTrackedRunningBuildFinished(
                currentBuildKey = "job#10",
                currentState = "RUNNING",
                previousState = "RUNNING",
                trackedRunningBuildKey = "job#10",
            ),
        )
        assertFalse(
            CiStatusBuildLogic.isTrackedRunningBuildFinished(
                currentBuildKey = "job#10",
                currentState = "SUCCESS",
                previousState = "SUCCESS",
                trackedRunningBuildKey = "job#10",
            ),
        )
        assertFalse(
            CiStatusBuildLogic.isTrackedRunningBuildFinished(
                currentBuildKey = "job#10",
                currentState = "SUCCESS",
                previousState = "RUNNING",
                trackedRunningBuildKey = "job#9",
            ),
        )
    }

    private fun jenkinsSummary(
        number: Int = 1,
        result: String? = "SUCCESS",
        building: Boolean = false,
        url: String = "https://jenkins.example/job/project/1/",
        stages: List<JenkinsStage> = emptyList(),
        artifacts: List<JenkinsArtifact> = emptyList(),
    ) = JenkinsBuildSummary(
        number = number,
        displayName = "#$number",
        fullDisplayName = "project #$number",
        result = result,
        building = building,
        url = url,
        timestampMillis = 0,
        durationMillis = 0,
        stages = stages,
        artifacts = artifacts,
    )
}

class CiStatusBuildTransitionLogicTest {
    @Test
    fun `new running build requests refresh and notifies running start`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "RUNNING",
            previousBuildKey = "job#9",
            previousState = "SUCCESS",
            trackedRunningBuildKey = null,
            lastRunningNotificationKey = null,
            currentFingerprint = "fp-running",
            lastFingerprint = "fp-success",
            reason = "light-poll",
            notifyPending = false,
            notifySuccess = true,
            notifyFailure = true,
        )

        assertTrue(decision.requestRefresh)
        assertTrue(decision.notifyRunningStarted)
        assertFalse(decision.trackedRunningFinished)
        assertFalse(decision.notifyRunningFinished)
        assertFalse(decision.storeFingerprintWhenUnchanged)
    }

    @Test
    fun `same running build does not notify running start twice`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "RUNNING",
            previousBuildKey = "job#10",
            previousState = "RUNNING",
            trackedRunningBuildKey = "job#10",
            lastRunningNotificationKey = "job#10",
            currentFingerprint = "fp-running",
            lastFingerprint = "fp-running",
            reason = "heavy-poll",
            notifyPending = true,
            notifySuccess = true,
            notifyFailure = true,
        )

        assertFalse(decision.requestRefresh)
        assertFalse(decision.notifyRunningStarted)
        assertFalse(decision.trackedRunningFinished)
        assertFalse(decision.notifyRunningFinished)
        assertTrue(decision.storeFingerprintWhenUnchanged)
    }

    @Test
    fun `tracked running build finishing with success notifies when fingerprint changed`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "SUCCESS",
            previousBuildKey = "job#10",
            previousState = "RUNNING",
            trackedRunningBuildKey = "job#10",
            lastRunningNotificationKey = "job#10",
            currentFingerprint = "fp-success",
            lastFingerprint = "fp-running",
            reason = "heavy-poll",
            notifyPending = true,
            notifySuccess = true,
            notifyFailure = true,
        )

        assertTrue(decision.requestRefresh)
        assertFalse(decision.notifyRunningStarted)
        assertTrue(decision.trackedRunningFinished)
        assertTrue(decision.notifyRunningFinished)
        assertFalse(decision.storeFingerprintWhenUnchanged)
    }

    @Test
    fun `tracked running build finishing does not notify when preference disables final state`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "FAILURE",
            previousBuildKey = "job#10",
            previousState = "RUNNING",
            trackedRunningBuildKey = "job#10",
            lastRunningNotificationKey = "job#10",
            currentFingerprint = "fp-failure",
            lastFingerprint = "fp-running",
            reason = "heavy-poll",
            notifyPending = true,
            notifySuccess = true,
            notifyFailure = false,
        )

        assertTrue(decision.requestRefresh)
        assertTrue(decision.trackedRunningFinished)
        assertFalse(decision.notifyRunningFinished)
    }

    @Test
    fun `tracked running build finishing does not notify duplicate fingerprint`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "SUCCESS",
            previousBuildKey = "job#10",
            previousState = "RUNNING",
            trackedRunningBuildKey = "job#10",
            lastRunningNotificationKey = "job#10",
            currentFingerprint = "fp-success",
            lastFingerprint = "fp-success",
            reason = "heavy-poll",
            notifyPending = true,
            notifySuccess = true,
            notifyFailure = true,
        )

        assertTrue(decision.trackedRunningFinished)
        assertFalse(decision.notifyRunningFinished)
    }

    @Test
    fun `push detected requests refresh even when build and state did not change`() {
        val decision = CiStatusBuildLogic.evaluateJenkinsTransition(
            currentBuildKey = "job#10",
            currentState = "SUCCESS",
            previousBuildKey = "job#10",
            previousState = "SUCCESS",
            trackedRunningBuildKey = null,
            lastRunningNotificationKey = null,
            currentFingerprint = "fp-success",
            lastFingerprint = "fp-success",
            reason = "push-detected",
            notifyPending = true,
            notifySuccess = true,
            notifyFailure = true,
        )

        assertTrue(decision.requestRefresh)
        assertFalse(decision.storeFingerprintWhenUnchanged)
    }
}
