# OnAir

A personal IPTV player for **Google TV / Android TV**, built around one question: *what's on right now?*

OnAir is a fork of **[IPVIO Stream](https://github.com/Emrodev/IPVIO-Stream)** by Emrodev. It keeps IPVIO's player (Media3 / ExoPlayer with the FFmpeg audio decoder), Xtream Codes login and TV guide, and adds a simpler "on now" experience in five sections:

| Section | What it shows |
|---|---|
| **Live TV** | the full-screen TV guide (pick a programme to watch; Back returns to the guide) |
| **Movies** | films airing right now on live channels – channel, progress, "45 min in · 1h 10m left" |
| **Shows** | series and shows airing right now, same cards |
| **Sports** | games on now, grouped by sport (NFL, College Football, NBA, College Basketball, MLB, NHL, Soccer, Combat Sports, Golf, Tennis, Motorsports, Horse Racing, Other), with "Starting soon" for the next hour |
| **Events** | PPV and special events: live now, coming up (24 h), and PPV channels that carry an event name but no guide data |

The home screen shows a live count on each tile ("12 on now"). On every "now" screen, OK on a card opens a small panel with the description, times and **Watch**; when the same programme runs on several channels (HD / FHD / 4K, regions) they are merged into one card and the panel lets you pick the source, best quality first. IPVIO's own Movies/Series library is still there under **On Demand**.

Target device: onn 4K Plus (Google TV, ~2 GB RAM, 32-bit), D-pad remote. Every screen works with D-pad + OK + Back.

> **OnAir does not provide any content.** It contains no channels, playlists, films or series. It only plays what **you** add yourself (an Xtream Codes login; M3U still works as in IPVIO). Only use sources that you are legally allowed to use.

## Status

**v1.0** (November 2026) – all phases done and approved on the onn 4K Plus.

| Phase | |
|---|---|
| 0 – Understand the codebase | done |
| 1 – Rebrand to OnAir (name, icon, TV banner, red-on-navy theme, `com.onair.tv`) | done |
| 2 – Five-tile home screen and a standalone TV guide screen | done |
| 3 – Guide in SQLite, "on now" classification (Movie / Show / Sports / Event) | done |
| 4 – The four "on now" screens | done |
| 5 – Tuning with a real lineup | done for v1 (keyword tables are data, easy to extend) |

The plan, ground rules and the v1.0 design notes are in [CLAUDE.md](CLAUDE.md).

## How "on now" works

- **Login:** the Xtream account is read through `player_api.php` (live, films and series with their categories); `get.php` is only a fallback. Series episodes are fetched when you open a series.
- **Guide:** the provider's XMLTV (`xmltv.php`) is stored on the device and imported into a small SQLite database (now − 3 h to now + 36 h; catch-up channels keep their archive days). Screens read only what they need.
- **Classification** (`OnNowLogic.kt`, unit-tested): every programme on now is an Event, Sports, Movie, Show or Other, from the programme title, the Xtream category name and the channel name (and the XMLTV categories / episode numbers when a provider sends them). Keyword tables are plain data, accent- and case-insensitive (Spanish channels work). A sports category alone doesn't make a programme Sports – the title has to say so. News, weather, religion, shopping and placeholders never show up in Movies or Shows.
- **PPV / events** are recognised by their Xtream category (PPV, EVENTS, …) or an unmistakable PPV channel name. Empty slots ("PPV 23") are hidden. Providers often name the slots shortly before an event, so OnAir asks for just the PPV categories again – at most every 30 minutes, when the home or Events screen opens – and shows the new names everywhere.
- **Hidden debug screen:** Settings → About → long-press the version number – guide size and XMLTV field coverage, counts per class and sport, PPV categories, samples of what lands in Other / Other Sports, and timings.

## Kind to your provider

The playlist (`player_api.php`) and the TV guide (`xmltv.php`) are stored on the device. OnAir downloads them again only on the background schedule — **at most every 12 hours** — or when you press a refresh button. It never downloads on app start or when logging in with an account it already has. The only other regular request is the small PPV-name check above (one category list, at most every 30 minutes). After errors it waits longer each time (15 min up to 12 h) and honours the provider's `Retry-After`; while a provider is throttling, no API calls are sent at all.

## Inherited from IPVIO Stream

- Xtream Codes login, several playlists, input from your phone over Wi-Fi; very large playlists (language filter, resumable downloads, compact storage)
- Home, Movies and Series browsing with filters, provider categories or automatic genres, search, detail pages, seasons and episodes
- Live TV with categories, favourites, own channel lists, TV guide, catch-up, reminders
- Player: audio/subtitle selection, AC3/E-AC3/DTS via FFmpeg, skip intro/credits (TheIntroDB), quality choice with fallback, reconnect after network loss, frame-rate matching, sleep timer
- Profiles, parental PIN, history, my list, backup/restore
- Colour themes (OnAir red on navy by default), fonts, display size; 9 UI languages (OnAir's own additions are in English)

## Your own API keys (optional)

The app ships **without any API keys**. Enter your own free keys under **Settings > API keys** (stored only on the device):

| Service | Used for |
|---|---|
| TMDB | posters, descriptions, cast, trailers, automatic genres — <https://www.themoviedb.org/settings/api> (log in first, choose *Developer*, copy the 32-character *API Key*) |
| OMDb | IMDb and Rotten Tomatoes ratings — <https://www.omdbapi.com/apikey.aspx> (free tier; click the activation link in the e-mail) |
| TheIntroDB | intro/recap/credits times — no key needed |

Without keys the app still works: with an Xtream login it takes descriptions, covers and ratings from the provider.

*This product uses the TMDB API but is not endorsed or certified by TMDB.*

## Building and installing

Requirements: Android Studio (current) with the Android SDK; the Gradle wrapper fetches the rest.

```
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
./gradlew test                 # unit tests for the pure logic
```

Install on the onn 4K Plus (sideloaded, not via Play Store):

```
adb connect <onn-ip>:5555      # or the pairing port shown under Wireless debugging
adb -s <onn-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

**Release build:** `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (R8-optimised, ~12 MB). It is signed when a signing file exists outside the repository – by default `~/.onair/signing.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (or pass `-Ponair.signing=<path>` / set `ONAIR_SIGNING`); otherwise it is built unsigned. Keystores and passwords never go into the repository. Debug and release builds are signed with different keys: switching between them on a device needs an uninstall, which deletes the app's data (login, favourites).

Logging: errors are written to logcat under the tag `OnAirLogin` (passwords are always masked); debug builds add short status lines.

On the TV: Settings → System → About → press "Android TV OS build" 7 times → Developer options → enable USB debugging / Wireless debugging.

OnAir uses the application ID `com.onair.tv`, so it installs next to an original IPVIO Stream. The Kotlin package is still `com.ipvio.stream` to keep merging from upstream easy (`git fetch upstream`).

Credentials are entered in the app only and stored encrypted on the device — never in the repository, `secrets.properties` or tests. Developers may copy `secrets.properties.example` to `secrets.properties` (git-ignored) for TMDB/OMDb keys.

## Project layout

```
app/src/main/java/com/ipvio/stream/   all Kotlin sources (one package, inherited from IPVIO)
  *Logic.kt                           pure, Android-free logic (covered by unit tests)
  Dict*.kt                            translations (one row per text: de¦en¦tr¦bs¦hr¦sr¦es¦fr¦it)
app/src/test/java/com/ipvio/stream/   JUnit tests
app/src/main/res/font/                Outfit and Inter (SIL Open Font License)
licenses/                             font licences
```

## Credits and licence

OnAir is based on **[IPVIO Stream](https://github.com/Emrodev/IPVIO-Stream)** by Emrodev — thank you! The name "IPVIO" and the IPVIO logo belong to the original project; OnAir uses its own name and logo, as IPVIO asks of modified versions.

Copyright (C) 2026 IPVIO Stream contributors; OnAir changes (C) 2026 the OnAir contributors.

OnAir is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License version 3** (see [LICENSE](LICENSE)). The GPL is required because the app includes the GPL-3.0 licensed FFmpeg audio decoder. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the libraries, fonts and services used.

## Disclaimer

This software is provided "as is", without warranty of any kind. The authors do not provide, host, sell or link to any streams or playlists and are not responsible for how the app is used. Using playlists without the rights holders' permission may be illegal in your country.
