# UltraDeep 🎵

[![Build UltraDeep APK](https://github.com/ASHiSHxNagar/UltraDeep/actions/workflows/build.yml/badge.svg)](https://github.com/ASHiSHxNagar/UltraDeep/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-00F5D4.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-7928CA.svg)](https://kotlinlang.org)
[![Media3](https://img.shields.io/badge/Media3-1.3.1-F72585.svg)](https://developer.android.com/media/media3)

**UltraDeep** is a native offline Android music player engineered in Kotlin, featuring an AMOLED dark UI, dynamic ambient background lighting, synchronized lyrics engine with LRCLIB API + offline caching, MediaStore ContentObserver auto-sync, and automated CI cloud builds via GitHub Actions.

---

## ✨ Core Features

### 1. 📁 File System Scanning & Instant Auto-Sync
- **MediaStore Integration:** Queries local audio files (`MP3`, `M4A`, `FLAC`, `AAC`, `WAV`, `OGG`) with high-resolution album artwork mapping.
- **Real-Time ContentObserver:** Subscribes to `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` to automatically detect newly downloaded or deleted tracks in real time without restarting the app.
- **Manual Storage Rescan:** Includes a dedicated "Rescan Storage" button in Settings with live progress.

### 2. 🔀 Advanced Sorting & Playlist Management
- **Comprehensive Sorting:**
  - 📅 **Date Added** (Newest first)
  - 🔤 **Title** (A–Z)
  - 🎤 **Artist**
  - ⏱️ **Duration**
  - 🎲 **Randomize Order** (instant dynamic list reshuffle)
- **Local Room Database:**
  - Playlists with song cross-referencing.
  - Favorites with instant toggle.
  - Cached offline lyrics.

### 3. 🎤 Dual-Mode Lyrics Engine (Automatic + Manual)
- **Automatic Synchronized Lyrics:** Fetches synchronized `.lrc` lyrics from the [LRCLIB API](https://lrclib.net) by matching Track Title, Artist, and Duration.
- **Offline Room Cache:** Downloaded lyrics are cached in the local Room DB for offline playback.
- **Real-time Sync & Tap-to-Seek:**
  - Active line highlighted with glowing neon-cyan typography.
  - Auto-scrolls centered around 70% of screen height for natural reading.
  - Tap on any lyric line to jump/seek audio playback instantly.
- **Manual Override Bottom Sheet:**
  - Edit or paste custom `[mm:ss.xx]` LRC lyrics.
  - Download LRC files from custom online URLs.
  - "Reset to Automatic" fallback.

### 4. 🌌 Spotify-Like Ambient UI & Now Playing
- **Dynamic Ambient Gradient:** Uses AndroidX Palette to extract vibrant and dark muted tones from album artwork, animating smooth background transitions across song changes.
- **Custom Now Playing Background:** Allows picking a custom static image or loop from device storage as the ambient backdrop.
- **Modern Mini Player:** Persistent bottom player bar with marquee song titles, album art, play/pause, next, and a slim progress line.
- **Media3 MediaSessionService:** Background playback service with system lockscreen controls and notification media session.

---

## 🛠️ Architecture & Tech Stack

| Component | Library / Tool |
| :--- | :--- |
| **Language** | Kotlin 1.9.23 |
| **Min SDK / Target SDK** | minSdk 26 (Android 8.0) / targetSdk 34 (Android 14) |
| **Media Engine** | AndroidX Media3 (ExoPlayer 1.3.1, MediaSession, UI) |
| **Persistence** | AndroidX Room 2.6.1 (SQLite) with KAPT |
| **Networking** | Retrofit 2.9.0 + OkHttp 4.12.0 + Gson |
| **Palette** | AndroidX Palette KTX 1.0.0 |
| **Image Loading** | Coil 2.6.0 |
| **UI Components** | Material Components 3 (DayNight / AMOLED Dark) |
| **CI / CD** | GitHub Actions (`ubuntu-latest`, Temurin JDK 17) |

---

## 🚀 Building & Downloading APK via GitHub Actions

Because the repository includes a complete CI/CD workflow, you do not need Java or the Android SDK installed on your local computer.

1. **Trigger Cloud Build:**
   - Every push to `main` automatically runs `.github/workflows/build.yml`.
   - You can also run it manually from the **Actions** tab on GitHub (`workflow_dispatch`).
2. **Download APK Artifact:**
   - Go to the **Actions** tab on your GitHub repository.
   - Click on the latest workflow run: **Build UltraDeep APK**.
   - Under **Artifacts**, download `UltraDeep-Debug-APK.zip`.
   - Extract and install `app-debug.apk` onto your Android device.

### Updating the installed APK and keeping your data

- Builds now use increasing Android version codes and reuse the same CI debug signing key, so APKs from later GitHub Actions runs can install as updates.
- The first update from an older CI APK may still require one uninstall because its one-time signing key cannot be recovered. Before uninstalling, open the currently installed app and use **Settings → Backup & Restore → Export Backup**; save that JSON somewhere outside the app.
- After installing the new APK, open **Settings → Backup & Restore → Choose automatic backup folder** and select a folder such as `Documents/UltraDeep`. The app refreshes `UltraDeep_AutoBackup.json` on launch and when it moves to the background.
- Android clears the folder permission when an app is uninstalled. After reinstalling, select the same folder again; UltraDeep restores the saved data automatically when the new installation has no local data, then resumes automatic backups.

---

## 📄 License
This project is licensed under the Apache License 2.0.
