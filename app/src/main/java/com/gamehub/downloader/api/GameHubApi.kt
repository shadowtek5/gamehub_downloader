package com.gamehub.downloader.api

import com.gamehub.downloader.data.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- DTOs (GameHub returns mostly snake_case; a few camelCase keys) ----

@Serializable
data class Heartbeat(
    val name: String? = null,
    val version: String? = null,
    val games: Int? = null,
    val platforms: Int? = null,
    val user: String? = null,
    val role: String? = null,
)

@Serializable
data class Platform(
    val slug: String,
    val name: String,
    val shortName: String = "",
    val vendor: String = "",
    val extensions: List<String> = emptyList(),
    val games: Int = 0,
    val playable: Boolean = false,
    val hidden: Boolean = false,
)

@Serializable
private data class PlatformsResponse(val platforms: List<Platform> = emptyList())

@Serializable
data class BrowseRom(
    val id: Long,
    val title: String,
    @SerialName("boxart_url") val boxartUrl: String? = null,
    @SerialName("platform_slug") val platformSlug: String,
    val variant: String? = null,
    val language: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class LibraryPage(val rows: List<BrowseRom> = emptyList(), val total: Int = 0)

@Serializable
data class RomDetail(
    val id: Long,
    val title: String,
    val filename: String? = null,
    @SerialName("platform_slug") val platformSlug: String,
    @SerialName("platform_name") val platformName: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("boxart_url") val boxartUrl: String? = null,
    @SerialName("hero_url") val heroUrl: String? = null,
    @SerialName("screenshot_url") val screenshotUrl: String? = null,
    val region: String? = null,
    val language: String? = null,
    val variant: String? = null,
    val revision: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    val md5: String? = null,
    val crc32: String? = null,
    val sha1: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("trailer_url") val trailerUrl: String? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    val rating: String? = null,
    @SerialName("age_rating") val ageRating: String? = null,
    @SerialName("game_modes") val gameModes: String? = null,
    val perspectives: String? = null,
    val themes: String? = null,
    val franchise: String? = null,
    @SerialName("ra_achievements") val raAchievements: Int? = null,
    @SerialName("dat_status") val datStatus: String? = null,
    val hltb: String? = null,
    @SerialName("igdb_related") val igdbRelated: String? = null,
) {
    val isArchive: Boolean
        get() = filename?.substringAfterLast('.', "")?.lowercase() in setOf("zip", "7z")

    /** HowLongToBeat times in seconds (main story, main + extras, completionist). */
    val howLongToBeat: Hltb?
        get() = hltb?.takeIf { it.isNotBlank() }?.let { runCatching { GameHubApi.json.decodeFromString<Hltb>(it) }.getOrNull() }
            ?.takeIf { it.main != null || it.plus != null || it.completionist != null }

    val related: IgdbRelated?
        get() = igdbRelated?.takeIf { it.isNotBlank() }?.let { runCatching { GameHubApi.json.decodeFromString<IgdbRelated>(it) }.getOrNull() }

    val isMultiDisc: Boolean get() = discNumber != null
    val isCueSheet: Boolean get() = filename?.endsWith(".cue", ignoreCase = true) == true
}

@Serializable
data class Hltb(val main: Long? = null, val plus: Long? = null, val completionist: Long? = null)

@Serializable
data class RelatedGame(val name: String, val cover: String? = null, val url: String? = null, val kind: String? = null)

@Serializable
data class IgdbRelated(val similar: List<RelatedGame> = emptyList(), val editions: List<RelatedGame> = emptyList())

@Serializable
private data class RomResponse(val rom: RomDetail)

@Serializable
data class PairStart(val id: String, val secret: String, val expiresIn: Int = 300)

@Serializable
data class PairPoll(val status: String? = null, val token: String? = null, val scope: String? = null, val error: String? = null)

@Serializable
private data class ErrorBody(val error: String? = null)

class ApiException(val code: Int, message: String) : IOException(message)

/** Thin client for one GameHub server. */
class GameHubApi(private val client: OkHttpClient, val connection: Connection) {

    val base: HttpUrl = normalizeBaseUrl(connection.baseUrl)

    fun url(path: String): HttpUrl = base.resolve(path) ?: throw IOException("Bad path $path")

    /** Absolute URL for a possibly-relative media URL, optionally asking the server for a resized thumbnail. */
    fun mediaUrl(path: String?, width: Int? = null): String? {
        if (path.isNullOrBlank()) return null
        val u = base.resolve(path) ?: return null
        if (width == null || !u.encodedPath.startsWith("/api/media/")) return u.toString()
        return u.newBuilder().setQueryParameter("w", width.toString()).build().toString()
    }

    fun authed(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${connection.token.trim()}")

    private suspend inline fun <reified T> get(url: HttpUrl, auth: Boolean = true): T = withContext(Dispatchers.IO) {
        val req = (if (auth) authed(url) else Request.Builder().url(url)).header("Accept", "application/json").build()
        // One quick retry: a pooled connection the server already closed fails with "unexpected end of stream".
        val body = try {
            fetch(req)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            fetch(req)
        }
        json.decodeFromString<T>(body)
    }

    private fun fetch(req: Request): String = client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, body))
        body
    }

    suspend fun heartbeat(): Heartbeat = get(url("api/heartbeat"))

    suspend fun platforms(): List<Platform> =
        get<PlatformsResponse>(url("api/platforms")).platforms.filter { !it.hidden && it.games > 0 }

    suspend fun library(platform: String?, query: String?, offset: Int, limit: Int = 120, sort: String = "name"): LibraryPage {
        val u = url("api/library").newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort)
        if (!platform.isNullOrBlank()) u.addQueryParameter("platform", platform)
        if (!query.isNullOrBlank()) u.addQueryParameter("q", query.trim())
        return get(u.build())
    }

    suspend fun rom(id: Long): RomDetail = get<RomResponse>(url("api/roms/$id")).rom

    fun fileUrl(id: Long): HttpUrl = url("api/roms/$id/file").newBuilder().addQueryParameter("download", "1").build()
    fun discsZipUrl(id: Long): HttpUrl = url("api/roms/$id/discs")

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        fun normalizeBaseUrl(raw: String): HttpUrl {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val parsed = s.toHttpUrlOrNull() ?: throw IOException("Invalid server URL")
            // Keep any sub-path (reverse proxy) and make it a directory so resolve() appends.
            val path = parsed.encodedPath.trimEnd('/') + "/"
            return parsed.newBuilder().encodedPath(path).query(null).fragment(null).build()
        }

        fun errorMessage(code: Int, body: String): String {
            val serverMsg = runCatching { json.decodeFromString<ErrorBody>(body).error }.getOrNull()
            return when (code) {
                401 -> "Unauthorized — the API token is invalid, expired or revoked"
                403 -> "Forbidden — this token's scope doesn't allow that"
                404 -> serverMsg ?: "Not found"
                415 -> "This game is stored as a folder and can't be downloaded as a file"
                else -> serverMsg ?: "Server returned HTTP $code"
            }
        }

        // ---- Device pairing (no token yet) ----

        suspend fun pairStart(client: OkHttpClient, baseUrl: String, device: String): PairStart = withContext(Dispatchers.IO) {
            val u = normalizeBaseUrl(baseUrl).resolve("api/pair/start")!!
            val body = """{"device":${Json.encodeToString(kotlinx.serialization.serializer<String>(), device)},"scope":"viewer"}"""
            post(client, u, body) { json.decodeFromString<PairStart>(it) }
        }

        suspend fun pairPoll(client: OkHttpClient, baseUrl: String, id: String, secret: String): PairPoll = withContext(Dispatchers.IO) {
            val u = normalizeBaseUrl(baseUrl).resolve("api/pair/$id/poll")!!
            val body = """{"secret":${Json.encodeToString(kotlinx.serialization.serializer<String>(), secret)}}"""
            val req = Request.Builder().url(u).post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                runCatching { json.decodeFromString<PairPoll>(text) }.getOrElse { PairPoll(error = "HTTP ${resp.code}") }
            }
        }

        fun pairApprovalUrl(baseUrl: String, id: String): String = normalizeBaseUrl(baseUrl).resolve("pair/$id")!!.toString()

        private fun <T> post(client: OkHttpClient, url: HttpUrl, body: String, parse: (String) -> T): T {
            val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, text))
                return parse(text)
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
