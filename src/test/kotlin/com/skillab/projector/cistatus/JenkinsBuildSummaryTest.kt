package com.skillab.projector.cistatus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsBuildSummaryTest {
    @Test
    fun `state is RUNNING when build is still building`() {
        assertEquals("RUNNING", summary(result = null, building = true).state)
    }

    @Test
    fun `state is UNKNOWN when Jenkins result is null and build is not running`() {
        assertEquals("UNKNOWN", summary(result = null, building = false).state)
    }

    @Test
    fun `state uses Jenkins result when build is completed`() {
        assertEquals("SUCCESS", summary(result = "SUCCESS", building = false).state)
    }

    @Test
    fun `html artifact is detected from file name or path`() {
        assertTrue(JenkinsArtifact("index.html", "reports/index.html", "http://jenkins/artifact/reports/index.html", null).isHtml)
        assertTrue(JenkinsArtifact("index", "reports/index.HTML", "http://jenkins/artifact/reports/index.HTML", null).isHtml)
        assertFalse(JenkinsArtifact("report.txt", "reports/report.txt", "http://jenkins/artifact/reports/report.txt", null).isHtml)
    }

    private fun summary(result: String?, building: Boolean): JenkinsBuildSummary = JenkinsBuildSummary(
        number = 42,
        displayName = "#42",
        fullDisplayName = "projector #42",
        result = result,
        building = building,
        url = "http://jenkins/job/projector/42/",
        timestampMillis = 123456789L,
        durationMillis = 1000L,
        stages = emptyList(),
        artifacts = emptyList(),
    )
}
