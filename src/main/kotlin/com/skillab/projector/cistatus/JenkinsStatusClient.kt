package com.skillab.projector.cistatus

import com.intellij.openapi.project.Project
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Comparator

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

data class JenkinsDiagnosticStep(
    val name: String,
    val url: String,
    val statusCode: Int?,
    val location: String?,
    val wwwAuthenticate: String?,
    val contentType: String?,
    val authHeaderSent: Boolean,
    val bodyPreview: String,
    val error: String?,
) {
    val ok: Boolean
        get() = error == null && statusCode in 200..299 && contentType?.contains("json", ignoreCase = true) == true
}

class JenkinsStatusClient(private val project: Project? = null) {
    private val maxArtifactCount = 500
    private val maxArtifactBytes = 100L * 1024L * 1024L
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .cookieHandler(cookieManager)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
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

    fun downloadArtifacts(
        summary: JenkinsBuildSummary,
        username: String,
        token: String,
        cacheRoot: Path,
    ): Path {
        if (summary.artifacts.size > maxArtifactCount) {
            throw IllegalStateException("Build has ${summary.artifacts.size} artifacts, which exceeds the limit of $maxArtifactCount.")
        }

        val buildCache = cacheRoot
            .resolve(stableId(summary.url))
            .resolve(summary.number.toString())
            .normalize()
        if (Files.exists(buildCache)) {
            Files.walk(buildCache)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
        Files.createDirectories(buildCache)

        var totalBytes = 0L
        summary.artifacts.forEach { artifact ->
            val target = buildCache.resolve(artifact.path).normalize()
            if (!target.startsWith(buildCache)) {
                throw IllegalStateException("Artifact path escapes cache directory: ${artifact.path}")
            }
            artifact.size?.let {
                totalBytes += it
                if (totalBytes > maxArtifactBytes) {
                    throw IllegalStateException("Build artifacts exceed the ${maxArtifactBytes / 1024 / 1024} MB preview limit.")
                }
            }

            Files.createDirectories(target.parent)
            val response = sendWithRetry(artifact.url, username, token, HttpResponse.BodyHandlers.ofFile(target))
            if (response.statusCode() !in 200..299) {
                throw JenkinsHttpException(response.statusCode(), artifact.url, response.headers().firstValue("Location").orElse(null))
            }
            if (artifact.size == null) {
                totalBytes += Files.size(target)
                if (totalBytes > maxArtifactBytes) {
                    throw IllegalStateException("Build artifacts exceed the ${maxArtifactBytes / 1024 / 1024} MB preview limit.")
                }
            }
        }

        return buildCache
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

    fun diagnose(
        baseUrl: String,
        jobPath: String,
        username: String,
        token: String,
        preferredBranch: String?,
    ): List<JenkinsDiagnosticStep> {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val rootUrl = buildJenkinsUrl(normalizedBaseUrl, "")
        val configuredJobUrl = buildJenkinsUrl(normalizedBaseUrl, jobPath)
        val steps = mutableListOf<JenkinsDiagnosticStep>()
        steps += diagnosticRequest("whoAmI", "$rootUrl/whoAmI/api/json", username, token)
        steps += diagnosticRequest("Jenkins root API", "$rootUrl/api/json", username, token)
        steps += diagnosticRequest("Configured job root", "$configuredJobUrl/api/json", username, token)
        val resolvedJobUrl = runCatching {
            resolveBuildableJobUrl(configuredJobUrl, username, token, preferredBranch)
        }.getOrNull()
        if (!resolvedJobUrl.isNullOrBlank()) {
            steps += diagnosticRequest("Resolved job latest build", "$resolvedJobUrl/lastBuild/api/json", username, token)
        }
        return steps
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
        if (preferred != null) {
            return preferred.url
        }

        val recursiveRoot = fetchJobNode(configuredJobUrl, username, token, 0, maxDepth = 5, visited = mutableSetOf())
        findAutoSelected(recursiveRoot, preferredBranch)?.let {
            return it.url.trimEnd('/')
        }

        return (jobs.firstOrNull { it.name.equals("main", ignoreCase = true) } ?: jobs.first()).url
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
        val response = sendWithRetry(url, username, token, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw JenkinsHttpException(response.statusCode(), url, response.headers().firstValue("Location").orElse(null))
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun getArray(url: String, username: String, token: String): List<JsonElement> {
        val response = sendWithRetry(url, username, token, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw JenkinsHttpException(response.statusCode(), url, response.headers().firstValue("Location").orElse(null))
        }
        return JsonParser.parseString(response.body()).asJsonArray.toList()
    }

    private fun <T> sendWithRetry(
        url: String,
        username: String,
        token: String,
        bodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        var lastError: IOException? = null
        var interactiveFallbackTried = false
        repeat(3) { attempt ->
            try {
                var response = httpClient.send(request(url, username, token), bodyHandler)
                if (!interactiveFallbackTried && shouldTriggerInteractiveFallback(response, url)) {
                    interactiveFallbackTried = true
                    val recovered = attemptInteractiveFallback(url)
                    if (recovered) {
                        response = httpClient.send(request(url, username, token), bodyHandler)
                        if (response.statusCode() !in setOf(401, 403) && !isSecurityRedirect(response)) {
                            return response
                        }
                    }
                }
                if (shouldRetry(response) && attempt < 2) {
                    warmSession(url, username, token)
                    sleepBeforeRetry(attempt)
                    return@repeat
                }
                return response
            } catch (error: IOException) {
                lastError = error
                if (attempt == 2 || !isTransientNetworkError(error)) {
                    throw error
                }
                sleepBeforeRetry(attempt)
            }
        }
        throw lastError ?: IOException("Jenkins request failed")
    }

    private fun shouldRetry(response: HttpResponse<*>): Boolean {
        val location = response.headers().firstValue("Location").orElse("")
        return response.statusCode() in setOf(401, 403, 502, 503, 504) ||
            (response.statusCode() in 300..399 && location.contains("securityRealm", ignoreCase = true))
    }

    private fun shouldTriggerInteractiveFallback(response: HttpResponse<*>, url: String): Boolean {
        val project = project ?: return false
        val settings = CiStatusSettings.getInstance(project)
        if (!settings.experimentalKeycloakInteractiveFallback) return false
        if (!url.startsWith(settings.jenkinsBaseUrl, ignoreCase = true)) return false
        return response.statusCode() in setOf(401, 403) || isSecurityRedirect(response)
    }

    private fun isSecurityRedirect(response: HttpResponse<*>): Boolean {
        val location = response.headers().firstValue("Location").orElse("")
        return response.statusCode() in 300..399 && (
            location.contains("securityRealm", ignoreCase = true) ||
                location.contains("commenceLogin", ignoreCase = true) ||
                location.contains("keycloak", ignoreCase = true)
            )
    }

    private fun attemptInteractiveFallback(url: String): Boolean {
        val project = project ?: return false
        val settings = CiStatusSettings.getInstance(project)
        if (!settings.experimentalKeycloakInteractiveFallback) return false
        val service = KeycloakSessionService.getInstance(project)
        return service.ensureLoggedIn(rootUrl(url))
    }

    private fun isTransientNetworkError(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("GOAWAY", ignoreCase = true) ||
            message.contains("stream", ignoreCase = true) ||
            message.contains("closed", ignoreCase = true) ||
            message.contains("reset", ignoreCase = true)
    }

    private fun warmSession(url: String, username: String, token: String) {
        runCatching {
            val root = rootUrl(url)
            httpClient.send(request("$root/whoAmI/api/json", username, token), HttpResponse.BodyHandlers.discarding())
        }
    }

    private fun sleepBeforeRetry(attempt: Int) {
        Thread.sleep(250L * (attempt + 1))
    }

    private fun diagnosticRequest(name: String, url: String, username: String, token: String): JenkinsDiagnosticStep {
        return runCatching {
            val response = httpClient.send(request(url, username, token), HttpResponse.BodyHandlers.ofString())
            JenkinsDiagnosticStep(
                name = name,
                url = url,
                statusCode = response.statusCode(),
                location = response.headers().firstValue("Location").orElse(null),
                wwwAuthenticate = response.headers().firstValue("WWW-Authenticate").orElse(null),
                contentType = response.headers().firstValue("Content-Type").orElse(null),
                authHeaderSent = username.isNotBlank() && token.isNotBlank(),
                bodyPreview = compactBodyPreview(response.body()),
                error = null,
            )
        }.getOrElse { error ->
            JenkinsDiagnosticStep(
                name = name,
                url = url,
                statusCode = null,
                location = null,
                wwwAuthenticate = null,
                contentType = null,
                authHeaderSent = username.isNotBlank() && token.isNotBlank(),
                bodyPreview = "",
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun compactBodyPreview(body: String): String =
        body.replace(Regex("\\s+"), " ").trim().take(240)

    private fun request(url: String, username: String, token: String): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("User-Agent", "skillab-ci-status-notifier")

        if (username.isNotBlank() && token.isNotBlank()) {
            val encoded = Base64.getEncoder()
                .encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", "Basic $encoded")
        }

        return builder.build()
    }

    private fun rootUrl(url: String): String {
        val uri = URI.create(url)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val base = "${uri.scheme}://${uri.host}$port"
        val path = uri.path.substringBefore("/job/", "").trimEnd('/')
        return if (path.isBlank()) base else "$base$path"
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

    private fun stableId(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
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