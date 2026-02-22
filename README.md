# Spotify Music Player for Android

An Android app that imports Spotify playlists, downloads songs via SoundCloud, and plays them locally with full playlist management and Bluetooth headphone support.

## Features

- **Spotify Playlist Import** - Paste a Spotify playlist URL and the app fetches all track info via the Spotify Web API
- **SoundCloud Downloads** - Automatically searches SoundCloud for each track and downloads the audio (no YouTube intros)
- **Playlist Management** - Create, rename, and delete playlists. Remove individual songs
- **Music Playback** - Play, pause, skip, seek with shuffle mode
- **Bluetooth Support** - Full AVRCP support for play/pause/skip from Bluetooth headphones, plus lock screen and notification controls
- **Background Playback** - Music continues playing when the app is in the background
- **Audio Focus** - Auto-pauses for phone calls, navigation, etc. Auto-pauses when headphones are disconnected

## Prerequisites

1. **Spotify Developer Account** - Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) to get a Client ID and Client Secret
2. **Android Studio** - For building the app (includes JDK 17 and Android SDK)

## Building

### With Android Studio (Recommended)
1. Open the project folder in Android Studio
2. Let Gradle sync complete
3. Click **Run** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**
4. APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### Command Line
```bash
# Requires JDK 17+ and Android SDK
./gradlew assembleDebug
```

## How It Works

### Download Flow
```
Spotify Playlist URL
    ↓
Spotify Web API (get track names + artists)
    ↓
SoundCloud Search (find matching tracks)
    ↓
SoundCloud Stream Download (progressive MP3)
    ↓
Local Storage + Room Database
```

### Architecture
- **Kotlin + Jetpack Compose** - Modern Android UI
- **Media3 (ExoPlayer)** - Audio playback engine
- **MediaSession** - System media integration (Bluetooth, notifications, lock screen)
- **Room** - Local SQLite database for playlists and songs
- **OkHttp** - Network requests to Spotify and SoundCloud APIs
- **Coroutines + Flow** - Async operations and reactive data

### Key Components
- `MusicPlayerService` - Background service with MediaSession for Bluetooth/notification controls
- `SoundCloudApi` - Extracts client_id from SoundCloud's JS bundles, searches tracks, downloads audio
- `SpotifyApi` - Client credentials auth, playlist track fetching with pagination
- `MusicRepository` - Orchestrates the full download pipeline
- `PlayerViewModel` - Connects UI to MediaController for playback control

## Usage

1. Open the app and tap the download button (bottom right)
2. Enter your Spotify Client ID and Client Secret (saved for next time)
3. Paste a Spotify playlist URL
4. Tap **Fetch Playlist** to see the track list
5. Tap **Download All via SoundCloud** to start downloading
6. Once complete, go back to see your playlist
7. Tap **Play All** or **Shuffle** to start listening
8. Use Bluetooth headphones - play/pause/skip all work automatically

## Tech Stack

| Component | Library |
|-----------|---------|
| UI | Jetpack Compose + Material3 |
| Audio | Media3 ExoPlayer 1.2.1 |
| Bluetooth/Media | Media3 MediaSession |
| Database | Room 2.6.1 |
| Networking | OkHttp 4.12 |
| JSON | Gson 2.10.1 |
| Navigation | Navigation Compose 2.7.7 |
| Min SDK | Android 8.0 (API 26) |
