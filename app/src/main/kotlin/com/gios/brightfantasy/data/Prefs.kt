package com.gios.brightfantasy.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Credentials and settings.
 *
 * SharedPreferences rather than Room — the whole of it is six strings and four scalars,
 * and skipping Room keeps KSP out of the build entirely.
 *
 * The cookies are stored in the clear, and that is a deliberate call rather than an
 * oversight. `EncryptedSharedPreferences` puts the key in the AndroidKeyStore, which on
 * this phone has bitten before ([[lightsync]]): a key that survives a reinstall on paper
 * and does not in practice turns "your session expired" into "your app lost your session
 * and cannot tell you why". A private app-data file that only this uid can read is the
 * same protection Chrome's own cookie jar has on the device, for the same value.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("brightfantasy", Context.MODE_PRIVATE)

    // ------------------------------------------------------------- credentials

    var swid: String
        get() = sp.getString(KEY_SWID, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SWID, Setup.normaliseSwid(v)).apply()

    var espnS2: String
        get() = sp.getString(KEY_S2, "").orEmpty()
        set(v) = sp.edit().putString(KEY_S2, v.trim()).apply()

    val credentials: FantasyApi.Credentials
        get() = FantasyApi.Credentials(swid, espnS2)

    val signedIn: Boolean get() = credentials.present

    // ------------------------------------------------------------- league

    var leagueId: String
        get() = sp.getString(KEY_LEAGUE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LEAGUE, v.trim()).apply()

    /**
     * The season being viewed. Zero means "whatever the calendar says", which is what it
     * stays at unless someone deliberately goes back to look at a previous year.
     */
    var season: Int
        get() = sp.getInt(KEY_SEASON, 0)
        set(v) = sp.edit().putInt(KEY_SEASON, v).apply()

    fun effectiveSeason(): Int = if (season > 0) season else Espn.currentSeason()

    /** Which of the league's teams is yours. 0 until it has been picked or guessed. */
    var teamId: Int
        get() = sp.getInt(KEY_TEAM, 0)
        set(v) = sp.edit().putInt(KEY_TEAM, v).apply()

    val configured: Boolean get() = leagueId.isNotBlank()

    fun apply(payload: Setup.Payload) {
        payload.leagueId?.let { leagueId = it }
        payload.swid?.let { swid = it }
        payload.espnS2?.let { espnS2 = it }
        payload.season?.let { season = it }
        payload.teamId?.let { teamId = it }
    }

    fun signOut() {
        sp.edit()
            .remove(KEY_SWID)
            .remove(KEY_S2)
            .remove(KEY_LEAGUE)
            .remove(KEY_TEAM)
            .remove(KEY_SEASON)
            .apply()
    }

    // ------------------------------------------------------------- settings

    /**
     * Show ESPN's projections alongside real points. Off is a quieter screen and, for
     * some people, a less annoying Sunday.
     */
    var showProjections: Boolean
        get() = sp.getBoolean(KEY_PROJECTIONS, true)
        set(v) = sp.edit().putBoolean(KEY_PROJECTIONS, v).apply()

    /** Sort the waiver list by ESPN's weekly projection rather than by ownership. */
    var waiversByProjection: Boolean
        get() = sp.getBoolean(KEY_WAIVER_SORT, false)
        set(v) = sp.edit().putBoolean(KEY_WAIVER_SORT, v).apply()

    /**
     * Whether tapping two players swaps them straight away or asks first. On by default:
     * a mis-tap that benches a starter on Sunday morning is expensive and the confirm is
     * one extra press.
     */
    var confirmLineupChanges: Boolean
        get() = sp.getBoolean(KEY_CONFIRM, true)
        set(v) = sp.edit().putBoolean(KEY_CONFIRM, v).apply()

    // ------------------------------------------------------------- cache

    fun getString(key: String): String? = sp.getString(key, null)

    fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()

    fun getLong(key: String, default: Long = 0L): Long = sp.getLong(key, default)

    fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()

    private companion object {
        const val KEY_SWID = "swid"
        const val KEY_S2 = "espn_s2"
        const val KEY_LEAGUE = "league_id"
        const val KEY_SEASON = "season"
        const val KEY_TEAM = "team_id"
        const val KEY_PROJECTIONS = "show_projections"
        const val KEY_WAIVER_SORT = "waivers_by_projection"
        const val KEY_CONFIRM = "confirm_lineup"
    }
}
