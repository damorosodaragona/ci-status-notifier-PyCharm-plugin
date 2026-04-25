package com.damorosodaragona.jenkinsnotifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubStatusClientTest {
    @Test
    fun `best target URL prefers failing status URL`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Tests", state = "success", targetUrl = "https://ci/success"),
                status(context = "Other", state = "failure", targetUrl = "https://ci/failure"),
            ),
        )

        assertEquals("https://ci/failure", url)
    }

    @Test
    fun `best target URL prefers Jenkins Tests when there are no failures`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Coverage Gate", state = "success", targetUrl = "https://ci/coverage"),
                status(context = "Jenkins / Tests", state = "success", targetUrl = "https://ci/tests"),
            ),
        )

        assertEquals("https://ci/tests", url)
    }

    @Test
    fun `best target URL falls back to coverage then quality then first available URL`() {
        assertEquals(
            "https://ci/coverage",
            bestTargetUrl(listOf(status(context = "Jenkins / Coverage Gate", targetUrl = "https://ci/coverage"))),
        )
        assertEquals(
            "https://ci/quality",
            bestTargetUrl(listOf(status(context = "Jenkins / Code Quality", targetUrl = "https://ci/quality"))),
        )
        assertEquals(
            "https://ci/other",
            bestTargetUrl(listOf(status(context = "Other", targetUrl = "https://ci/other"))),
        )
    }

    @Test
    fun `best target URL ignores blank and null URLs`() {
        val url = bestTargetUrl(
            listOf(
                status(context = "Jenkins / Tests", targetUrl = ""),
                status(context = "Other", state = "failure", targetUrl = null),
            ),
        )

        assertNull(url)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bestTargetUrl(statuses: List<CommitStatus>): String? {
        val method = GitHubStatusClient::class.java.getDeclaredMethod("bestTargetUrl", List::class.java)
        method.isAccessible = true
        return method.invoke(GitHubStatusClient(), statuses) as String?
    }

    private fun status(
        context: String,
        state: String = "success",
        description: String = "",
        targetUrl: String? = null,
    ): CommitStatus = CommitStatus(
        context = context,
        state = state,
        description = description,
        targetUrl = targetUrl,
    )
}
