# OnAir

A personal IPTV player for **Google TV / Android TV**, built around one question: *what's on right now?*

OnAir is a fork of **[IPVIO Stream](https://github.com/Emrodev/IPVIO-Stream)** by Emrodev. It keeps IPVIO's player (Media3 / ExoPlayer with the FFmpeg audio decoder), Xtream Codes login and TV guide, and adds a simpler "on now" experience in five sections:

| Section | What it shows |
|---|---|
| **Live TV** | the TV guide |
| **Movies** | films airing right now on live channels, with channel and progress |
| **Shows** | series and shows airing right now |
| **Sports** | games and sports programmes on now, grouped by sport |
| **Events** | PPV and special events, live now and coming up |

Target device: onn 4K Plus (Google TV, ~2 GB RAM, 32-bit), D-pad remote. Every screen works with D-pad + OK + Back.

> **OnAir does not provide any content.** It contains no channels, playlists, films or series. It only plays what **you** add yourself (an Xtream Codes login; M3U still works as in IPVIO). Only use sources that you are legally allowed to use.

## Status

| Phase | |
|---|---|
| 0 – Understand the codebase | done |
| 1 – Rebrand to OnAir (name, icon, TV banner, red-on-navy theme, `com.onair.tv`) | done |
| 2 – Five-tile home screen and a standalone TV guide screen | next |
| 3 – Guide in SQLite, "on now" classification (Movie / Show / Sports / Event) | planned |
| 4 – The four "on now" screens | planned |
| 5 – Tuning with a real lineup | planned |

The plan and ground rules are in [CLAUDE.md](CLAUDE.md).

## Kind to your provider

The playlist (`player_api.php`) and the TV guide (`xmltv.php`) are stored on the device. OnAir downloads them again only on the background schedule — **at most every 12 hours** — or when you press a refresh button. It never downloads on app start or when logging in with an account it already has. After errors it waits longer each time (15 min up to 12 h) and honours the provider's `Retry-After`; while a provider is throttling, no API calls are sent at all.

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
