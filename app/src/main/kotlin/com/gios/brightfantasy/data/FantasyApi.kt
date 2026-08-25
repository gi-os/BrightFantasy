package com.gios.brightfantasy.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The one place that talks to ESPN.
 *
 * ESPN's fantasy API has no tokens, no OAuth and no developer programme. A private
 * league is read by presenting the two session cookies a browser would have sent:
 * `SWID` (the account's id, braces included) and `espn_s2` (a long opaque session
 * blob). Nothing else authenticates — no header, no signature — which is why the whole
 * credential problem in this app is "how do you get a 300-character cookie onto a phone
 * with no keyboard worth typing on", and why the answer is a QR code.
 *
 * A public league needs no cookies at all, so the app is usable before setup and says so.
 */
class FantasyApi(private val creds: () -> Credentials) {

    data class Credentials(val swid: String, val espnS2: String) {
        val present: Boolean get() = swid.isNotBlank() && espnS2.isNotBlank()
    }

    /** What came back, including the failure, because the failure is the useful part. */
    sealed interface Result {
        data class Ok(val body: String) : Result
        /** ESPN answered, but not with what was asked for. [message] is ESPN's own text. */
        data class Denied(val code: Int, val message: String) : Result
        data class Offline(val cause: String) : Result
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Some of these payloads are a hundred kilobytes of JSON over LTE.
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun Request.Builder.common(): Request.Builder {
        val c = creds()
        // A bare OkHttp user agent gets 403ed often enough to matter, on the fantasy host
        // as much as on the site API.
        header("User-Agent", "Mozilla/5.0 (Linux; Android 13) BrightFantasy")
        header("Accept", "application/json, text/plain, */*")
        // ESPN reads its own web client's platform header on the write path and rejects
        // a POST without one; harmless on reads, so it goes on everything.
        header("x-fantasy-platform", "kona-PROD-BrightFantasy")
        header("x-fantasy-source", "kona")
        if (c.present) header("Cookie", "SWID=${c.swid}; espn_s2=${c.espnS2}")
        return this
    }

    fun get(url: String, fantasyFilter: String? = null): Result {
        val request = Request.Builder()
            .url(url)
            .common()
            // ESPN takes the player/activity query as a JSON *header*, not a parameter and
            // not a body. Without it `kona_player_info` answers with the entire player
            // universe and times out; there is no query-string equivalent.
            .apply { fantasyFilter?.let { header("x-fantasy-filter", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.Ok(body)
                } else {
                    Result.Denied(response.code, espnMessage(body, response.code))
                }
            }
        }.getOrElse { Result.Offline(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * A lineup change, an add or a drop. All three are the same POST to the same path.
     *
     * Deliberately not swallowed into a boolean: the first time this runs against a real
     * account it will either work or say exactly why, and "couldn't save lineup" would
     * throw away the only sentence worth reading.
     */
    fun post(url: String, json: String): Result {
        val request = Request.Builder()
            .url(url)
            .common()
            .post(json.toRequestBody(JSON))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.Ok(body)
                } else {
                    Result.Denied(response.code, espnMessage(body, response.code))
                }
            }
        }.getOrElse { Result.Offline(it.message ?: it.javaClass.simpleName) }
    }

    fun bytes(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).common().build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * ESPN's error body is `{"messages":["..."],"details":[...]}`. Pulling the first
         * message out beats showing a status code, and the two that actually happen are
         * worth recognising by hand: a 401 means the cookies expired (they last a few
         * months), a 404 on a league that used to work means the season rolled over.
         */
        fun espnMessage(body: String, code: Int): String {
            val quoted = Regex("\"messages\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.getOrNull(1)
            return when {
                quoted != null -> quoted
                code == 401 -> "ESPN rejected the login. The cookies have probably expired."
                code == 403 -> "This league is private and the login was not accepted."
                code == 404 -> "No league by that id in this season."
                else -> "ESPN answered $code."
            }
        }
    }
}
