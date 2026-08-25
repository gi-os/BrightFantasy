# BrightFantasy

ESPN fantasy football on the Light Phone III.

Your matchup, your lineup, the standings and the waiver wire, in the LightOS design
language — 27×31 grid, Akkurat, greyscale, wheel scrolling. Reads ESPN's undocumented
fantasy API directly; no server in the middle.

**Install:** [BrightMarket](https://brightmarket.gzl.dev) → Fantasy, or grab the APK from
[Releases](../../releases/latest).
**Set up:** open [the setup page](https://gi-os.github.io/BrightFantasy/) on a computer and
scan the code it draws.

---

## What it does

| Screen | What's on it |
| --- | --- |
| **Matchup** | Both lineups side by side, positional, with the margin between the scores. How many starters are still to play, and the projected finish. Step back through any week. |
| **My team** | Start/sit by tapping one player then the other. Illegal swaps are refused with the reason before anything is sent. What you started, the best you could have started, and the gap. |
| **League** | Scoreboard, standings with the playoff line drawn, power rankings, the add/drop feed, and the draft board. |
| **The Wire** | Free agents and waivers, filtered by position, sorted by ownership or by projection. Pick up a player and choose who goes in the same transaction. |
| **Player** | This week's box score, the season, and every week of it as a bar chart. |

Shake the phone to file a bug.

## Setting it up

ESPN has no OAuth, no device flow, and no tokens — a private league is read by presenting
the two session cookies a browser would have sent. `espn_s2` is about 300 characters and
cannot be shortened, which makes typing it on this phone a non-starter.

So: [the setup page](https://gi-os.github.io/BrightFantasy/) takes your league id and the
two cookies and draws a QR code. The app scans it. The page runs entirely in your browser
and contains no network code at all — the cookies never leave your computer except onto
your own screen.

A **public league** needs only the league id. Cookies expire every few months; when the app
says the login was refused, make a fresh code.

## Notes on ESPN's API

Everything below was verified live against a real league rather than taken from a blog
post, and the payload sizes are measured on a twelve-team league.

**The base URL moved** in April 2024. Reads go to `lm-api-reads.fantasy.espn.com`, writes to
`lm-api-writes.fantasy.espn.com`. The old `fantasy.espn.com` paths answer 404, which reads
exactly like a wrong league id.

**Which view you ask for is the whole performance story:**

| request | bytes |
| --- | ---: |
| `mTeam` + `mSettings` + `mStandings` | 61 K |
| `mMatchupScore`, no week | 52 K |
| `mMatchupScore` + `scoringPeriodId` | 353 K |
| `mRoster` + `forTeamId` | 94 K |
| `mRoster`, whole league | 1.1 M |
| `mBoxscore` | 926 K |

`forTeamId` really does filter `mRoster` — that one parameter is 94 KB instead of 1.1 MB.
A matchup is three cheap requests (scoreboard plus two rosters) rather than one expensive
one, and `mBoxscore` is never used at all.

**The roster inlined in `mMatchupScore` is a trap.** Adding `scoringPeriodId` makes ESPN
attach a roster to every matchup, but its player objects are stripped to a stats array with
no name, no position and no pro team. 300 KB for a screen of blank rows.

**Three axes identify a stat, and they all live in one flat unordered array.**
`statSourceId` 0 is what happened and 1 is what ESPN projected; `statSplitTypeId` 0 is the
season, 1 that week, 2 a per-game average. Indexing the array — which is what most examples
do — gets a projection instead of a result about half the time, silently.

**FLEX is lineup slot 23 and the bench is 20.** Slot ids are not ordered by whether they
start, so "below the bench id" files every FLEX player onto the bench and leaves the
starting lineup a player short. Verified the right way round against all twelve teams of a
real week: the starters then sum to the recorded score to the decimal.

**`x-fantasy-filter` is a header, not a parameter.** Free agents and the activity feed are
both queried through it; without it, `kona_player_info` answers with the entire player
universe.

**A player's weekly game log is gated** behind `filterStatsForTopScoringPeriodIds`, whose
`additionalValue` is a list of composite stat ids. Without it the log is silently empty,
which reads as a player who has not played.

**Points and bye weeks are indistinguishable** from the fantasy payload alone — both are
zero. The season-level `proTeamSchedules_wl` endpoint is the only source for kickoff time,
opponent, and whether a game has finished.

**A 404 on the activity feed is normal.** Leagues created before ESPN introduced message
topics have no communication group at all.

**Writes are unverified.** The session that built this had read access to a public league
and no ESPN login, so the lineup and add/drop payloads are the shape the ESPN web client
sends rather than something observed working. Every failure surfaces ESPN's own message
rather than a generic one, so the first real attempt says what is wrong.

## Building

CI does the building — a push to `main` compiles, runs the tests, signs and publishes a
release. Push a branch first and let `check.yml` go green; `build.yml` on `main` ships to
the phone with no review step in between.

```
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

The parsers, URL builders and setup payloads are pure Kotlin with no Android imports, so
they compile and run under a plain `kotlinc` without the Android toolchain.

## Versioning

`v<major>.<minor>.<CI run number>`. Bump `versionName` in `app/build.gradle.kts` for
anything Obtainium should treat as a new version.

## Changelog

### v1.0 — first release

- Matchup, roster with start/sit, league scoreboard, standings, power rankings, activity
  feed, draft board, waiver wire, player pages.
- QR setup with an inline QR encoder on the setup page — no CDN, no upload.
- Wheel scrolling, shake-to-report, LightOS bars and type scale.

---

Icons are from [light-sdk](https://github.com/lightphone/light-sdk) (MIT); see
`NOTICE-light-sdk.txt`. Not affiliated with ESPN.
