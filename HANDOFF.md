# GPS Joystick — 專案交接文件

給接手此專案的 session／開發者。搭配 `CLAUDE.md`（工作慣例與硬邊界）與 `README.md`（使用者向說明）一起讀。本文聚焦**架構、資料流、實作細節**，附 `file:line`（行號為撰寫當下概值，重構後以符號名為準）。

> **硬邊界（不可跨越）**：本專案**禁止任何 anti-detection／規避第三方 app 偵測**的功能——不做 jitter/wobble、位置隨機化、teleport cooldown、距離對應冷卻時間對照等。只用標準 Android mock provider、固定決定性行為。收到這類需求一律拒絕。詳見 `CLAUDE.md`。

---

## 0. 專案概觀

- **套件**：`com.dopiz.gpsjoystick`，單 module Android app，個人 sideload 用。
- **功能**：mock GPS 注入、懸浮搖桿、OSMDroid 地圖傳送、GPX 路線播放（一次／巡迴／往返＋暫停＋走回路線）、無障礙連點器（3 組預設）。
- **技術棧**：Kotlin、plain XML views＋ViewBinding、**無 Compose／無 DI／無 DB**（只用 SharedPreferences via `SessionStore`）。
- **狀態傳遞**：以 `StateFlow` 為主，跨元件直接讀取，無依賴注入容器。

---

## 1. 模組清單（`app/src/main/java/com/dopiz/gpsjoystick/`）

### 核心服務

| 檔案 | 職責 | 關鍵符號 |
|---|---|---|
| `MockLocationService.kt` | 前景服務；mock 注入、GPX 播放引擎、joystick 方向狀態，暴露 `state: StateFlow<MockState>` | `onStartCommand()`、`startInjecting()`（註冊 test provider＋100ms tick）、`advancePosition()`（tick 引擎：glide／走回路線 RETURNING／GPX cursor／方向）、`setJoystick()`（0.12 dead-zone）、`setPlaybackRoute()`、`resumeRoute()`、`pausePlayback()`（記 anchor） |
| `OverlayService.kt` | 懸浮 hub＋joystick＋子按鈕窗口；狀態反射、連點錄製介面。每個按鈕是獨立 overlay window（觸控穿透） | `showHub()`、`buildChildren()`、`toggleSpeedRing()`（走/跑/車/自訂）、`toggleTapRing()`（1/2/3）、`onTapSlot()`（短按 toggle）、`onTapSlotLongPress()`（長按就地重錄）、`enterAutoTapPick()`/`exitAutoTapPick()`、`applyState()`、`startObserving()`（同時聽 MockLocationService.state 與 AutoTapService.running） |
| `AutoTapService.kt` | `AccessibilityService`；`dispatchGesture` 合成點擊，250ms 固定週期 | `startTapping()`、`stopTapping()`、`tapAt()`（1ms tap）、`tick` runnable（INTERVAL_MS=250）、`running: StateFlow<Int?>`（slot 1..3 或 null）、靜態 `instance` |

### Activities

| 檔案 | 職責 | 關鍵符號 |
|---|---|---|
| `MainActivity.kt` | 權限卡（動態排序：未全備排第一，全備移到底）、start/stop mock、連點 slot 錄製入口、header（圓角 logo＋App 名） | `startMock()`、`recordTapSlot()`（發 ACTION_PICK_TAP 給 OverlayService）、`renderPermissions()`（逐列綠勾／紅叉＋reorder）、`renderTapSlots()` |
| `MapActivity.kt` | OSMDroid 地圖：傳送、最愛、速度晶片、GPX 巡航控制、放大地圖 | `loadGpxById()`、`teleportTo()`、播放模式一次/巡迴/往返、速度晶片走/跑/車/自訂 |
| `GpxLibraryActivity.kt` | GPX 庫：匯入（SAF OpenDocument）／匯出（CreateDocument）／刪除／改名／選用 | `doImport()`、`doExport()`、`render()` |
| `FavoritesActivity.kt` | 最愛座標：點選返回座標、**全部匯出／匯入 JSON（合併去重）** | `doExport()`（`FavoritesStore.exportJson()`）、`doImport()`（`FavoritesStore.importMerged()`） |

### 資料模型與引擎

| 檔案 | 內容 |
|---|---|
| `MockState.kt` | data class：isRunning、lat/lng、speedMps、direction、heading*、movementPaused、playback、glide*、error。預設 lat=25.0339 lng=121.5645 speed=5.0 |
| `Playback.kt` | `Playback` data class（points、mode、active、paused、segmentIndex/Progress、forward、hasAnchor、anchor*、returning）＋`PlaybackMode` enum（ONCE/LOOP/REVERSE）＋`GeoPt`＋`PlaybackEngine`（`segMeters()`、`currentPos()`、`currentBearing()`、`step()`；等矩投影，非 great-circle） |
| `Direction.kt` | `Direction` enum（NONE/N/E/S/W，含 sign 與 bearing）＋`DirectionEngine`（`step()` cardinal、`stepVector()` 自由向量、`bearingOf()`） |
| `SpeedModel.kt` | 無狀態速度工具，MIN=1.0／MAX=30.0 m/s、`distanceMeters()`。只管速度值，不涉方向 |
| `PermissionChecker.kt` | `status()` 回 `Status`（locationGranted、isMockAppSelected、overlayGranted、notificationsGranted、mockReady）；各檢查方法＋跳轉 Intent（開發者選項／App 詳情／懸浮設定） |

### Stores

| 檔案 | 儲存 | 說明 |
|---|---|---|
| `SessionStore.kt` | SharedPreferences ×2 | `"mock_session"`＝session 狀態；`"ui_prefs"`＝UI 設定。所有 key 見 §3 |
| `GpxStore.kt` | `filesDir/gpx/<uuid>.gpx`＋SharedPreferences `"gpx_library"` metadata（JSONArray） | `import()`（複製＋驗證≥1 點）、`get()`（解析回 GeoPt）、`list()`、`delete()`、`file()`（供匯出） |
| `FavoritesStore.kt` | SharedPreferences `"favorites"` key `"items"`（JSONArray） | `Fav{lat,lng,label}`；`add/rename/removeAt`、`exportJson()`、`importMerged()`（Triple 去重、回 (imported, skipped)） |
| `GpxParser.kt` | — | 最小 GPX 解析（XmlPullParser，讀 trkpt/rtept/wpt，無第三方庫） |

### 自訂 View

| 檔案 | 職責 |
|---|---|
| `JoystickView.kt` | 虛擬搖桿，回報 north/east 單位向量＋magnitude 0..1；`locked`（免持鎖定）、`onDragWindow`（拖動視窗）。knob 位移 clamp 避免裁切 |
| `RadialMenuView.kt` | hub 圓盤＋直列選單幾何；columnCount=5（地圖/搖桿/鎖定/連點/速度）、autoTapIndex=3、speedIndex=4；展開方向自適應 |
| `ChildButton.kt` | 單一圓形按鈕（hub 或子按鈕）；`Glyph` enum（HUB/JOYSTICK/PLAY/PAUSE/SPEED/TEXT/WALK/RUN/CAR/MAP/MAP_OPEN/LOCK/LOCK_OPEN/TAP/TARGET/TUNE）、`active`（綠環）、`fillOverride`、`dimmed`、`hubExpanded`。Material Symbols 單路徑 glyph |
| `TapPickView.kt` | 全螢幕連點錄製畫布；以螢幕絕對座標 rawX/rawY 記點（最多 3），畫標記時用 `getLocationOnScreen()` 扣視窗偏移（見 §6 座標坑） |

---

## 2. 資料流與狀態

**兩個所有權明確的 StateFlow：**

- `MockLocationService.state: StateFlow<MockState>`（服務獨佔寫入）→ OverlayService（`applyState()` 驅動按鈕視覺）、MapActivity（marker 同步）讀。
- `AutoTapService.running: StateFlow<Int?>`（值＝執行中 slot 或 null）→ OverlayService（`setAutoTapVisual()` 高亮當前 slot）讀。

**Joystick 方向鏈**：`JoystickView.onMove()` → OverlayService listener → `MockLocationService.setJoystick()` → `_state.update{...}` → tick `advancePosition()` → `DirectionEngine.stepVector()` → lat/lng → `LocationManager.setTestProviderLocation()`。

**速度共享**：速度值存於 `MockState.speedMps`，`SpeedModel` 只做計算。OverlayService/MapActivity 選速 → `setSpeed()` → `_state`。tick loop 讀 speedMps 供 Playback/Direction 消費。搖桿設方向、速度恆取全局值（不隨位移縮放）。

**Playback 生命週期**：選 GPX → `setPlaybackRoute()`。暫停記 anchor（`hasAnchor=true`）。傳送／搖桿會停用 playback 但**保留 anchor**，`resumeRoute()` 依 anchor 距離決定是否進入 RETURNING 相位（直線走回）再續跑。

---

## 3. 持久化（`SessionStore`）

### `"mock_session"`（session 狀態，`startInjecting()` 每 1000ms 存、`onDestroy` 前存）
K_RUNNING(bool)、K_LAT/K_LNG/K_SPEED（Double→long bits）、K_PB_ACTIVE(bool)、K_PB_PAUSED(bool)、K_PB_MODE(String)、K_PB_INDEX(int)、K_PB_PROGRESS(Double bits)、K_PB_FORWARD(bool)、K_PB_ROUTE（`"lat,lng;..."` 編碼）。

> Double 用 `toRawBits`/`fromBits` 存 long，避免浮點精度問題。

### `"ui_prefs"`（UI 設定）
K_SPEED_CHIP(int，0/1/2 preset 或 3 custom)、K_CUSTOM_KMH(Double bits，預設 25)、K_PB_MODE_UI(String)、K_CURRENT_GPX(String id)、K_LOCK(bool)、K_JOY_VISIBLE(bool)、K_HUB_X/Y(int，中心 px)、K_JOY_X/Y(int，左上 px)、`tap_points_<slot>`(String，`"x,y;x,y;x,y"` 最多 3，slot=1/2/3)。未設定哨兵 `Int.MIN_VALUE`。

### GpxStore metadata（JSONArray）
每項：`{id, name, filename, count, importedAt}`；GPX 檔本體在 `filesDir/gpx/<uuid>.gpx`。

### FavoritesStore（JSONArray）
每項：`{lat, lng, label}`。匯出＝`array.toString()`；匯入＝合併＋(label,lat,lng) 去重。

---

## 4. 權限與系統整合

**Manifest 權限**：ACCESS_FINE_LOCATION、**ACCESS_MOCK_LOCATION**（`tools:ignore`，缺了 app 會從開發者選項 mock 清單消失）、FOREGROUND_SERVICE(+LOCATION/+SPECIAL_USE)、POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW、INTERNET、ACCESS_NETWORK_STATE。

**Services**：MockLocationService（`foregroundServiceType=location`）、OverlayService（`specialUse`＋PROPERTY_SPECIAL_USE_FGS_SUBTYPE）、AutoTapService（`BIND_ACCESSIBILITY_SERVICE`＋accessibility intent-filter＋meta-data 指向 `@xml/autotap_accessibility_config`）。

**AccessibilityService config**（`res/xml/autotap_accessibility_config.xml`）：`canPerformGestures="true"`（連點關鍵）、`feedbackGeneric`、最小事件集 `typeViewClicked`、`android:description`。

**Mock app 選擇**：以 `AppOpsManager.OPSTR_MOCK_LOCATION`（`unsafeCheckOpNoThrow`）判定。無公開 API 直接跳到「選擇模擬位置應用程式」選擇器，只能跳到開發者選項頁，使用者手動選。

**Restricted settings（Android 13+）**：sideload app 啟用無障礙會被擋。解法：App info → ⋮ → 允許受限制的設定；或 `adb shell appops set com.dopiz.gpsjoystick ACCESS_RESTRICTED_SETTINGS allow`。

---

## 5. 建置與發布

- **版本**：AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.0.21；compileSdk/targetSdk 35、minSdk 26；JDK 21（Java 17 target）。
- **`gradle.properties`** pin 了本機 `org.gradle.java.home`（macOS Temurin 21）；**CI 會 `sed` 刪掉該行**。
- **建置**：`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（每次改動的主驗證 gate）。
- **安裝**：使用者手動從 Download 安裝（MIUI/One UI 擋 `adb install`），慣用 `adb push ... /sdcard/Download/gps-joystick.apk`。UI 檢查用 `adb exec-out screencap -p`。
- **發布**：`.github/workflows/release.yml`，**手動觸發**（Actions → Release → Run workflow，輸入 tag 如 `v1.0.0`）→ build APK →建 GitHub Release（`generate_release_notes: true`）＋上傳 APK。

---

## 6. 慣例與坑

- **繁體中文字串**：全部 user-facing 文字在 `res/values/strings.xml`，標點用全形。
- **配色（Material 3 OLED dark）**：primary 藍 `#3B82F6`、active 綠 `#22C55E`、paused amber `#F59E0B`、error 紅 `#EF4444`、surface `#131A22`/`#1B242E`、outline `#2A3542`。
- **MaterialButton insets**：預設有水平 inset，圓形按鈕要把四邊 inset 與 `minWidth/minHeight` 全歸零（否則變橢圓——放大地圖鈕曾踩過）。
- **Overlay 座標**：懸浮視窗不一定從螢幕 (0,0) 起。存**螢幕絕對座標**，繪製時用 `getLocationOnScreen()` 扣偏移（`TapPickView`）。連點分發時 point 直接當 rawX/rawY 給 `dispatchGesture`。
- **Joystick dead-zone**：`0.12`，低於視為停；高於即全速（不隨位移縮放）。
- **Session 恢復**：`START_STICKY` 系統重啟（intent==null）且 in-memory 為初始態才從 SessionStore 還原，避免 UI 觸發的 START 被舊持久狀態劫持。
- **YAGNI**：無 DB／DI／Compose；SharedPreferences 手工序列化；純 XML view＋ViewBinding。維持這個路線。

---

## 7. Git 現況（撰寫當下）

- 預設分支 `main`（GitHub：`Dopiz/my-gps-joystick`）。
- 已 merge PR：#1（連點＋UI 打磨）、#2（懸浮長按重錄）。
- 進行中分支 `feat/favorites-import-export`（最愛匯入匯出，尚未開 PR，領先 main 一個 commit）。
- 未實作／已拒絕：任何冷卻時間對照、距離→等待映射（違反硬邊界）。
