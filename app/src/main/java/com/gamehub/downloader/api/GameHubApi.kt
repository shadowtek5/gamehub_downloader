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
    /** The signed-in user's own data for this game. */
    val favorite: Int? = null,
    @SerialName("playtime_seconds") val playtimeSeconds: Long? = null,
    @SerialName("last_played_at") val lastPlayedAt: String? = null,
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

// ---- Save sync ----

/** A battery (.srm) save on the server: the same slot GameHub's browser player uses. */
@Serializable
data class RemoteBatterySave(val romId: Long, val sha256: String, val size: Long = 0, val updatedAt: String = "")

/** An emulator save folder synced from a device, keyed by system + the save's own id. */
@Serializable
data class RemoteDeviceSave(
    val platform: String,
    val key: String,
    val romId: Long? = null,
    val title: String? = null,
    val device: String? = null,
    val sha256: String,
    val size: Long = 0,
    val updatedAt: String = "",
)

@Serializable
data class RemoteSaves(val battery: List<RemoteBatterySave> = emptyList(), val device: List<RemoteDeviceSave> = emptyList())

@Serializable
data class SaveMatch(val id: Long, val platform: String, val title: String = "")

@Serializable
private data class SaveMatchResponse(val matches: Map<String, List<SaveMatch>> = emptyMap())

@Serializable
private data class SaveMatchRequest(val names: List<String>, val platforms: List<String> = emptyList())

/** A save state stored on GameHub (made by the browser player or uploaded by a device). */
@Serializable
data class RemoteState(
    val id: Long,
    @SerialName("size_bytes") val size: Long = 0,
    @SerialName("has_screenshot") val hasScreenshot: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val label: String? = null,
    val sha256: String? = null,
)

@Serializable
private data class RemoteStates(val states: List<RemoteState> = emptyList())

@Serializable
private data class StateCreated(val id: Long, val sha256: String? = null)

@Serializable
data class SaveVersion(val sha256: String? = null, val updatedAt: String? = null, val romId: Long? = null)

// ---- Collections ----

@Serializable
data class CollectionInfo(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerialName("is_smart") val isSmart: Int = 0,
    @SerialName("item_count") val itemCount: Int = 0,
)

@Serializable
private data class CollectionsResponse(val collections: List<CollectionInfo> = emptyList())

@Serializable
private data class FavoriteResponse(val favorite: Boolean = false)

// ---- Firmware / BIOS ----

/** One BIOS file a system's emulator looks for, and whether GameHub has it. */
@Serializable
data class BiosFile(
    /** Exact name (may include sub-folders) the libretro core looks for in its system folder. */
    val file: String,
    val md5: String? = null,
    val size: Long = 0,
    val region: String? = null,
    val description: String? = null,
    val have: Boolean = false,
    val verified: Boolean = false,
    val rowId: Long? = null,
)

/** A file uploaded to GameHub for a system that isn't in the BIOS list (kept as-is). */
@Serializable
data class FirmwareExtra(
    val id: Long,
    val filename: String,
    @SerialName("size_bytes") val size: Long = 0,
)

/** System firmware / keys for consoles like PS Vita, PS3 and Switch (GameHub 0.1.12+), kept as uploaded. */
@Serializable
data class FirmwarePackage(
    val id: String,
    val label: String,
    val required: Boolean = false,
    /** Set when the package is a folder of files (Switch firmware .nca files under "Firmware/"). */
    val folder: String? = null,
    val files: List<FirmwareExtra> = emptyList(),
)

@Serializable
data class BiosSystem(
    val slug: String,
    val name: String,
    val required: Boolean = false,
    val files: List<BiosFile> = emptyList(),
    val extras: List<FirmwareExtra> = emptyList(),
    val packages: List<FirmwarePackage> = emptyList(),
)

@Serializable
private data class BiosResponse(val systems: List<BiosSystem> = emptyList())

// ---- Android emulators (GameHub 0.1.11+, from Cocoon FE's emulator list) ----

/** One emulator entry ("player") for a system, with its Daijishou-format launch line. */
@Serializable
data class RemotePlayer(
    val id: String,
    val name: String,
    @SerialName("package") val packageName: String,
    val kind: String = "standalone",
    val core: String? = null,
    val amStartArguments: String,
    val acceptedFilenameRegex: String? = null,
)

/** Where a standalone emulator reads BIOS files for some systems. */
@Serializable
data class RemoteBiosTarget(
    val id: String,
    val name: String,
    val packages: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val where: String = "",
)

@Serializable
data class EmulatorCatalog(
    val platforms: Map<String, List<RemotePlayer>> = emptyMap(),
    val bios: List<RemoteBiosTarget> = emptyList(),
)

class ApiException(val code: Int, message: String) : IOException(message)

/** A conditional save upload was refused because the server copy changed (HTTP 412). */
class SaveConflictException(message: String) : IOException(message)

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

    /**
     * One page of the library. [favorites] limits it to the user's favorites and [collection]
     * to one collection (both work with the platform filter and search).
     */
    suspend fun library(
        platform: String?,
        query: String?,
        offset: Int,
        limit: Int = 120,
        sort: String = "name",
        favorites: Boolean = false,
        collection: Long? = null,
    ): LibraryPage {
        val u = url("api/library").newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort)
        if (!platform.isNullOrBlank()) u.addQueryParameter("platform", platform)
        if (!query.isNullOrBlank()) u.addQueryParameter("q", query.trim())
        if (favorites) u.addQueryParameter("tab", "favorites")
        collection?.let { u.addQueryParameter("collection", it.toString()) }
        return get(u.build())
    }

    suspend fun collections(): List<CollectionInfo> = get<CollectionsResponse>(url("api/collections")).collections

    /** Sets (or clears) a favorite; returns the new state. */
    suspend fun setFavorite(romId: Long, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val body = """{"favorite":$favorite}""".toRequestBody("application/json".toMediaType())
        json.decodeFromString<FavoriteResponse>(fetch(authed(url("api/roms/$romId/favorite")).post(body).build())).favorite
    }

    /** Every system's Android emulators, or null on servers before 0.1.11 (no catalog). */
    suspend fun emulators(): EmulatorCatalog? = try {
        get<EmulatorCatalog>(url("api/emulators"))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    // ---- Firmware / BIOS ----

    suspend fun bios(): List<BiosSystem> = get<BiosResponse>(url("api/firmware")).systems

    fun firmwareUrl(rowId: Long): HttpUrl = url("api/firmware/$rowId")

    // ---- Playtime (games launched in an emulator on this device) ----

    /** Marks the game as being played now (also adds a minute, like GameHub's own player). */
    suspend fun startPlaying(romId: Long) = postEmpty(url("api/roms/$romId/heartbeat"))

    suspend fun stopPlaying(romId: Long) = postEmpty(url("api/roms/$romId/stop-playing"))

    /**
     * Adds a finished session's time. Servers before 0.1.11 don't have the endpoint, so it
     * falls back to one heartbeat per minute (each adds 60 seconds).
     */
    suspend fun addPlaytime(romId: Long, seconds: Long) = withContext(Dispatchers.IO) {
        if (seconds <= 0) return@withContext
        val body = """{"seconds":$seconds}""".toRequestBody("application/json".toMediaType())
        try {
            fetch(authed(url("api/roms/$romId/playtime")).post(body).build())
        } catch (e: ApiException) {
            if (e.code != 404 && e.code != 405) throw e
            repeat(((seconds + 30) / 60).toInt().coerceAtMost(12 * 60)) { postEmpty(url("api/roms/$romId/heartbeat")) }
        }
    }

    private suspend fun postEmpty(u: HttpUrl) = withContext(Dispatchers.IO) {
        fetch(authed(u).post(ByteArray(0).toRequestBody(null)).build())
        Unit
    }

    suspend fun rom(id: Long): RomDetail = get<RomResponse>(url("api/roms/$id")).rom

    fun fileUrl(id: Long): HttpUrl = url("api/roms/$id/file").newBuilder().addQueryParameter("download", "1").build()
    fun discsZipUrl(id: Long): HttpUrl = url("api/roms/$id/discs")

    // ---- Save sync ----

    suspend fun saves(): RemoteSaves = get(url("api/saves"))

    /** Pairs save file base names (e.g. RetroArch "<ROM name>.srm") with library games. */
    suspend fun matchSaves(names: List<String>, platforms: List<String> = emptyList()): Map<String, List<SaveMatch>> =
        withContext(Dispatchers.IO) {
            if (names.isEmpty()) return@withContext emptyMap()
            val body = json.encodeToString(SaveMatchRequest.serializer(), SaveMatchRequest(names, platforms))
            val req = authed(url("api/saves/match")).post(body.toRequestBody("application/json".toMediaType())).build()
            json.decodeFromString<SaveMatchResponse>(fetch(req)).matches
        }

    private fun deviceSaveUrl(platform: String, key: String): HttpUrl =
        url("api/device-saves/").newBuilder().addPathSegment(platform).addPathSegment(key).build()

    suspend fun downloadBatterySave(romId: Long): ByteArray = downloadBytes(url("api/roms/$romId/save"))

    suspend fun downloadDeviceSave(platform: String, key: String): ByteArray = downloadBytes(deviceSaveUrl(platform, key))

    /**
     * Uploads a battery save only if the server copy is still [expected] (null = there must be
     * none). Throws [SaveConflictException] when another device changed it in the meantime.
     */
    suspend fun uploadBatterySave(romId: Long, bytes: ByteArray, expected: String?): SaveVersion =
        upload(authed(url("api/roms/$romId/save")), bytes, "application/octet-stream", expected)

    suspend fun uploadDeviceSave(
        platform: String,
        key: String,
        zip: ByteArray,
        contentHash: String,
        expected: String?,
        title: String?,
        romId: Long?,
        device: String?,
    ): SaveVersion {
        val b = authed(deviceSaveUrl(platform, key)).header("X-Save-Sha256", contentHash)
        title?.let { b.header("X-Save-Title", java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")) }
        device?.let { b.header("X-Save-Device", java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")) }
        romId?.let { b.header("X-Save-Rom-Id", it.toString()) }
        return upload(b, zip, "application/zip", expected)
    }

    // ---- Save states ----

    suspend fun states(romId: Long): List<RemoteState> = get<RemoteStates>(url("api/roms/$romId/states")).states

    suspend fun downloadState(id: Long): ByteArray = downloadBytes(url("api/states/$id"))

    suspend fun downloadStateScreenshot(id: Long): ByteArray? =
        runCatching { downloadBytes(url("api/states/$id").newBuilder().addQueryParameter("type", "screenshot").build()) }.getOrNull()

    /** Uploads a save state (raw, uncompressed) with an optional screenshot; returns the new state's id. */
    suspend fun uploadState(romId: Long, state: ByteArray, screenshot: ByteArray?, label: String): Long = withContext(Dispatchers.IO) {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("state", "state.state", state.toRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("label", label)
            .apply { screenshot?.let { addFormDataPart("screenshot", "screenshot.png", it.toRequestBody("image/png".toMediaType())) } }
            .build()
        json.decodeFromString<StateCreated>(fetch(authed(url("api/roms/$romId/states")).post(body).build())).id
    }

    suspend fun deleteState(id: Long) = withContext(Dispatchers.IO) {
        client.newCall(authed(url("api/states/$id")).delete().build()).execute().close()
    }

    private suspend fun downloadBytes(u: HttpUrl): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(authed(u).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, resp.body?.string().orEmpty()))
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun upload(builder: Request.Builder, bytes: ByteArray, type: String, expected: String?): SaveVersion =
        withContext(Dispatchers.IO) {
            if (expected == null) builder.header("If-None-Match", "*") else builder.header("If-Match", "\"$expected\"")
            val req = builder.post(bytes.toRequestBody(type.toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 412) throw SaveConflictException(errorMessage(resp.code, text))
                if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(resp.code, text))
                json.decodeFromString<SaveVersion>(text)
            }
        }

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
