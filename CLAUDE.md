# CLAUDE.md — OnAir

## What this project is

OnAir is a personal fork of **IPVIO Stream** (https://github.com/Emrodev/IPVIO-Stream), an open-source IPTV player for Android TV / Google TV written in Kotlin + Jetpack Compose with Media3/ExoPlayer.

OnAir keeps IPVIO's player, Xtream login and EPG handling, and adds a simpler "what's on right now" experience built around five sections:

1. **Live TV** → the existing TV guide
2. **Movies** → movies airing right now on live channels, with channel and progress
3. **Shows** → TV shows/series airing right now on live channels, with channel and progress
4. **Sports** → games/sports programs airing now, grouped by sport
5. **Events** → PPV / special events live now and coming up

**Target / test device:** onn 4K Plus (Google TV, Android 14), controlled with a D-pad remote. Sideloaded via ADB, not distributed through Play Store. Measured 2026-11-10: **~1.9 GB RAM** (MemTotal 1,971,788 kB, ~550 MB available with the system idle, 860 MB zram swap), **32-bit userland** (`armeabi-v7a`), app heap limit **384 MB** with `largeHeap` (256 MB without), 1920×1080 at 320 dpi (xhdpi). Treat it as a 2 GB, 32-bit box. Only install to the owner's test device; never to other devices on the network.

**Source:** the owner uses an **Xtream Codes** login only. M3U support can stay as IPVIO has it but is not a focus.

**Out of scope:** live scores, sports APIs, accounts, cloud sync, any bundled content or playlists.

## Ground rules

- **Never commit credentials.** Xtream server/user/password are entered in the app only. Do not hardcode them, log them, or put them in tests, sample data or `secrets.properties`. Mask the password in any debug output.
- **Licence:** the project is GPL-3.0 (required by IPVIO's FFmpeg decoder). Keep `LICENSE`, `THIRD_PARTY_NOTICES.md` and the `licenses/` folder intact. Add a line crediting IPVIO Stream as the upstream project in the README and the app's About screen.
- **Rebrand fully:** IPVIO's README asks forks not to use its name or logo. Replace all user-visible "IPVIO" names, logos and the green-on-navy brand references with OnAir.
- **Keep upstream mergeable where practical.** Prefer adding new files over rewriting IPVIO's existing ones, so `git fetch upstream` + merge stays manageable. Remote `upstream` points to Emrodev/IPVIO-Stream.
- **Remote-first UI.** Every screen must be fully usable with D-pad + OK + Back. Visible focus state on every focusable element. No touch-only interactions.
- **Performance on a 2 GB, 32-bit box (384 MB heap).** Never load the full guide into memory; keep per-screen working sets small (budget tens of MB, not hundreds) and let `Mem`/`LoadLogic` budgets scale from `maxMemory()` instead of hard-coding sizes. Native libraries must include `armeabi-v7a`. Query only what a screen needs. Keep list screens smooth (lazy lists, stable keys, no heavy work on the main thread).
- **Small, verifiable steps.** After each phase: the project builds (`./gradlew assembleDebug`), unit tests pass (`./gradlew test`), and the owner can install and check it on the TV before the next phase starts.
- Follow the existing codebase conventions: pure logic in `*Logic.kt` files with JUnit tests; translations in `Dict*.kt`. OnAir only needs **English** for new strings, but add them in the existing format so the build doesn't break.

## Decisions (owner, 2026-11-10)

These override anything below that says otherwise.

- **Provider rate limits.** Catalog (`player_api.php`) and EPG (`xmltv.php`) are cached on the device and only fetched on the background schedule (at most every 12 h) or a manual refresh — never on app start, login with a stored account, or playlist switch. Back off on errors (15 min → 12 h, honour `Retry-After`). All provider API clients go through `ProviderGuard`; rules live in `RefreshLogic.kt` (tested). Don't add provider calls that bypass this.
- **User-Agent** stays as it is (`IPVIOStream/1.0` for playlist requests) — it works with the owner's provider. It is not user-visible, so the rebrand leaves it alone.
- **Xtream categories.** Each live channel's Xtream category name (`group-title`) and `category_id` (`xc-category-id` → `Item.catId`) must survive the player_api → M3U → parser → cache path unchanged. Sports/Events detection depends on them; tests cover it.
- **Guide storage: framework SQLite** (`android.database.sqlite`, no Room, no KSP).
- **Guide window:** now − 3 h to now + 36 h for all channels; channels with catch-up keep their archive days (max 7). Prune outside the window on every import.
- **Per-channel EPG calls:** once the guide is in SQLite, `get_simple_data_table` / `get_short_epg` are only called on demand for the one focused channel that has no XMLTV rows. No bulk prefetch (the guide currently prefetches up to 80 channels — remove that).
- **Standalone TV guide screen** (no video preview in v1). Picking a programme starts the player; Back from the player returns to the guide. The in-player guide stays as it is.

- **What the owner's XMLTV actually contains** (measured 2026-11-11 on the stored file: 29,131 programmes, 2,875 channels): **0 % `<category>`, 0 % `<episode-num>`, 0 % `<sub-title>`** – only title and description. All times carry `-0500` and are converted correctly (checked against known air times). So classification relies on **titles + Xtream category name + channel name** (plus duration); the EPG-category and episode rules stay in `OnNowLogic` for providers that send them but don't fire here. Tune keyword/title tables for that (Phase 5); the debug screen shows the field coverage.
- **Guide coverage:** only ~1,257 of ~9,400 live channels carry a tvg-id (epg_channel_id) and ~680 match the XMLTV. Channels without guide rows can't appear in Movies/Shows/Sports; PPV/event channels are still listed under Events → "PPV channels" with their channel name.

### Phase order

0. Understand · 1. Rebrand · 2. Five-tile home + standalone TV guide screen · 3. Guide → SQLite, then the "on now" logic · 4. The four "now" screens · 5. Tuning — **all done; v1.0 approved on the TV 2026-11-11** (tag `v1.0`).

## v1.0 — what was built (2026-11-11)

The phase specs below are kept as the original brief; this section is the state of the code. Later decisions made while building:

- **Login / catalog:** Xtream loads through `player_api.php` (account check, live/VOD/series categories + streams, streamed JSON → M3U lines → IPVIO's parser), `get.php` only as fallback (`PlaylistStore.downloadApi`, `XtreamApiLogic`). Series episodes load on demand via `get_series_info` (`SeriesInfo` in `XtreamApi.kt`).
- **Guide:** framework SQLite (`GuideDb`, rules in `GuideLogic`): staging table + atomic swap, re-import only when the stored files / wanted channels / `GuideLogic.SCHEMA` change. The import also records the UTC offsets of start times (debug screen). Readers (`Xmltv.forChannel`, `Epg.now`, `EpgFull.get`) keep their signatures.
- **On now:** `OnNowLogic` (pure, tested) + `OnNow` (snapshot off the main thread, cached 60 s; per-playlist `ChanIndex` built once after each playlist load). Measured on the 4K Plus: ~400 ms per snapshot.
- **PPV / events:** event channel = PPV/event **Xtream category** (`PPV_CATEGORY_WORDS`), or a clear PPV **name** ("PPV 01", "PPV:", "UFC PPV") — never a bare "Event"/"Main Event". Slots without an event name ("PPV 23", "[PPV] EVENTS 01:") are hidden from Events and counted.
- **Events refresh:** `get_live_streams&category_id=…` for the PPV/event categories only, when the home or Events screen opens, at most every 30 min (`RefreshLogic.Trigger.EVENTS`, normal backoff, through `ProviderGuard`). New names are an overlay (`EventNames`, item id → name) applied in `PlaylistStore.setItems`; a full catalog download clears it.
- **Sports:** a sports Xtream category alone is not enough — the title needs a sports signal (`SPORT_TITLE_WORDS`, league keywords, "vs"/"@"); otherwise it is a Show. "Football" = soccer on UK/international channels, NFL on US channels; beIN defaults to soccer; extra group **Horse Racing** (racing channels: Sky Sports Racing, TVG, FanDuel TV, Racing UK …). All keyword tables are data in `OnNowLogic`.
- **Screens:** `OnAirHome.kt` (tiles with live counts, `OnAirNav`), `GuideScreen.kt`, `NowScreens.kt` (Movies/Shows/Sports/Events, detail panel with source picker; Sports: Up/Down stay in the sport list, Right/OK into the cards, Back returns to the sport), rules in `NowScreenLogic` / `OnAirHomeLogic`. Hidden debug screen: `OnAirDebug.kt` (Settings → About → long-press the version).
- **Logging** (`LoginDebug`, tag `OnAirLogin`, always masked): errors in every build; status lines in debug builds; request/response tracing only when `LoginDebug.VERBOSE` is switched on in a debug build.

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
- **Live TV** tile opens the TV guide as a **standalone screen** (`GuideScreen.kt` hosting IPVIO's `Guide` composable from `PlayerGuide.kt`), not via the player. Today every guide entry point first starts playback and then opens the guide inside the player (`AppNav.openGuide`). The new screen shows no video preview; choosing a programme tunes the channel in the player, Back from the player returns to the guide with the channel focused. The sidebar's EPG entry and Live TV → "TV-Guide" also open the standalone screen; the in-player guide (Right key / TV-Guide button while watching) stays.

## Phase 3 — "On now" data layer

Build one shared, tested piece of logic that all four "now" screens use.

### Data needed

For each programme in the guide: channel (stream id, name, logo, category/group name), title, description, start, stop, EPG category tags, episode number if present.

- Prefer whatever IPVIO already stores. If categories or episode numbers are discarded during parsing, extend the parser to keep them.
- If IPVIO doesn't keep a full guide, use Xtream's bulk XMLTV endpoint (`xmltv.php?username=…&password=…`) during the normal background refresh — **not** per-channel `get_short_epg` calls across thousands of channels.
- IPVIO keeps the guide only in memory (`Xmltv.progs`) and its parser discards `<category>`, `<episode-num>` and `<sub-title>`. **First step of Phase 3:** extend the parser and import the stored `epg_*.xml.gz` into **framework SQLite** (`GuideDb.kt`; table `programme` with tvg_id, start, stop, title, sub_title, descr, categories, episode, title_norm; indexes on (start, stop) and (tvg_id, start)). Import into a staging table in one transaction and swap atomically, off the main thread. Window: now − 3 h to now + 36 h, catch-up channels keep archive days (max 7); prune on every import. `Xmltv.forChannel`, `Epg.now` and `EpgFull.get` read from the database behind their existing signatures. Channel name/logo/group/category id stay in the playlist cache and are joined in memory via `tvgId`.
- Keep `OnNowLogic` pure (works on plain rows), so it is unit-testable without Android SQLite.

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

## Building and installing on the onn 4K Plus

```
./gradlew assembleDebug
adb connect <onn-4k-plus-ip>:5555                 # or the pairing port shown under Wireless debugging
adb -s <onn-4k-plus-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk   # always pass -s: other Android devices are on the network
```

On the TV: Settings → System → About → tap "Android TV OS build" 7 times → Developer options → enable USB debugging / Wireless debugging.

## Release build (signed)

Since v1.0 the TV runs the **release** build. Debug and release are signed with different keys, so switching between them on the TV needs an uninstall, which **deletes the login and all app data** — ask the owner first. Release updates install in place (`install -r`) and keep the data.

- **Keystore:** `%USERPROFILE%\.onair\onair-release.jks` (PKCS12, alias `onair`, RSA 4096, valid until 2054; certificate SHA-256 `1B:D4:07:49:33:72:41:B0:1A:0F:E3:25:AB:8C:89:0A:E5:36:88:62:59:63:42:C0:BC:3C:F3:65:B3:57:65:D7`).
- **Passwords:** `%USERPROFILE%\.onair\signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). The folder is readable by the owner's Windows account only. **Never commit either file, never copy them into the repo, never print the passwords.** Back both up somewhere safe (password manager / offline copy): without them future updates can't install over the current app without wiping it.
- `app/build.gradle.kts` reads that file (or `-Ponair.signing=<path>` / env `ONAIR_SIGNING`); without it the release build is unsigned.
- **R8 keep rules:** `app/proguard-rules.pro` — `-keeppackagenames` for the optimised scopes (R8 moved a Kotlin multi-file facade class out of its package → `IllegalAccessError` at launch) and the Room `RoomDatabase` constructor (Room 2.6.1's rule is incomplete under R8 full mode → `NoSuchMethodException` at launch). After changing dependencies or R8 settings, launch the release build on the TV and check `adb logcat` for `FATAL` before tagging.

```
./gradlew assembleRelease
adb -s <onn-4k-plus-ip>:5555 install -r app/build/outputs/apk/release/app-release.apk
```

### Day-to-day testing: the `staging` build (owner, 2026-11-11)

- **Use `staging` for day-to-day TV testing:** the release build without R8 (and without the release lint check) – same `com.onair.tv` and release signing, so it installs over the release build and keeps the login and data. A one-file change builds in ~40 s instead of ~100 s.
  ```
  ./gradlew assembleStaging
  adb -s <onn-4k-plus-ip>:5555 install -r app/build/outputs/apk/staging/app-staging.apk
  ```
- **Always build and check a `release` build on the TV before tagging** (launch it, watch `adb logcat` for `FATAL`): R8 problems only show there (v1.0 had two R8 crashes at launch).
- Build machine: 7.7 GB RAM – `gradle.properties` gives the Gradle daemon 3 GB and caps the Kotlin daemon at 1.5 GB; build cache and configuration cache are on. The PC must not sleep during a build (a 45-minute "build" in v2 was the laptop in standby); the owner set sleep to 30 min when plugged in. Defender exclusions were proposed but not added.

## v2 — plan (2026-11-11)

Two features: a **force guide update** button and **Multiview** (two live channels at once, like TiviMate). Same ground rules as v1: small verifiable phases, pure logic in `*Logic.kt` with tests, every provider call through `ProviderGuard` / `RefreshLogic`, release build verified on the TV before tagging.

### Findings before designing (2026-11-11)

- **Account connections: `max_connections` = 2** (owner, from the browser, 2026-11-11). Each multiview screen is one stream = one provider connection, so with 2 screens open **both connections are in use**. If a second stream fails to start (connection refused / limit), show: **"Your account allows 2 streams; one may be in use on another device"** – never a generic error. The account check (`player_api.php` without action) is not stored today; Phase 7 stores `user_info` (`max_connections`, `active_cons`, `exp_date`, `status`) from the check the catalog download already makes (no extra request) and shows it on the debug screen.
- **Decoders (onn 4K Plus, Amlogic S905X5M, `/vendor/etc/media_codecs_amlogic_video.xml`):** hardware decoders `c2.amlogic.{avc,hevc,vp9,av1}.decoder`, up to 9 instances each, max 4096×4096 (AV1 3840×3840). The real limit is throughput: AVC 1,036,800 blocks/s (≈ one 4K30 or two 1080p60), HEVC 2,073,600 blocks/s (≈ one 4K60 or two 4K30). Measured single-instance rates: AVC 1080p ≈ 134 fps, HEVC 1080p ≈ 220–247 fps, HEVC 4K ≈ 87–103 fps.
  - Two 1080p streams fit (AVC 2 × 50–60 fps = 100–120 of ~134 fps – tight but within budget; 2 × 1080p30 easily).
  - **Two 4K streams do not fit reliably** (AVC 4K never twice; HEVC 4K only at ≤ 30 fps each, and many 4K channels are 50/60 fps). Rule for v2: at most one screen ≥ 4K; any other screen uses a ≤ 1080p source (same programme / tvg-id, lower quality) – automatically.
  - Secure (DRM) decoders: max 2 and `supports-multiple-secure-codecs=false` – irrelevant for IPTV.
- **Video output:** the display pipeline has two hardware video composer paths (`/sys/class/video_composer/video_composer.0` and `.1`, with dedicated "pip" counters). Two screens can each use their own path. A 3rd/4th screen (2×2) would have to be composed into a shared path – unmeasured; also 4 × 1080p50 exceeds the AVC budget, so 2×2 would need ≤ 720p or ≤ 30 fps sources. v2 ships with 2; the model allows N.
- **Player today:** one ExoPlayer (`PlayerScreen.kt`) with `PlayerView`, FFmpeg software audio (`SoftAudio`), buffers from `Mem.loadControl` (this box: tier 1 = 20–40 s, 28 MB). A second player gets the small tier-0 buffer (14 MB) and no audio renderer while not selected (no second software audio decode).

### Owner's answers (2026-11-11)

1. While picking a channel the running stream keeps playing in a **small window at the top right** (like the guide's picture slot).
2. Picker search finds **channel names and titles on now**.
3. Multiview is **live channels only** (no On Demand).
4. **Resume after Pause jumps back to live.**
5. Enlarged layout: **large ≈ 75 % width at the top, small screen(s) in a row along the bottom.**
6. A 4K channel with no ≤ 1080p sibling: **try it anyway with a "may stutter" note.**
7. (Not answered – recommendation used:) a successful manual guide update also resets the 12-hour schedule.

### Phase 6 — Force guide update (home screen)

- A pill button on the home screen ("Update guide") with a status line next to it: "Guide updated 14:05" (last successful download + import), while running "Downloading guide …" / "Importing guide …", on failure the error.
- Re-downloads **only** `xmltv.php` (all guide sources of the active playlist) and re-imports into SQLite – never the catalog (`player_api.php` / `get.php`).
- New trigger in `RefreshLogic` (tested): manual guide update allowed at most every **15 min** after the last attempt, plus the existing failure backoff and provider cooldown. Pressed too soon: the line says why and when it is possible again ("Guide was updated at 14:05 – next manual update from 14:20").
- A successful manual update counts as a guide success for the 12-hour schedule.

### Phase 7 — Multiview groundwork (no new screens)

- Store `user_info` from the account check; debug screen shows max/active connections and expiry. If `max_connections` is 1, the Multiview option explains it instead of opening.
- Runtime decoder probe (`MediaCodecList`: per-codec max instances, blocks/s, performance points) + a debug-screen **dual-decode test**: plays two chosen channels at once for 30 s and reports dropped frames / decoder used / resolution per player – measured on the 4K Plus before building the UI.
- `MultiviewLogic.kt` (pure, tested): screen model for N screens (v2: max 2), layouts (SPLIT 50/50; ENLARGED: one large + small row along the bottom), selection moves (Left/Right in SPLIT, Up/Down in ENLARGED), per-screen menu (Change channel, Pause/Resume, Enlarge/Reduce, Full screen, Remove), Back → confirm, what "Remove" / "Full screen" leave behind, and the quality budget (choose a ≤ 1080p sibling source when another screen is ≥ 4K; fall back to the same source with a warning if none exists).

### Phase 8 — Multiview screen

- Entry: in the player, OK → the existing options plus **Multiview**. Choosing it opens the channel picker (Phase 9); the picked channel opens next to the current one, 50/50.
- Each screen its own ExoPlayer + surface; the selected screen has a visible highlight and the audio; the other is muted with its audio renderer disabled.
- OK on a screen → menu: Change channel · Pause · Enlarge screen / Reduce screen · Full screen · Remove screen. Full screen: that channel continues in the normal player, the other stream stops. Remove: that stream stops, the remaining one goes full-screen.
- Back: "Exit multiview?" – Yes: the selected screen continues full-screen; No: stay.
- Error / provider limit (e.g. HTTP 4xx because of connections) on one screen: shown on that screen only; the other keeps playing.

### Phase 9 — Channel picker ("Add channel" / "Change channel")

- The normal home screen and its Live TV / Movies / Shows / Sports / Events screens and search, in a picker mode: choosing a card or channel returns it to Multiview instead of starting the player. The running stream keeps playing while picking (see questions).
- Change channel uses the same picker and replaces only that screen.
- Quality rule from Phase 7 applied when the channel is added.

### Phase 10 — Tuning and release

- Owner tests on the TV; fix navigation, layout, audio switching, quality fallback. Release build (signed), verified on the TV, README/CLAUDE.md updated, tag `v2.0`.

## Definition of done (v1)

- OnAir name, icon and banner on the Google TV home screen
- Five-tile home screen; Live TV opens the guide
- Movies, Shows, Sports (grouped) and Events (live + upcoming) populated from the Xtream guide, with channel, progress and one-press Watch
- Smooth on the onn 4K Plus with the owner's full lineup; no credentials in the repo
- Builds cleanly, tests pass, README credits IPVIO Stream
