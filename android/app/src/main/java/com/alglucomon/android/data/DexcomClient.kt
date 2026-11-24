package com.alglucomon.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DexcomClient {
    private const val APP_ID_STANDARD = "d89443d2-327c-4a6f-89e5-496bbb0317db"
    private const val APP_ID_JP = "d8665ade-9673-4e27-9ff6-92db4ce13d13"
    private const val EMPTY_UUID = "00000000-0000-0000-0000-000000000000"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class CachedSession(
        val region: DexcomRegion,
        val username: String,
        val sessionId: String,
    )

    @Volatile
    private var cachedSession: CachedSession? = null

    fun clearSession() {
        cachedSession = null
    }

    private fun endpointConfig(region: DexcomRegion): Pair<String, String> = when (region) {
        DexcomRegion.OUS -> "https://shareous1.dexcom.com/ShareWebServices/Services" to APP_ID_STANDARD
        DexcomRegion.JP -> "https://share.dexcom.jp/ShareWebServices/Services" to APP_ID_JP
        DexcomRegion.US -> "https://share2.dexcom.com/ShareWebServices/Services" to APP_ID_STANDARD
    }

    private fun postJson(url: String, jsonBody: JSONObject?): Pair<Int, String> {
        val body = jsonBody?.toString()?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun decodeJsonString(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun authenticate(region: DexcomRegion, username: String, password: String): String {
        val cleanUser = username.trim()
        require(cleanUser.isNotEmpty() && password.isNotEmpty()) {
            "Dexcom username and password are required."
        }
        val (baseUrl, appId) = endpointConfig(region)

        val authPayload = JSONObject().apply {
            put("accountName", cleanUser)
            put("password", password)
            put("applicationId", appId)
        }
        val (authCode, authBody) = postJson("$baseUrl/General/AuthenticatePublisherAccount", authPayload)
        if (authCode !in 200..299) {
            throw IllegalStateException("Dexcom auth failed ($authCode): $authBody")
        }
        val accountId = decodeJsonString(authBody)
        if (accountId.isEmpty() || accountId == EMPTY_UUID) {
            throw IllegalStateException("Invalid Dexcom credentials or wrong region.")
        }

        val loginPayload = JSONObject().apply {
            put("accountId", accountId)
            put("password", password)
            put("applicationId", appId)
        }
        val (loginCode, loginBody) = postJson("$baseUrl/General/LoginPublisherAccountById", loginPayload)
        if (loginCode !in 200..299) {
            throw IllegalStateException("Dexcom login failed ($loginCode): $loginBody")
        }
        val sessionId = decodeJsonString(loginBody)
        if (sessionId.isEmpty() || sessionId == EMPTY_UUID) {
            throw IllegalStateException("Dexcom login returned an empty session ID.")
        }
        return sessionId
    }

    private data class ReadResult(
        val readings: List<GlucoseReading>,
        val sessionExpired: Boolean,
        val errorMessage: String?,
    )

    private fun readWithSession(
        region: DexcomRegion,
        sessionId: String,
        minutes: Int,
        maxCount: Int,
    ): ReadResult {
        val (baseUrl, _) = endpointConfig(region)
        val url = "$baseUrl/Publisher/ReadPublisherLatestGlucoseValues?sessionId=$sessionId&minutes=$minutes&maxCount=$maxCount"
        val (code, body) = postJson(url, null)
        if (code !in 200..299) {
            val expired = body.contains("SessionIdNotFound") ||
                body.contains("SessionNotValid") ||
                code == 500
            return ReadResult(emptyList(), expired, "Dexcom read error ($code): $body")
        }

        val arr = JSONArray(body)
        val list = ArrayList<GlucoseReading>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val ts = parseDexcomTimestamp(obj.getString("WT"))
            val value = obj.getInt("Value")
            val trend = parseTrendValue(obj.opt("Trend"))
            list.add(GlucoseReading(value = value, trend = trend, timestamp = ts))
        }
        list.sortBy { it.timestamp }
        return ReadResult(list, false, null)
    }

    suspend fun fetchReadings(
        region: DexcomRegion,
        username: String,
        password: String,
        minutes: Int = 360,
        maxCount: Int = 72,
    ): List<GlucoseReading> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val current = cachedSession?.takeIf { it.region == region && it.username == cleanUser }
        val sessionId = current?.sessionId ?: authenticate(region, cleanUser, password).also {
            cachedSession = CachedSession(region, cleanUser, it)
        }

        val first = readWithSession(region, sessionId, minutes, maxCount)
        if (first.errorMessage == null) return@withContext first.readings

        if (first.sessionExpired) {
            clearSession()
            val freshId = authenticate(region, cleanUser, password).also {
                cachedSession = CachedSession(region, cleanUser, it)
            }
            val retry = readWithSession(region, freshId, minutes, maxCount)
            if (retry.errorMessage == null) return@withContext retry.readings
            throw IllegalStateException(retry.errorMessage)
        }
        throw IllegalStateException(first.errorMessage)
    }

    suspend fun testConnection(
        region: DexcomRegion,
        username: String,
        password: String,
    ): String = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val sessionId = authenticate(region, cleanUser, password)
        val res = readWithSession(region, sessionId, minutes = 1440, maxCount = 1)
        if (res.errorMessage != null) {
            throw IllegalStateException(res.errorMessage)
        }
        cachedSession = CachedSession(region, cleanUser, sessionId)
        val latest = res.readings.lastOrNull()
        if (latest != null) {
            "Connected — latest reading: ${latest.value} mg/dL"
        } else {
            "Authenticated, but no readings found in the last 24h. Make sure Dexcom Share is enabled with at least 1 follower."
        }
    }

    fun parseDexcomTimestamp(wt: String): Long {
        val start = wt.indexOf("Date(")
        require(start >= 0) { "Invalid Dexcom timestamp format: $wt" }
        val rest = wt.substring(start + 5)
        val end = rest.indexOf(')')
        require(end > 0) { "Unterminated Dexcom timestamp: $wt" }
        val inner = rest.substring(0, end)
        val offsetIdx = inner.indexOfFirstIndexed { idx, c -> idx > 0 && (c == '+' || c == '-') }
        val millis = if (offsetIdx >= 0) inner.substring(0, offsetIdx) else inner
        return millis.trim().toLong()
    }

    fun parseTrendValue(raw: Any?): TrendDirection = when (raw) {
        is Number -> TrendDirection.fromCode(raw.toInt())
        is String -> TrendDirection.fromLabel(raw)
        else -> TrendDirection.UNKNOWN
    }

    private inline fun String.indexOfFirstIndexed(predicate: (Int, Char) -> Boolean): Int {
        for (i in indices) {
            if (predicate(i, this[i])) return i
        }
        return -1
    }
}
