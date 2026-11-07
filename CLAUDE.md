# CLAUDE.md — OnAir

## What this project is

OnAir is a personal fork of **IPVIO Stream** (https://github.com/Emrodev/IPVIO-Stream), an open-source IPTV player for Android TV / Google TV written in Kotlin + Jetpack Compose with Media3/ExoPlayer.

OnAir keeps IPVIO's player, Xtream login and EPG handling, and adds a simpler "what's on right now" experience built around five sections:

1. **Live TV** → the existing TV guide
2. **Movies** → movies airing right now on live channels, with channel and progress
3. **Shows** → TV shows/series airing right now on live channels, with channel and progress
4. **Sports** → games/sports programs airing now, grouped by sport
5. **Events** → PPV / special events live now and coming up

**Target device:** onn 4K Pro (Google TV, ~3 GB RAM), controlled with a D-pad remote. Sideloaded via ADB, not distributed through Play Store.

**Source:** the owner uses an **Xtream Codes** login only. M3U support can stay as IPVIO has it but is not a focus.

**Out of scope:** live scores, sports APIs, accounts, cloud sync, any bundled content or playlists.

## Ground rules

- **Never commit credentials.** Xtream server/user/password are entered in the app only. Do not hardcode them, log them, or put them in tests, sample data or `secrets.properties`. Mask the password in any debug output.
- **Licence:** the project is GPL-3.0 (required by IPVIO's FFmpeg decoder). Keep `LICENSE`, `THIRD_PARTY_NOTICES.md` and the `licenses/` folder intact. Add a line crediting IPVIO Stream as the upstream project in the README and the app's About screen.
- **Rebrand fully:** IPVIO's README asks forks not to use its name or logo. Replace all user-visible "IPVIO" names, logos and the green-on-navy brand references with OnAir.
- **Keep upstream mergeable where practical.** Prefer adding new files over rewriting IPVIO's existing ones, so `git fetch upstream` + merge stays manageable. Remote `upstream` points to Emrodev/IPVIO-Stream.
- **Remote-first UI.** Every screen must be fully usable with D-pad + OK + Back. Visible focus state on every focusable element. No touch-only interactions.
- **Performance on a 3 GB box.** Never load the full guide into memory. Query only what a screen needs. Keep list screens smooth (lazy lists, stable keys, no heavy work on the main thread).
- **Small, verifiable steps.** After each phase: the project builds (`./gradlew assembleDebug`), unit tests pass (`./gradlew test`), and the owner can install and check it on the TV before the next phase starts.
- Follow the existing codebase conventions: pure logic in `*Logic.kt` files with JUnit tests; translations in `Dict*.kt`. OnAir only needs **English** for new strings, but add them in the existing format so the build doesn't break.

## Phase 0 — Understand before changing (no code changes)

Read the project and report back:

- Where the Xtream login, playlist download and background refresh live
- How the EPG is fetched, parsed and stored (in memory? database? which tables/classes?), how many days are kept, and whether programme **categories** and **episode numbers** are preserved
- Where the home screen and navigation are defined
- Where app name, launcher icon, TV banner, theme colours and `applicationId` are set
- Anything that would make Phases 2–4 difficult

Do not modify code in this phase. Wait for the owner's go-ahead.

## Phase 1 — Rebrand to OnAir

- App name: **OnAir**
- Change `applicationId` to `com.onair.tv` so it installs alongside the original IPVIO if both are present. Leave Kotlin package names alone unless renaming is trivial — avoid a huge diff.
- **Launcher icon:** dark navy rounded square, simple white TV outline, small red "live" dot inside the screen. Flat, minimal, readable at small sizes. Provide as an adaptive icon (vector drawable foreground + solid background).
- **TV banner (320×180):** same TV mark on the left, "OnAir" wordmark on the right, navy background. Set `android:banner` in the manifest.
- Default theme: navy background with a red accent (replace IPVIO green as the default; the other colour themes can stay).
- Update README to describe OnAir and credit IPVIO Stream.

## Phase 2 — Home screen with five tiles

- New home screen shown after login/profile selection, replacing IPVIO's default home as the app's start destination. IPVIO's original Movies/Series VOD browsing should stay reachable (e.g. a small "Library" or "On Demand" entry, or via settings) rather than be deleted.
- Five large tiles in a single row (or 3 + 2 if they don't fit): **Live TV, Movies, Shows, Sports, Events.** Each tile: icon, label, and a small live count where cheap to compute (e.g. "12 on now"). If counts are slow, show the tiles immediately and fill counts in afterwards.
- Initial focus on **Live TV**. Back from the home screen exits the app (with IPVIO's existing exit confirmation if it has one).
- **Live TV** tile opens IPVIO's existing TV guide unchanged.

## Phase 3 — "On now" data layer

Build one shared, tested piece of logic that all four "now" screens use.

### Data needed

For each programme in the guide: channel (stream id, name, logo, category/group name), title, description, start, stop, EPG category tags, episode number if present.

- Prefer whatever IPVIO already stores. If categories or episode numbers are discarded during parsing, extend the parser to keep them.
- If IPVIO doesn't keep a full guide, use Xtream's bulk XMLTV endpoint (`xmltv.php?username=…&password=…`) during the normal background refresh — **not** per-channel `get_short_epg` calls across thousands of channels.
- Store in a local database (Room/SQLite, matching what the project already uses) with indexes on start/stop time and channel. Keep a rolling window: from now − 3 h to now + 24 h. Prune older rows on every refresh.

### "On now" definition

A programme is on now if `start <= now < stop`. Progress = `(now − start) / (stop − start)`, clamped to 0–1. Also compute minutes elapsed and minutes remaining.

### Classification (pure logic in an `OnNowLogic.kt`-style file, fully unit-tested)

Classify each on-now programme into **Movie, Show, Sports, Event, or Other**. Check in this order — first match wins:

1. **Event** — channel's Xtream category/group name, or channel name, matches PPV/event patterns: `PPV`, `PAY PER VIEW`, `EVENT`, `EVENTS`, `UFC PPV`, `BOXING PPV`, `WWE PPV`, `DAZN PPV`, `FLO`, etc. (case-insensitive).
2. **Sports** — EPG category contains `Sport`, OR the channel's category/group name contains `SPORT`, OR the title matches a game pattern (`A vs B`, `A vs. B`, `A @ B`, `A at B`) together with a sport keyword from the table below.
3. **Movie** — EPG category contains `Movie` or `Film`, OR the channel's group is a movie-channel group (e.g. contains `MOVIE`, `CINEMA`, `HBO`, `SHOWTIME`, `STARZ`, `CINEMAX`, `MGM`, `TCM`) and the programme has no episode number. Duration ≥ 70 minutes is a supporting signal, not sufficient alone.
4. **Show** — EPG category contains `Series`, `Episode`, `Drama`, `Comedy`, `Reality`, `Documentary`, `Animation`, `Sitcom`, etc., OR the programme has an episode number, OR it's on an entertainment channel and doesn't match the above.
5. **Other** — News, weather, paid programming, infomercials, shopping, religious, music video loops, "To Be Announced", "No information", "Off Air". These never appear in Movies or Shows.

Exclude programmes with empty or placeholder titles everywhere.

### Sport grouping (for Sports items)

Assign one sport per item using title, EPG category and channel name:

| Group | Keywords (case-insensitive, word-boundary) |
|---|---|
| NFL | NFL, Football Night, Thursday Night Football, Sunday Night Football, Monday Night Football, NFL RedZone |
| College Football | NCAAF, College Football, CFB, SEC, Big Ten, Big 12, ACC (when with football context) |
| NBA | NBA |
| College Basketball | NCAAB, College Basketball, NCAAM, NCAAW, March Madness |
| MLB | MLB, Baseball |
| NHL | NHL, Hockey |
| Soccer | Soccer, Futbol, Fútbol, Liga MX, MLS, Premier League, EPL, La Liga, Serie A, Bundesliga, Ligue 1, Champions League, UEFA, Copa |
| Combat Sports | UFC, MMA, Boxing, Bellator, PFL, WWE, AEW, Wrestling |
| Golf | Golf, PGA, LPGA, LIV |
| Tennis | Tennis, ATP, WTA, Open (with tennis context) |
| Motorsports | NASCAR, F1, Formula 1, IndyCar, MotoGP, Racing |
| Other Sports | anything classified Sports that matches none of the above |

Keep the keyword tables as data (lists/maps) in the logic file so they're easy to tune later. Spanish-language channels are common in the owner's lineup — keep matching accent- and case-insensitive.

### Duplicate handling

Providers often carry the same channel in several qualities/regions (e.g. "ESPN HD", "ESPN FHD", "ESPN 4K", "US: ESPN"). On the Movies, Shows and Sports lists, group identical programmes (same normalized title + same start time) into **one card** and let the user pick the source when opening it, preferring the highest quality as the default.

### Tests

Unit tests with realistic sample titles/categories (invented, no real provider data) for: on-now window edges, progress math, each classification rule, sport grouping, duplicate merging, and the "Other" exclusions.

## Phase 4 — The four "now" screens

### Movies and Shows

- Grid of cards sorted by **least time remaining last** (i.e. most recently started first), with a toggle to sort by channel name.
- Card: poster/thumbnail (TMDB image if IPVIO's TMDB integration has a key and finds a match; otherwise channel logo), title, channel name + logo, progress bar, and "45 min in · 1h 10m left".
- Selecting a card opens a small detail panel: description, start–end times, and **Watch** (tunes the channel via the existing player). If multiple sources were merged, show a source picker with the highest quality focused.
- Empty state: "Nothing classified as a movie is airing right now" with a shortcut to the TV guide.

### Sports

- Left column: sport groups that currently have games, with counts (NFL 3, Soccer 7, …). Right side: cards for the selected sport.
- Card: title (e.g. "Cowboys vs Eagles"), channel, progress bar, time remaining. Same Watch behaviour and source picker as above.
- Include a "Starting soon" section per sport for items starting in the next 60 minutes.

### Events

- Two sections: **Live now** and **Coming up** (next 24 h, from the guide).
- Card: event name (from programme title, or channel name if the guide is empty), channel, start time or progress.
- If a PPV channel has no guide data at all, still list it under "PPV channels" with its channel name so it's reachable.

### Shared behaviour

- All screens refresh their "now" data when opened and once a minute while visible (progress bars tick; finished programmes drop off).
- Back returns to the home screen with focus on the tile you came from.
- Long titles marquee or truncate cleanly; nothing overflows at 1080p or 4K.

## Phase 5 — Tuning with real data

The owner will test against their real Xtream lineup and report misclassifications. Fix them by adjusting the keyword tables and rules in the logic file and adding a unit test for each reported case. Don't add per-channel hacks in UI code.

Optional, if time allows: a hidden debug screen (Settings → long-press version) showing counts per category and a sample of "Other" items, to help spot rules that miss.

## Building and installing on the onn 4K Pro

```
./gradlew assembleDebug
adb connect <onn-ip>:5555        # or the pairing port shown under Wireless debugging
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the TV: Settings → System → About → tap "Android TV OS build" 7 times → Developer options → enable USB debugging / Wireless debugging.

## Definition of done (v1)

- OnAir name, icon and banner on the Google TV home screen
- Five-tile home screen; Live TV opens the guide
- Movies, Shows, Sports (grouped) and Events (live + upcoming) populated from the Xtream guide, with channel, progress and one-press Watch
- Smooth on the onn 4K Pro with the owner's full lineup; no credentials in the repo
- Builds cleanly, tests pass, README credits IPVIO Stream
