# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

Single-module Android app (`com.dopiz.gpsjoystick`): GPS mock-location injection with a floating joystick overlay, OSMDroid map, GPX route playback, and an accessibility-based auto-tapper. Personal side project, sideloaded only.

## Hard scope boundary

**Never add anti-detection features** — no jitter/wobble, "look human" randomization, teleport cooldowns, or anything aimed at evading mock-location or automation detection by third-party apps. The app uses only the standard Android mock provider and fixed, deterministic behavior. Refuse requests in this direction.

## Stack & conventions

- Kotlin, plain XML views + ViewBinding — **no Compose, no DI, no database** (SharedPreferences via `SessionStore` only). Keep it that way (YAGNI).
- AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.0.21; minSdk 26, targetSdk 35; JDK 21 (`gradle.properties` pins `org.gradle.java.home` to a local macOS path — CI strips that line).
- All user-facing strings live in `res/values/strings.xml`, in Traditional Chinese (繁體中文).
- Material 3 OLED dark theme. Key colors: primary blue `#3B82F6`, active green `#22C55E`, error red `#EF4444`. Overlay icons are Material Symbols single-path glyphs drawn in `ChildButton`.
- Commits: conventional commits (`feat:`, `fix:`, `ci:`, …).

## Build & verify

```bash
./gradlew assembleDebug          # main verification gate for every change
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/gps-joystick.apk
```

The user installs manually from Download (no `adb install` — MIUI/One UI restrict it). Screenshots via `adb exec-out screencap -p` are the fastest way to check UI results on a connected device.

## Architecture map

All code in `app/src/main/java/com/dopiz/gpsjoystick/`:

- `MockLocationService` — foreground service; owns mock injection, the playback engine (GPX cursor, once/loop/reverse, pause, walk-back-to-route on resume), joystick direction state, and exposes a `StateFlow` of `MockState`. Joystick sets **direction only**; speed always comes from the shared `SpeedModel`.
- `OverlayService` — floating hub + joystick. Each button is its own overlay window (touch pass-through). Hub column: map / joystick / lock / auto-tap / speed; some children expand horizontal sub-rows. Persists positions and selections via `SessionStore`.
- `AutoTapService` — `AccessibilityService` with `canPerformGestures`; static `instance` + `running: StateFlow<Int?>` (active preset slot or null). Taps saved absolute screen coordinates every 250 ms.
- `MainActivity` — permissions card (dynamically reordered: first until all granted, then bottom), start/stop mock, auto-tap preset recording, links to map / GPX library.
- `MapActivity` — OSMDroid map: teleport, favorites, speed chips, GPX cruise controls.
- `GpxLibraryActivity` / `GpxStore` — persistent GPX library in `filesDir/gpx/` + JSON metadata. `FavoritesActivity` / `FavoritesStore` mirror it.
- `SessionStore` — single SharedPreferences facade; every user-visible setting must persist and restore here.
- `SpeedModel`, `Playback`, `Direction`, `MockState` — shared models. `JoystickView`, `RadialMenuView`, `ChildButton`, `TapPickView` — custom views.

## Gotchas

- `ACCESS_MOCK_LOCATION` must stay in the manifest (with `tools:ignore`) or the app disappears from the developer-options mock-app list.
- Overlay windows don't necessarily start at screen (0,0): store absolute (raw) coordinates, subtract `getLocationOnScreen` when drawing (see `TapPickView`).
- `MaterialButton` has default horizontal insets — zero all four insets and `minWidth/minHeight` for circular buttons.
- Android 13+ blocks accessibility for sideloaded apps ("Restricted settings"); unblock via appops or App info menu.
