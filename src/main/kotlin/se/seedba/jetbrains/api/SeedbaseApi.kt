package se.seedba.jetbrains.api

import com.google.gson.JsonArray
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

class SeedbaseApiException(message: String) : RuntimeException(message)

data class LoginInitiation(val code: String, val browserUrl: String, val pollUrl: String)

internal fun JsonObject.str(key: String): String {
    val el = get(key) ?: return ""
    if (el.isJsonNull) return ""
    return if (el.isJsonPrimitive) el.asString else el.toString()
}

object SeedbaseApi {
    const val DEFAULT_API_URL = "https://seedba.se/api/v1"
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    private const val MAX_PAGES = 50
    private const val POLL_INTERVAL_MS = 2_000L
    private const val GENERATION_TIMEOUT_MS = 300_000L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Kein API-URL-Setting in der IDE: ein eingechecktes Projekt-Setting könnte den
    // Token sonst still an einen fremden Host umleiten. Die Env-Var ist ein bewusster
    // Dev-Override pro Shell, den kein Repository injizieren kann.
    fun apiUrl(): String = normalizeApiUrl(System.getenv("SEEDBASE_API_URL") ?: DEFAULT_API_URL)

    fun webBaseUrl(): String = apiUrl().removeSuffix("/api/v1")

    fun normalizeApiUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw SeedbaseApiException("Invalid API URL '$raw'.")
        }
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme == "https") return trimmed
        if (scheme == "http" && (uri.host ?: "").lowercase() in LOCAL_HOSTS) return trimmed
        throw SeedbaseApiException(
            "Insecure API URL '$raw' — only https:// is allowed (http:// only for localhost).",
        )
    }

    private fun authHeader(token: String): String =
        if (token.startsWith("dr_sk_")) "Bearer $token" else "Token $token"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(builder: HttpRequest.Builder): HttpResponse<ByteArray> {
        val request = builder.timeout(Duration.ofSeconds(30)).build()
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SeedbaseApiException("Request interrupted")
        } catch (exc: Exception) {
            throw SeedbaseApiException("Network error: ${exc.message ?: exc}")
        }
    }

    private fun errorFromBody(status: Int, body: ByteArray): String {
        val text = String(body, StandardCharsets.UTF_8).trim()
        val parsed = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            null
        }
        if (parsed != null && parsed.isJsonObject) {
            val detail = parsed.asJsonObject.str("detail")
            if (detail.isNotEmpty()) return detail
            if (parsed.asJsonObject.size() > 0) {
                return "Request failed ($status): ${parsed.toString().take(300)}"
            }
        }
        val snippet = text.take(300)
        return if (snippet.isNotEmpty()) "Request failed ($status): $snippet" else "Request failed ($status)"
    }

    private fun parseJson(resp: HttpResponse<ByteArray>): JsonElement {
        val text = String(resp.body(), StandardCharsets.UTF_8)
        if (text.isBlank()) return JsonObject()
        try {
            return JsonParser.parseString(text)
        } catch (_: Exception) {
            throw SeedbaseApiException("Unexpected non-JSON response from ${resp.uri()}")
        }
    }

    private fun getJson(token: String, url: String): JsonElement {
        val resp = send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader(token))
                .header("Accept", "application/json")
                .GET(),
        )
        if (resp.statusCode() !in 200..299) {
            throw SeedbaseApiException(errorFromBody(resp.statusCode(), resp.body()))
        }
        return parseJson(resp)
    }

    private fun postJson(token: String, url: String, payload: String): JsonObject {
        val resp = send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader(token))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)),
        )
        if (resp.statusCode() !in 200..299) {
            throw SeedbaseApiException(errorFromBody(resp.statusCode(), resp.body()))
        }
        val parsed = parseJson(resp)
        return if (parsed.isJsonObject) parsed.asJsonObject else JsonObject()
    }

    private fun resolveAgainstBase(base: String, url: String): String {
        var path = url
        if (path.startsWith(base)) {
            path = path.substring(base.length)
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
            val parsed = URI(path)
            path = parsed.rawPath + (parsed.rawQuery?.let { "?$it" } ?: "")
        }
        if (path.startsWith("/api/v1/")) {
            path = path.substring("/api/v1".length)
        }
        if (!path.startsWith("/")) {
            path = "/$path"
        }
        return "$base$path"
    }

    fun initiateLogin(): LoginInitiation {
        val base = apiUrl()
        val resp = send(
            HttpRequest.newBuilder(URI.create("$base/cli/auth/initiate/"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")),
        )
        if (resp.statusCode() !in 200..299) {
            throw SeedbaseApiException("Login initiation failed (${resp.statusCode()})")
        }
        val parsed = parseJson(resp)
        if (!parsed.isJsonObject) throw SeedbaseApiException("Unexpected login response from server")
        val data = parsed.asJsonObject
        val code = data.str("code")
        val pollUrl = data.str("poll_url")
        if (code.isEmpty() || pollUrl.isEmpty()) {
            throw SeedbaseApiException("Unexpected login response from server")
        }
        return LoginInitiation(code, data.str("browser_url"), resolveAgainstBase(base, pollUrl))
    }

    fun pollForToken(pollUrl: String, timeoutMs: Long = 300_000L, checkCancelled: () -> Unit): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            checkCancelled()
            val resp = send(
                HttpRequest.newBuilder(URI.create(pollUrl))
                    .header("Accept", "application/json")
                    .GET(),
            )
            when {
                resp.statusCode() == 404 || resp.statusCode() == 410 ->
                    throw SeedbaseApiException("Authorization code expired")
                resp.statusCode() !in 200..299 ->
                    throw SeedbaseApiException("Authorization polling failed (${resp.statusCode()})")
            }
            val parsed = parseJson(resp)
            if (parsed.isJsonObject) {
                val data = parsed.asJsonObject
                when (data.str("status")) {
                    "authorized" -> {
                        val token = data.str("token")
                        if (token.isEmpty()) {
                            throw SeedbaseApiException("Authorization finished but token missing")
                        }
                        return token
                    }
                    "expired" -> throw SeedbaseApiException("Authorization code expired")
                }
            }
            if (System.currentTimeMillis() + POLL_INTERVAL_MS > deadline) {
                throw SeedbaseApiException("Authorization timed out")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun requestList(token: String, startUrl: String): List<JsonObject> {
        val base = apiUrl()
        val rows = mutableListOf<JsonObject>()
        var url: String? = startUrl
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val data = getJson(token, url)
            var next: String? = null
            when {
                data.isJsonArray -> collectObjects(data.asJsonArray, rows)
                data.isJsonObject -> {
                    val obj = data.asJsonObject
                    val results = obj.get("results")
                    if (results != null && results.isJsonArray) {
                        collectObjects(results.asJsonArray, rows)
                    }
                    val nextEl = obj.get("next")
                    if (nextEl != null && !nextEl.isJsonNull) {
                        next = resolveAgainstBase(base, nextEl.asString)
                    }
                }
            }
            url = next
            pages += 1
        }
        return rows
    }

    private fun collectObjects(array: JsonArray, into: MutableList<JsonObject>) {
        for (el in array) {
            if (el.isJsonObject) into.add(el.asJsonObject)
        }
    }

    fun listProjects(token: String): List<JsonObject> =
        requestList(token, "${apiUrl()}/datasets/")

    fun listGenerations(token: String, projectId: String): List<JsonObject> =
        requestList(token, "${apiUrl()}/generations/?dataset=${encode(projectId)}")

    fun generateAndWait(token: String, projectId: String, checkCancelled: () -> Unit): JsonObject {
        val base = apiUrl()
        val create = postJson(token, "$base/datasets/${encode(projectId)}/generate/", "{}")
        val generationId = create.str("generation_id")
        if (generationId.isEmpty()) {
            throw SeedbaseApiException("Generation did not return an id")
        }
        val deadline = System.currentTimeMillis() + GENERATION_TIMEOUT_MS
        var consecutiveErrors = 0
        while (true) {
            checkCancelled()
            Thread.sleep(POLL_INTERVAL_MS)
            var status: JsonObject? = null
            try {
                val data = getJson(token, "$base/generations/${encode(generationId)}/")
                status = if (data.isJsonObject) data.asJsonObject else null
                consecutiveErrors = 0
            } catch (exc: SeedbaseApiException) {
                consecutiveErrors += 1
                if (consecutiveErrors >= 3) throw exc
            }
            if (status != null) {
                when (val state = status.str("status")) {
                    "completed" -> return status
                    "failed", "cancelled" -> throw SeedbaseApiException("Generation $state")
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw SeedbaseApiException("Generation timed out")
            }
        }
    }

    private fun schemaPayload(content: String, sourceType: String): String =
        JsonObject().apply {
            addProperty("content", content)
            addProperty("source_type", sourceType)
        }.toString()

    fun importDetect(token: String, projectId: String, content: String, sourceType: String): JsonObject =
        postJson(token, "${apiUrl()}/datasets/${encode(projectId)}/import/detect/", schemaPayload(content, sourceType))

    fun importSchema(token: String, projectId: String, content: String, sourceType: String): JsonObject =
        postJson(token, "${apiUrl()}/datasets/${encode(projectId)}/import/", schemaPayload(content, sourceType))

    fun download(token: String, generationId: String, format: String): ByteArray {
        val url = "${apiUrl()}/generations/${encode(generationId)}/download/?export_format=${encode(format)}"
        val resp = send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader(token))
                .GET(),
        )
        if (resp.statusCode() !in 200..299) {
            throw SeedbaseApiException(errorFromBody(resp.statusCode(), resp.body()))
        }
        return resp.body()
    }
}
