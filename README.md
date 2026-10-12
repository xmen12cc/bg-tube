# BgTube

A tiny Android app that plays YouTube videos in the **background**: paste a link, press Play, lock your screen or switch apps — the audio keeps going.

Uses the official YouTube embed player inside a WebView, kept alive by a foreground media service (CPU wakelock included), so playback survives screen-off and app-switching.

[![Build APK](https://github.com/xmen12cc/bg-tube/actions/workflows/build.yml/badge.svg)](https://github.com/xmen12cc/bg-tube/actions/workflows/build.yml)

## Install

1. Grab the newest APK: **https://github.com/xmen12cc/bg-tube/releases/latest/download/BgTube-debug.apk**
   (or browse [Releases](https://github.com/xmen12cc/bg-tube/releases))
2. On your phone, open the downloaded APK and allow "install unknown apps" when prompted (debug-signed, for personal use).
3. Recommended: Settings → Apps → BgTube → Battery → **Unrestricted / Don't optimize**. Aggressive OEMs (Samsung, Xiaomi, etc.) otherwise kill background audio.

## Use

- Paste a YouTube link (`watch?v=`, `youtu.be`, `Shorts`, `live`, music.youtube all work) and press **Play**.
- Or in the YouTube app: **Share → BgTube** — it starts playing immediately.
- Controls: in-app play/pause/stop, the media notification, and the lock screen.
- **Repeat loop**: tap the 🔁 button (or the Loop action on the notification) to repeat the current video endlessly; the preference is remembered. Tap again to turn it off.
- Recently played videos appear as chips — tap to replay.

## How it works

- The video plays in the official YouTube IFrame embed inside a `WebView`.
- A **foreground service** (`mediaPlayback` type) owns the WebView, so playback survives backgrounding — even swiping the app from recents.
- A **partial CPU wakelock** keeps audio decoding while the screen is off.
- The WebView is tricked into believing it's always visible (classic `onWindowVisibilityChanged` override), so the player never auto-pauses.
- A JS bridge reports player state to a `MediaSession`, which drives the media notification / lock-screen controls and artwork.

## Build

GitHub Actions builds every push to `main` and publishes the APK to the [`latest` release](https://github.com/xmen12cc/bg-tube/releases/tag/latest). Locally:

```
./gradlew assembleDebug
```

## Limitations

- Videos with embedding disabled, or age-restricted videos, refuse to play in the embed player.
- Some regions show YouTube's consent page once on first load — accept it, it persists.
- Personal-use project: it plays via YouTube's official embed player and is not intended for Play Store distribution.
