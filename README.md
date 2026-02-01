# BeatFetcher

A modern Android application that extracts audio from YouTube and local video files into M4A, and provides a built-in Media Session player. Built with Jetpack Compose + Material 3, MVVM, and Media3.


## Features

- **Two-mode UI (Extractor / Media Session)**: A persistent animated wave header acts as the app’s navigation and mode switch.
  - Tap / double-tap toggles app mode.
  - Vertical swipe on the center control toggles Light/Dark variant.
- **Unified theming**: Light/Dark is a contrast variant; the active mode controls the base hue (Extractor = Purple, Media Session = Blue). The entire UI is tinted from a single resolved `ColorScheme`.
- **YouTube Audio Extraction**: Extracts audio and converts to M4A (AAC in MP4 container) using Media3 Transformer.
- **Public Music Visibility**: Saves output into the device's public Music directory via MediaStore (e.g., `Music/`)
- **Reactive Downloads List**: Room + Flow for live updates as downloads complete or are deleted
- **Audio Playback**: Built-in player (Media3 ExoPlayer + Media3 Session) supporting content URIs and a foreground playback notification.
- **Progress & Notifications**: Throttled progress updates
- **File Management**: Delete downloads (handles both file paths and content URIs)
- **Local Video Conversion**: Convert local video files to audio-only M4A
- **Modern UI**: Material 3 with Jetpack Compose

## Technical Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: MVVM + Repository
- **DI**: Hilt
- **Database**: Room + Flow
- **Networking**: OkHttp
- **YouTube Extraction**: NewPipe Extractor (`v0.25.1`)
- **Transform/Playback**: Media3 Transformer (1.4.1) for M4A, Media3 ExoPlayer
- **Concurrency**: Kotlin Coroutines + Flow
- **Images**: Coil
- **Storage**: MediaStore (scoped storage compliant)
- **Build**: Gradle

## Project Structure

```bash
app/src/main/java/com/example/youtubetomp3/
├── data/                    # Data layer
│   ├── AppDatabase.kt      # Room database
│   ├── DownloadDao.kt      # Data access objects
│   └── DownloadItem.kt     # Data entities
├── di/                     # Dependency injection
│   └── AppModule.kt        # Hilt modules
├── newpipe/                # NewPipe integration
│   └── NewPipeLinkProcessor.kt
├── repository/             # Repository layer
│   └── DownloadRepository.kt
├── service/                # Business logic services
│   ├── AudioPlayerService.kt
│   ├── DownloadService.kt
│   ├── AudioDownloadService.kt
│   ├── AudioExtractor.kt
│   └── PlaybackForegroundService.kt
├── ui/                     # UI layer
│   ├── MainUiState.kt
│   ├── MainViewModel.kt
│   ├── MediaPlayerScreen.kt
│   └── YouTubePlayerPreview.kt
├── util/                   # Utilities
│   ├── AppearanceBridge.kt
│   ├── UpdateActionReceiver.kt
│   └── UpdateChecker.kt
├── MainActivity.kt         # Main activity
└── BeatFetcher.kt         # Application class
```

## Setup Instructions

### Prerequisites

- Android Studio (Koala or later recommended)
- Android SDK 30+ (Android 11+)

### Installation

- Option A — Build from source
  1. Clone the repository in your desired project folder

     ```bash
     git clone https://github.com/EnDeRTiGeR/BeatFetcher.git
     ```

  2. Build and install on a connected device/emulator
     - macOS/Linux:

       ```bash
       ./gradlew build
       ./gradlew installDebug
       ```

     - Windows:

       ```bash
       gradlew.bat build
       gradlew.bat installDebug
       ```

     - Or install a built APK via ADB:

       ```bash
       adb install -r app/build/outputs/apk/debug/app-debug.apk
       ```

- Option B — Install APK from Releases
  1. Download the latest APK from the Releases section:
     <https://github.com/EnDeRTiGeR/BeatFetcher/releases/latest>
  2. On your Android device, allow “Install unknown apps” for your browser or file manager if prompted
  3. Open the APK to install, then launch BeatFetcher

## Usage

### UI Modes + Theme Gestures

- **Extractor mode**: Used for URL input, preview, and conversion.
- **Media Session mode**: Used for playback and library/now-playing UI.
- **Switch mode**: Use the center control in the animated wave header.
  - Extractor -> Media Session: tap.
  - Media Session -> Extractor: double-tap.
- **Switch Light/Dark variant**: vertical swipe on the center control.

### Downloading YouTube Videos

1. **Paste/Share URL**: After copying the URL from youtube, tap the Paste icon; or share URL to the app
2. **Preview Video**: A video preview appears when a valid URL is present
3. **Start Download**: Tap "Convert" to begin extraction and conversion to M4A
4. **Monitor Progress**: Watch progress with throttled updates
5. **Access Files**: Downloads are inserted into the public Music directory via MediaStore (e.g., `Music/`)

### Playing Downloaded Audio

1. **View Downloads**: See all downloaded files in the app
2. **Play Audio**: Tap the play button to start playback
3. **Control Playback**: Use pause/stop controls as needed
4. **Delete Files**: Remove unwanted downloads with the delete button

### Converting Local Videos

1. **Select Video**: Choose a local video file to convert
2. **Start Conversion**: It will automatically begin conversion to M4A
3. **Monitor Progress**: Watch progress with throttled updates
4. **Access Files**: Converted files are inserted into the public Music directory via MediaStore (e.g., `Music/local audio.m4a`)

## Architecture

### MVVM Pattern

- **Model**: Data entities and repositories
- **View**: Compose UI components
- **ViewModel**: Business logic and state management

### Dependency Injection

Uses Hilt for dependency injection with modules for:

- Network services
- Database access
- Audio services
- YouTube extraction

### Data Flow

1. User enters YouTube URL
2. ViewModel extracts video ID
3. NewPipe Extractor fetches stream info and metadata
4. AudioDownloadService downloads audio via OkHttp and converts to M4A with Media3 Transformer
5. DownloadRepository saves file information
6. UI updates with progress and completion

## Key Components

### Extraction & Transform

Handles YouTube stream extraction and audio transform to M4A:

- URL/video ID parsing and metadata lookup
- Stream info via NewPipe Extractor
- M4A (AAC/MP4) output using Media3 Transformer
- Progress tracking, cancellation and error handling

### DownloadService

Background service for file downloads:

- Foreground service with notifications
- Progress updates
- File management
- Error handling

### PlaybackForegroundService

Foreground service used for playback controls and notification artwork. Notification visuals are theme-driven via a lightweight bridge (`AppearanceBridge`) to carry the resolved scheme colors outside of Compose.

### AudioPlayerService

Audio playback functionality:

- Media3 ExoPlayer integration
- Playback controls
- Audio session management

### Local Video Conversion (within AudioDownloadService)

Local video conversion functionality implemented inside `AudioDownloadService.convertLocalVideoToAudio()`:

- Media3 Transformer integration
- Progress updates
- File management
- Error handling

## Permissions

The app requires the following permissions:

- `INTERNET`: For network access
- `POST_NOTIFICATIONS` (Android 13+): For progress notifications
- `FOREGROUND_SERVICE`: For foreground work during downloads
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: For playback foreground service
- `FOREGROUND_SERVICE_DATA_SYNC`: For download foreground service
- `READ_MEDIA_AUDIO` (Android 13+): For reading media when needed
- `READ_MEDIA_VIDEO`: For video preview / local video selection where applicable
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (legacy devices only): Not required on Android 10+ with MediaStore
- `ACCESS_NETWORK_STATE`: For connectivity checks

## Build Configuration

### Gradle Configuration

- **Compile SDK**: 36
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 36
- **Kotlin Version**: 2.0.21
- **Compose Version**: 1.7.3

App ID: `com.beatfetcher.ytmp3`

### Dependencies

Key dependencies include:

- Jetpack Compose UI components
- Hilt for dependency injection
- Room for database
- OkHttp for networking
- Media3 (Transformer, ExoPlayer)
- Coil for image loading

## Development Notes

### YouTube Download Limitations

Downloading or converting YouTube content may be restricted by YouTube's Terms of Service and local laws. This app relies on the NewPipe Extractor to discover stream URLs and uses Media3 Transformer to generate M4A output. Ensure you only download content you have rights to use. For production deployments, consider:

1. Verifying compliance with YouTube's TOS and local regulations
2. Providing clear user guidance and enforcement for permitted content
3. Supporting additional formats/bitrates as allowed
4. Hardening error handling for region/age/DRM-restricted videos


## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0). See the `LICENSE` file for details. The choice of GPL-3.0 ensures compliance with the NewPipe Extractor’s strong copyleft requirements.

## Third-Party Notices

This project uses third-party components. See `THIRD_PARTY_NOTICES.md` for details, including:

- NewPipe Extractor — GPL-3.0 — <https://github.com/TeamNewPipe/NewPipeExtractor>
- AndroidX Media3 (Transformer, ExoPlayer) — Apache-2.0 — <https://developer.android.com/jetpack/androidx/releases/media3>

## Terms & Conditions

- You are responsible for ensuring you have the rights to download and convert any content.
- Use of this app must comply with YouTube's Terms of Service and applicable copyright laws.
- Do not download DRM-protected or restricted content, or redistribute copyrighted works without permission.
- The software is provided "as is" without warranty of any kind. By using this app, you agree to these terms.

## Disclaimer

This application is for educational purposes. Users should respect YouTube's terms of service and copyright laws when downloading content. The developers are not responsible for any misuse of this application.
