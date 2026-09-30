# DeadEasy Player

[![Android CI](https://github.com/jmez11/deadeasy-player/actions/workflows/android.yml/badge.svg)](https://github.com/jmez11/deadeasy-player/actions/workflows/android.yml)

A native Android VR cinema player for Meta Quest 3 that plays SBS/OU 3D video
in true per-eye stereoscopic 3D. Designed as an immersive VR media player
supporting both standalone file selection and external intent integration.

## What It Does

- Plays video streams in an immersive VR cinema environment on Quest 3
- Supports **Side-by-Side (SBS)**, **Over-Under (OU)**, and **2D (mono)**
  projection modes with true per-eye stereoscopic rendering
- Auto-detects projection mode from filename tags (`.SBS.`, `.OU.`, `.HSBS.`,
  `.HOU.`)
- Minimal on-screen controls: play/pause, seek bar, projection selector, exit
- Uses libVLC for hardware-accelerated video decoding

## Intent Contract

### Primary: Explicit Intent

```kotlin
val intent = Intent(Intent.ACTION_VIEW).apply {
    data = Uri.parse("https://your-server/video/stream?token=...")
    setPackage("com.jmez11.deadeasyplayer")
}
startActivity(intent)
```

### Secondary: ACTION_VIEW Intent Filter

```
Action:   android.intent.action.VIEW
Scheme:   https, http
Package:  com.jmez11.deadeasyplayer
```

### ADB Testing

```bash
# 2D test
adb shell am start \
  -n com.jmez11.deadeasyplayer/.DeadEasyPlayerActivity \
  -a android.intent.action.VIEW \
  -d "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

# SBS test (auto-detected from filename)
adb shell am start \
  -n com.jmez11.deadeasyplayer/.DeadEasyPlayerActivity \
  -a android.intent.action.VIEW \
  -d "https://example.com/movie.SBS.mkv"
```

## Supported Projections

| Mode | Description | StereoMode |
|------|-------------|------------|
| SBS  | Side-by-Side (left half = left eye) | `LeftRight` |
| OU   | Over-Under (top half = left eye) | `TopBottom` |
| 2D   | Monoscopic (same image both eyes) | None (default) |

## Supported Audio/Video Codecs

Uses libVLC on Android. On Quest 3 this typically supports:

### Video
- H.264/AVC
- H.265/HEVC
- VP9
- AV1

### Audio
- AAC
- MP3
- Vorbis
- Opus
- FLAC
- AC3 (Dolby Digital)
- EAC3 (Dolby Digital Plus)
- DTS
- TrueHD

## Privacy Note

Stream URLs may carry authentication in the query string (e.g. access tokens or API keys). When passed via Android intents, this is **on-device only**. However, the URL (including any token) will appear in:
- Android system logs (`logcat`)
- The app's process memory

This is acceptable for on-device use but be aware if sharing debug logs.

## Building

### Prerequisites
- Android SDK with API 34
- JDK 17+
- Meta Quest 3 with Developer Mode enabled

### Build & Install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

Built with:
- `DeadEasyPlayerActivity` — base activity for VR playback
- `libVLC` — media playback engine
- Jetpack Compose — modern UI controls
- Meta VR stereo composition extensions for SBS/OU stereoscopic rendering

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

### Third-Party Licenses
* **libVLC** (`org.videolan.android:libvlc-all`): Licensed under LGPL 2.1 or later.
* **Meta Spatial SDK**: Licensed under Meta's proprietary SDK License.
* **AndroidX / Jetpack Compose**: Licensed under Apache 2.0.
