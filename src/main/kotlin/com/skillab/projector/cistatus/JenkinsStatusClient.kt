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

data class JenkinsJobCandidate(
    val name: String,
    val url: String,
)

data class JenkinsJobNode(
    val name: String,
    val url: String,
    val color: String,
    val buildable: Boolean,
    val lastBuildNumber: Int?,
    val lastBuildResult: String?,
    val lastBuildBuilding: Boolean,
    val children: List<JenkinsJobNode>,
) {
    val isBuildJob: Boolean
        get() = lastBuildNumber != null || (buildable && children.isEmpty())
}

data class JenkinsJobTree(
    val root: JenkinsJobNode,
    val autoSelected: JenkinsJobNode?,
)

class JenkinsStatusClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetchLatestBuild(
        baseUrl: String,
        jobPath: String,
        username: String,
        token: String,
        preferredBranch: String? = null,
    ): JenkinsBuildSummary {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val configuredJobUrl = buildJenkinsUrl(normalizedBaseUrl, jobPath)
        val jobUrl = resolveBuildableJobUrl(configuredJobUrl, username, token, preferredBranch)
        return fetchLatestBuildForJobUrl(jobUrl, username, token)
    }

    fun fetchLatestBuildForJobUrl(jobUrl: String, username: String, token: String): JenkinsBuildSummary {
        val normalizedJobUrl = jobUrl.trimEnd('/')
        val buildJson = getJson(
            "$normalizedJobUrl/lastBuild/api/json?tree=number,displayName,fullDisplayName,result,building,url,timestamp,duration,artifacts[fileName,relativePath]",
            username,
            token,
        )

        val buildUrl = buildJson.string("url").ifBlank {
            "$normalizedJobUrl/${buildJson.int("number")}/"
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

    fun fetchJobTree(
        baseUrl: String,
        jobPath: String,
        username: String,
        token: String,
        preferredBranch: String?,
    ): JenkinsJobTree {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val rootUrl = buildJenkinsUrl(normalizedBaseUrl, jobPath)
        val root = fetchJobNode(rootUrl, username, token, 0, maxDepth = 5, visited = mutableSetOf())
        return JenkinsJobTree(root, findAutoSelected(root, preferredBranch))
    }

    private fun resolveBuildableJobUrl(
        configuredJobUrl: String,
        username: String,
        token: String,
        preferredBranch: String?,
    ): String {
        val jobJson = getJson(
            "$configuredJobUrl/api/json?tree=buildable,lastBuild[number],jobs[name,url,color]",
            username,
            token,
        )
        if (jobJson.obj("lastBuild") != null || jobJson.boolean("buildable")) {
            return configuredJobUrl
        }

        val jobs = jobJson.array("jobs").mapNotNull { item ->
            val job = item.asJsonObjectOrNull() ?: return@mapNotNull null
            JenkinsJobCandidate(job.string("name"), job.string("url").trimEnd('/'))
        }
        if (jobs.isEmpty()) {
            return configuredJobUrl
        }

        val preferred = preferredBranch?.let { branch ->
            jobs.firstOrNull { it.name.equals(branch, ignoreCase = true) }
                ?: jobs.firstOrNull { it.name.equals(branch.replace("/", "%2F"), ignoreCase = true) }
                ?: jobs.firstOrNull { it.name.equals(branch.replace("/", "%252F"), ignoreCase = true) }
        }

        return (preferred ?: jobs.firstOrNull { it.name.equals("main", ignoreCase = true) } ?: jobs.first()).url
    }

    private fun fetchJobNode(
        jobUrl: String,
        username: String,
        token: String,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
    ): JenkinsJobNode {
        val normalizedJobUrl = jobUrl.trimEnd('/')
        if (!visited.add(normalizedJobUrl)) {
            return JenkinsJobNode(normalizedJobUrl.substringAfterLast('/'), normalizedJobUrl, "", false, null, null, false, emptyList())
        }

        val jobJson = getJson(
            "$normalizedJobUrl/api/json?tree=name,displayName,url,color,buildable,lastBuild[number,result,building],jobs[name,displayName,url,color]",
            username,
            token,
        )
        val childSeeds = if (depth < maxDepth) {
            jobJson.array("jobs").mapNotNull { it.asJsonObjectOrNull() }
        } else {
            emptyList()
        }
        val children = childSeeds.mapNotNull { child ->
            val childUrl = child.string("url").ifBlank { return@mapNotNull null }
            runCatching { fetchJobNode(childUrl, username, token, depth + 1, maxDepth, visited) }
                .getOrNull()
        }
        val lastBuild = jobJson.obj("lastBuild")

        return JenkinsJobNode(
            name = jobJson.string("displayName").ifBlank { jobJson.string("name").ifBlank { normalizedJobUrl.substringAfterLast('/') } },
            url = jobJson.string("url").ifBlank { normalizedJobUrl },
            color = jobJson.string("color"),
            buildable = jobJson.boolean("buildable"),
            lastBuildNumber = lastBuild?.nullableInt("number"),
            lastBuildResult = lastBuild?.nullableString("result"),
            lastBuildBuilding = lastBuild?.boolean("building") ?: jobJson.string("color").endsWith("_anime"),
            children = children,
        )
    }

    private fun findAutoSelected(root: JenkinsJobNode, preferredBranch: String?): JenkinsJobNode? {
        val buildJobs = flatten(root).filter { it.isBuildJob }
        if (buildJobs.isEmpty()) {
            return null
        }

        val branchNames = preferredBranch?.let { branch ->
            listOf(
                branch,
                branch.replace("/", "%2F"),
                branch.replace("/", "%252F"),
                branch.substringAfterLast('/'),
            ).distinct()
        }.orEmpty()

        return branchNames.firstNotNullOfOrNull { branch ->
            buildJobs.firstOrNull { it.name.equals(branch, ignoreCase = true) }
                ?: buildJobs.firstOrNull { it.url.trimEnd('/').substringAfterLast('/').equals(branch, ignoreCase = true) }
        }
            ?: buildJobs.firstOrNull { it.name.equals("main", ignoreCase = true) }
            ?: buildJobs.first()
    }

    private fun flatten(node: JenkinsJobNode): List<JenkinsJobNode> =
        listOf(node) + node.children.flatMap(::flatten)

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
        if (trimmed.isBlank()) {
            return ""
        }
        if (trimmed.contains("/job/") || trimmed.startsWith("job/")) {
            return trimmed
        }
        return trimmed.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { "job/${encodeJobName(it)}" }
    }

    private fun buildJenkinsUrl(normalizedBaseUrl: String, jobPath: String): String {
        val normalizedPath = normalizeJobPath(jobPath)
        return if (normalizedPath.isBlank()) normalizedBaseUrl else "$normalizedBaseUrl/$normalizedPath"
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

    private fun encodeJobName(value: String): String =
        encodePathSegment(value).replace("%2F", "%252F")
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun JsonObject.string(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.nullableString(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.int(name: String): Int =
    get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0

private fun JsonObject.nullableInt(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.asInt

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
        when (statusCode) {
            401 -> append("Jenkins authentication failed or this token cannot read the requested Jenkins root. Check the Jenkins username/API token, grant Overall/Read for root scans, or set a narrower Jenkins scan root. ")
            403 -> append("Jenkins denied access. The user may not have permission for this job, or Jenkins may require authentication. ")
            404 -> append("Jenkins job was not found. Check the Jenkins scan root. ")
            else -> {
                append("Jenkins returned HTTP ")
                append(statusCode)
                append(". ")
            }
        }
        append("URL: ")
        append(url)
        if (statusCode in 300..399 && !location.isNullOrBlank()) {
            append(" and redirected to ")
            append(location)
            append(". Check the Jenkins scan root and credentials.")
        }
    }
)
