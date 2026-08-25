package com.gios.brightfantasy.data

/**
 * The credential QR.
 *
 * `espn_s2` is around 300 characters of URL-encoded base64 and the SWID is a braced
 * UUID. Typing either on a 3.9" phone is not a thing anyone will do twice, so setup is
 * one scan of a code generated on a desktop by `setup/index.html` in this repo — a page
 * that renders the QR locally in the browser and never sends the cookies anywhere.
 *
 * Three payload shapes are accepted, because the point of failure to avoid is a user who
 * scanned something reasonable and got "not a setup code":
 *
 *  1. `brightfantasy://setup?league=…&swid=…&s2=…` — what the generator produces.
 *  2. A bare JSON object with the same keys.
 *  3. Any ESPN fantasy URL, which carries the league id and often the season — enough on
 *     its own for a public league, and enough to fill in the id before the cookies.
 *
 * Pure Kotlin and free of Android imports so the whole matrix is unit-testable.
 */
object Setup {

    data class Payload(
        val leagueId: String? = null,
        val swid: String? = null,
        val espnS2: String? = null,
        val season: Int? = null,
        val teamId: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = leagueId == null && swid == null && espnS2 == null &&
                season == null && teamId == null
    }

    fun parse(raw: String): Payload? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val payload = when {
            text.startsWith("{") -> parseJson(text)
            text.startsWith("brightfantasy://", ignoreCase = true) -> parseQuery(text)
            text.contains("espn.com", ignoreCase = true) -> parseEspnUrl(text)
            // A code holding nothing but digits is a league id, and that is a perfectly
            // reasonable thing for someone to have made.
            text.all { it.isDigit() } && text.length in 4..12 -> Payload(leagueId = text)
            text.contains('=') -> parseQuery(text)
            else -> null
        }
        return payload?.takeUnless { it.isEmpty }
    }

    private fun parseQuery(text: String): Payload {
        val query = text.substringAfter('?', text.substringAfter("://", text))
        val fields = query.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").lowercase()
                val value = part.substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to decode(value)
            }
            .toMap()
        return Payload(
            leagueId = fields["league"] ?: fields["leagueid"] ?: fields["id"],
            swid = fields["swid"]?.let(::normaliseSwid),
            espnS2 = fields["s2"] ?: fields["espn_s2"] ?: fields["espns2"],
            season = (fields["season"] ?: fields["year"] ?: fields["seasonid"])?.toIntOrNull(),
            teamId = (fields["team"] ?: fields["teamid"])?.toIntOrNull(),
        )
    }

    /**
     * Deliberately hand-rolled rather than `JSONObject`: this file stays Android-free so
     * it can be tested off-device, and the shape is five flat string fields.
     */
    private fun parseJson(text: String): Payload {
        fun field(vararg names: String): String? {
            for (name in names) {
                val match = Regex("\"$name\"\\s*:\\s*\"?([^\",}]+)\"?", RegexOption.IGNORE_CASE)
                    .find(text)
                val value = match?.groupValues?.getOrNull(1)?.trim()
                if (!value.isNullOrBlank()) return value
            }
            return null
        }
        return Payload(
            leagueId = field("league", "leagueId", "id"),
            swid = field("swid")?.let(::normaliseSwid),
            espnS2 = field("s2", "espn_s2", "espnS2"),
            season = field("season", "year", "seasonId")?.toIntOrNull(),
            teamId = field("team", "teamId")?.toIntOrNull(),
        )
    }

    /**
     * ESPN's own URLs, of which there are several generations still in circulation:
     * `.../football/league?leagueId=123&seasonId=2025`, `.../football/team?leagueId=…`,
     * and the newer `/leagueId/123/` path form.
     */
    private fun parseEspnUrl(text: String): Payload {
        val league = Regex("leagueId[=/](\\d+)", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)
        val season = Regex("seasonId[=/](\\d{4})", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        val team = Regex("teamId[=/](\\d+)", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        return Payload(leagueId = league, season = season, teamId = team)
    }

    /**
     * The SWID is braced in the cookie jar and ESPN wants it braced back. Browsers show
     * it both ways depending on where you look, and an unbraced one is accepted by the
     * request and then quietly treated as logged out — a 200 with a public-league view of
     * a private league, which is the single most confusing failure this app can have.
     */
    fun normaliseSwid(raw: String): String {
        val trimmed = decode(raw).trim()
        if (trimmed.isEmpty()) return trimmed
        val bare = trimmed.trim('{', '}')
        return "{$bare}"
    }

    /**
     * Percent-decoding, by hand. `espn_s2` is URL-encoded in the cookie jar and pasting it
     * straight through leaves `%2B` in the middle of the session blob, which ESPN reads as
     * a different session and refuses. `java.net.URLDecoder` would also turn `+` into a
     * space, and `+` is a legitimate character in that value.
     */
    fun decode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
