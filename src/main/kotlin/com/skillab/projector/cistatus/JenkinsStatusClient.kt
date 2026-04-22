package com.skillab.projector.cistatus

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

data class JenkinsBuildSummary(
    val number: Int,
    val displayName: String,
    val fullDisplayName: String,
    val result: String?,
    val building: Boolean,
    val url: String,
    val timestampMillis: Long,
    val durationMillis: Long,
    val stages: List<JenkinsStage>,
    val artifacts: List<JenkinsArtifact>,
) {
    val state: String
        get() = when {
            building -> "RUNNING"
            result.isNullOrBlank() -> "UNKNOWN"
            else -> result
        }
}

data class JenkinsStage(
    val id: String,
    val name: String,
    val status: String,
    val durationMillis: Long,
    val url: String?,
)

data class JenkinsArtifact(
    val name: String,
    val path: String,
    val url: String,
    val size: Long?,
) {
    val isHtml: Boolean
        get() = name.endsWith(".html", ignoreCase = true) || path.endsWith(".html", ignoreCase = true)
}

class JenkinsStatusClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetchLatestBuild(baseUrl: String, jobPath: String, username: String, token: String): JenkinsBuildSummary {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val jobUrl = "$normalizedBaseUrl/${normalizeJobPath(jobPath)}"
        val buildJson = getJson(
            "$jobUrl/lastBuild/api/json?tree=number,displayName,fullDisplayName,result,building,url,timestamp,duration,artifacts[fileName,relativePath]",
            username,
            token,
        )

        val buildUrl = buildJson.string("url").ifBlank {
            "$jobUrl/${buildJson.int("number")}/"
        }.trimEnd('/') + "/"
        val stages = fetchStages(buildUrl, username, token)
        val artifacts = fetchArtifacts(buildUrl, buildJson, username, token)

        return JenkinsBuildSummary(
            number = buildJson.int("number"),
            displayName = buildJson.string("displayName").ifBlank { "#${buildJson.int("number")}" },
            fullDisplayName = buildJson.string("fullDisplayName"),
            result = buildJson.nullableString("result"),
            building = buildJson.boolean("building"),
            url = buildUrl,
            timestampMillis = buildJson.long("timestamp"),
            durationMillis = buildJson.long("duration"),
            stages = stages,
            artifacts = artifacts,
        )
    }

    private fun fetchStages(buildUrl: String, username: String, token: String): List<JenkinsStage> {
        val workflow = runCatching { getJson("${buildUrl}wfapi/describe", username, token) }.getOrNull() ?: return emptyList()
        return workflow.array("stages").mapNotNull { item ->
            val stage = item.asJsonObjectOrNull() ?: return@mapNotNull null
            val href = stage.obj("_links")?.obj("self")?.string("href")
            JenkinsStage(
                id = stage.string("id"),
                name = stage.string("name"),
                status = stage.string("status"),
                durationMillis = stage.long("durationMillis"),
                url = href?.let { resolveJenkinsUrl(buildUrl, it) },
            )
        }
    }

    private fun fetchArtifacts(
        buildUrl: String,
        buildJson: JsonObject,
        username: String,
        token: String,
    ): List<JenkinsArtifact> {
        val workflowArtifacts = runCatching {
            getArray("${buildUrl}wfapi/artifacts", username, token).mapNotNull { item ->
                val artifact = item.asJsonObjectOrNull() ?: return@mapNotNull null
                val url = artifact.string("url")
                JenkinsArtifact(
                    name = artifact.string("name"),
                    path = artifact.string("path"),
                    url = resolveJenkinsUrl(buildUrl, url),
                    size = artifact.nullableLong("size"),
                )
            }
        }.getOrDefault(emptyList())

        if (workflowArtifacts.isNotEmpty()) {
            return workflowArtifacts
        }

        return buildJson.array("artifacts").mapNotNull { item ->
            val artifact = item.asJsonObjectOrNull() ?: return@mapNotNull null
            val relativePath = artifact.string("relativePath")
            JenkinsArtifact(
                name = artifact.string("fileName").ifBlank { relativePath.substringAfterLast('/') },
                path = relativePath,
                url = "${buildUrl}artifact/${relativePath.split('/').joinToString("/") { encodePathSegment(it) }}",
                size = null,
            )
        }
    }

    private fun getJson(url: String, username: String, token: String): JsonObject {
        val response = httpClient.send(request(url, username, token), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw JenkinsHttpException(response.statusCode(), url, response.headers().firstValue("Location").orElse(null))
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun getArray(url: String, username: String, token: String): List<JsonElement> {
        val response = httpClient.send(request(url, username, token), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw JenkinsHttpException(response.statusCode(), url, response.headers().firstValue("Location").orElse(null))
        }
        return JsonParser.parseString(response.body()).asJsonArray.toList()
    }

    private fun request(url: String, username: String, token: String): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("User-Agent", "skillab-ci-status-notifier")

        if (username.isNotBlank() && token.isNotBlank()) {
            val encoded = Base64.getEncoder()
                .encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", "Basic $encoded")
        }

        return builder.build()
    }

    private fun normalizeJobPath(jobPath: String): String {
        val trimmed = jobPath.trim().trim('/')
        if (trimmed.contains("/job/") || trimmed.startsWith("job/")) {
            return trimmed
        }
        return trimmed.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { "job/${encodePathSegment(it)}" }
    }

    private fun resolveJenkinsUrl(buildUrl: String, value: String): String {
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> URI.create(buildUrl).resolve(value).toString()
            else -> URI.create(buildUrl).resolve(value).toString()
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonObject.string(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.nullableString(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.int(name: String): Int =
    get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0

private fun JsonObject.long(name: String): Long =
    get(name)?.takeUnless { it.isJsonNull }?.asLong ?: 0L

private fun JsonObject.nullableLong(name: String): Long? =
    get(name)?.takeUnless { it.isJsonNull }?.asLong

private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

private fun JsonObject.array(name: String): List<JsonElement> =
    getAsJsonArray(name)?.toList().orEmpty()

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeUnless { it.isJsonNull }?.asJsonObjectOrNull()

class JenkinsHttpException(statusCode: Int, url: String, location: String?) : IllegalStateException(
    buildString {
        append("Jenkins returned HTTP ")
        append(statusCode)
        append(" for ")
        append(url)
        if (statusCode in 300..399 && !location.isNullOrBlank()) {
            append(" and redirected to ")
            append(location)
            append(". Check the Jenkins job path and credentials.")
        }
    }
)
