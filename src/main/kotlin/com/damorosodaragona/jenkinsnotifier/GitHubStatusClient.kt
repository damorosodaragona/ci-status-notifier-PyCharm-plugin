package com.damorosodaragona.jenkinsnotifier

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CommitStatusSummary(
    val sha: String,
    val state: String,
    val totalCount: Int,
    val targetUrl: String?,
    val statuses: List<CommitStatus>,
)

data class CommitStatus(
    val context: String,
    val state: String,
    val description: String,
    val targetUrl: String?,
)

class GitHubStatusClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun fetch(repository: String, sha: String, token: String): CommitStatusSummary {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/$repository/commits/$sha/status"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "jenkins-notifier")

        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("GitHub returned HTTP ${response.statusCode()}")
        }

        val root = JsonParser.parseString(response.body()).asJsonObject
        val statuses = root.getAsJsonArray("statuses").map { item ->
            val status = item.asJsonObject
            CommitStatus(
                context = status.get("context")?.asString.orEmpty(),
                state = status.get("state")?.asString.orEmpty(),
                description = status.get("description")?.asString.orEmpty(),
                targetUrl = status.get("target_url")?.takeUnless { it.isJsonNull }?.asString,
            )
        }

        return CommitStatusSummary(
            sha = sha,
            state = root.get("state")?.asString.orEmpty(),
            totalCount = root.get("total_count")?.asInt ?: statuses.size,
            targetUrl = bestTargetUrl(statuses),
            statuses = statuses,
        )
    }

    private fun bestTargetUrl(statuses: List<CommitStatus>): String? {
        return statuses.firstOrNull { it.state in setOf("failure", "error") && !it.targetUrl.isNullOrBlank() }?.targetUrl
            ?: statuses.firstOrNull { it.context == "Jenkins / Tests" && !it.targetUrl.isNullOrBlank() }?.targetUrl
            ?: statuses.firstOrNull { it.context == "Jenkins / Coverage Gate" && !it.targetUrl.isNullOrBlank() }?.targetUrl
            ?: statuses.firstOrNull { it.context == "Jenkins / Code Quality" && !it.targetUrl.isNullOrBlank() }?.targetUrl
            ?: statuses.firstOrNull { !it.targetUrl.isNullOrBlank() }?.targetUrl
    }
}
