# GPS Joystick

A personal Android app for mocking GPS location with a floating joystick overlay, map-based teleporting, GPX route playback, and a screen auto-tapper. Built for testing and development on your own device — it uses only Android's standard mock-location provider (`LocationManager.addTestProvider`) and does **not** attempt to hide mocking from other apps.

## Features

- **Mock location injection** — foreground service feeding the standard Android mock provider; starts at your real position.
- **Floating overlay** — a compact hub (app-icon root node) that expands into a vertical menu: map, joystick, direction lock, auto-tap, speed. Buttons are individual overlay windows, so taps outside the menu pass through to the app below.
- **Joystick** — direction-only control (speed always comes from the global speed model), drag handle for repositioning, hands-free direction lock.
- **Speed model** — walk / run / drive presets plus a custom speed (remembered across restarts), shared by the joystick, map cruising, and GPX playback.
- **Map page** (OSMDroid, no API key) — tap or paste coordinates, teleport, save favorites with custom names, expandable map.
- **GPX library** — import `.gpx` files into a persistent in-app library, then play routes with three modes (once / loop / reverse), pause/resume, and walk-back-to-route when you resume after joysticking away.
- **Auto-tap** — record up to three tap-point presets (max 3 points each) in-app; toggle them from the overlay (buttons 1/2/3, one active at a time). Uses an `AccessibilityService` with `dispatchGesture`, fixed 250 ms interval.

## Requirements

- Android 8.0+ (minSdk 26, targetSdk 35)
- JDK 21, Android SDK (AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21)

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Note: `gradle.properties` pins `org.gradle.java.home` to a local macOS JDK path. CI strips that line (see `.github/workflows/release.yml`).

## Device setup

1. Install the APK (sideload).
2. Grant location and overlay permissions from the app's permission card.
3. Enable **Developer options → Select mock location app** and pick this app.
4. For auto-tap: enable the app's accessibility service. On Android 13+ sideloaded apps hit **Restricted settings** — allow it via App info → ⋮ → *Allow restricted settings* (or `adb shell appops set com.dopiz.gpsjoystick ACCESS_RESTRICTED_SETTINGS allow`).

## Release

Manually triggered GitHub Actions workflow (**Actions → Release → Run workflow**, input a tag like `v1.0.0`) builds the APK and publishes a GitHub Release with auto-generated notes.

## Scope

This is a personal utility. Anti-detection / evading mock-location or automation detection by third-party apps is intentionally out of scope.
