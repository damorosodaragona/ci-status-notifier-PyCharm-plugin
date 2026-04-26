package com.damorosodaragona.jenkinsnotifier

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

class CiStatusNotifierContentTest {
    @Test
    fun `buildContent includes commit state and Jenkins statuses`() {
        val notifier = CiStatusNotifier(projectStub())
        val summary = CommitStatusSummary(
            sha = "0123456789abcdef",
            state = "failure",
            totalCount = 3,
            targetUrl = null,
            statuses = listOf(
                CommitStatus("Jenkins / Tests", "failure", "tests failed", "https://ci/tests"),
                CommitStatus("continuous-integration/jenkins/pr-head", "success", "build ok", "https://ci/pr"),
                CommitStatus("lint", "success", "ok", "https://ci/lint"),
            ),
        )

        val content = notifier.buildContentForTest(summary)

        assertTrue(content.contains("Commit <code>0123456789ab</code>: failure."))
        assertTrue(content.contains("<b>Jenkins / Tests</b>: tests failed"))
        assertTrue(content.contains("<b>continuous-integration/jenkins/pr-head</b>: build ok"))
        assertTrue(!content.contains("<b>lint</b>"))
    }

    @Test
    fun `buildContent falls back to summary text when there are no important Jenkins statuses`() {
        val notifier = CiStatusNotifier(projectStub())
        val summary = CommitStatusSummary(
            sha = "fedcba9876543210",
            state = "success",
            totalCount = 1,
            targetUrl = null,
            statuses = listOf(
                CommitStatus("lint", "success", "ok", "https://ci/lint"),
            ),
        )

        val content = notifier.buildContentForTest(summary)

        assertTrue(content.contains("Commit <code>fedcba987654</code>: success. 1 statuses."))
    }

    private fun projectStub(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "test-project"
                "isDisposed" -> false
                "isOpen" -> true
                "toString" -> "Project(test-project)"
                else -> null
            }
        } as Project
}

private fun CiStatusNotifier.buildContentForTest(summary: CommitStatusSummary): String {
    val method = CiStatusNotifier::class.java.getDeclaredMethod("buildContent", CommitStatusSummary::class.java)
    method.isAccessible = true
    return method.invoke(this, summary) as String
}
