# GPS Joystick

A personal Android app for mocking GPS location with a floating joystick overlay, map-based teleporting, GPX route playback, favorites with categories, and a screen auto-tapper. Built for testing and development on your own device — it uses only Android's standard mock-location provider (`LocationManager.addTestProvider`) and does **not** attempt to hide mocking from other apps.

## Features

- **Mock location injection** — foreground service feeding the standard Android mock provider; starts at your real position.
- **Floating overlay** — a compact hub (app-icon root node) that expands into a vertical menu: map, joystick, direction lock, auto-tap, speed. Buttons are individual overlay windows, so taps outside the menu pass through to the app below.
- **Joystick** — direction-only control (speed always comes from the global speed model), drag handle for repositioning, hands-free direction lock.
- **Speed model** — walk / run / drive presets plus a custom speed (remembered across restarts), shared by the joystick, map cruising, and GPX playback.
- **Map page** (OSMDroid, no API key) — tap or paste coordinates, teleport (glides to the target), long-press to drop a pin and jump there, expandable map.
- **Walk to a coordinate** — instead of teleporting, walk in a straight line to the target at the current shared speed; the map draws the start/end markers and the guide line while the leg is running.
- **Favorites** — a full-screen list (RecyclerView) with:
  - user-defined **categories**; `未分類` is implicit, always first, and cannot be renamed or deleted;
  - add / rename categories, delete a category (its favorites fall back to `未分類`);
  - per-row menu: rename, edit coordinates, move to another category, delete;
  - **multi-select** (long-press a row) for batch category assignment;
  - **clear all**;
  - each row shows the distance from the current position and the suggested cooldown for jumping there;
  - **teleport** or **walk** to a favorite straight from the row;
  - **import / export** as JSON (see below).
- **GPX library** — import `.gpx` files into a persistent in-app library, then play routes with three modes (once / loop / reverse), pause/resume, and walk-back-to-route when you resume after joysticking away.
- **Radius cruise** — enter a radius in km (up to 1000) and the app generates a circular route that *starts at the current mock position* (the circle's centre sits due south of it, so the current position is the northernmost point), sampled every 5°. The generated route can be saved into the GPX library as a real `.gpx` file and replayed like any imported route.
- **Teleport cooldown countdown** — every teleport computes a suggested wait from the distance between the position you are leaving and the target, then counts it down in four places: a map banner, a card on the home screen, the foreground notification, and a badge on the overlay hub. Walking never starts a cooldown. The countdown is stored in `SharedPreferences` (start timestamp + total seconds), so it survives a process restart. Format is `mm:ss` below an hour and `h:mm` from an hour up.
- **Auto-tap** — record up to three tap-point presets (max 3 points each) in-app; toggle them from the overlay (buttons 1/2/3, one active at a time), and **long-press 1/2/3 to re-record that slot in place** without leaving the current app. Uses an `AccessibilityService` with `dispatchGesture`, fixed 250 ms interval.

The cooldown brackets are community-observed Pokémon GO guidance, not an official rule from any game — treat them as a hint, not a guarantee.

## Requirements

- Android 8.0+ (minSdk 26, targetSdk 35, compileSdk 35)
- JDK 21 to run Gradle (the build compiles to Java 17 bytecode)
- AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Note: `gradle.properties` pins `org.gradle.java.home` to a local macOS JDK path. CI strips that line (see `.github/workflows/release.yml`).

## Device setup

1. Install the APK (sideload).
2. Grant **location** (`ACCESS_FINE_LOCATION`) and **overlay** (`SYSTEM_ALERT_WINDOW`) permissions from the app's permission card. The overlay permission is required for the floating hub/joystick and for recording auto-tap points.
3. Enable **Developer options → Select mock location app** and pick this app. The app detects this via the `MOCK_LOCATION` app-op, so the permission card updates by itself once you have selected it.
4. Allow **notifications** (Android 13+) — the mock and overlay services run in the foreground, and the ongoing notification is where the cooldown countdown appears.
5. For auto-tap: enable the app's accessibility service. On Android 13+ sideloaded apps hit **Restricted settings** — allow it via App info → ⋮ → *Allow restricted settings* (or `adb shell appops set com.dopiz.gpsjoystick ACCESS_RESTRICTED_SETTINGS allow`).

Map tiles are downloaded from the network, so `INTERNET` / `ACCESS_NETWORK_STATE` are declared; they are install-time permissions and need no user action.

## Favorites import format

Export writes, and import reads, a **single top-level JSON array** of objects:

```json
[
  {
    "label": "Fukuoka",
    "lat": 33.589,
    "lng": 130.4194,
    "category": "Japan"
  },
  {
    "label": "Hiroshima",
    "lat": 34.3976,
    "lng": 132.4763
  }
]
```

Fields:

| Field | Required | Notes |
| --- | --- | --- |
| `lat` | yes | number, decimal degrees |
| `lng` | yes | number, decimal degrees |
| `label` | no | display name; missing or empty gives an unnamed entry |
| `category` | no | missing, empty or whitespace-only falls back to `未分類` |

Import behaviour:

- Import **merges** into the existing list; it never replaces it. Use *clear all* first if you want a clean slate.
- A row is skipped as a duplicate when `label` + `lat` + `lng` all match an existing favorite exactly. `category` is not part of the duplicate key, so re-importing the same points with a different category will not move them.
- Category names that do not exist yet are created automatically, in the order they first appear.
- Malformed JSON, or an entry missing `lat` / `lng`, aborts the whole import — nothing is written.
- After import you get a toast with how many entries were imported and how many were skipped.

Any text editor works for preparing the file; the picker accepts `application/json` (and `*/*` as a fallback for file managers with odd MIME types).

## Release

Manually triggered GitHub Actions workflow (**Actions → Release → Run workflow**, input a tag like `v1.0.0`) builds the APK and publishes a GitHub Release with auto-generated notes.

## Scope

This is a personal utility. Anti-detection / evading mock-location or automation detection by third-party apps is intentionally out of scope.
