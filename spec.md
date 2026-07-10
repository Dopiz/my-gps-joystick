# GPS Joystick App Spec

**Goal:** 一個 Android app，透過官方 mock location provider 模擬 GPS 位置，提供懸浮搖桿即時操控移動，並支援匯入 GPX 路線自動循環播放。

## In scope
- **Mock location**：使用 Android 官方 `LocationManager` mock provider API 注入模擬座標。使用者需在「開發者選項 → 選擇模擬位置應用程式」把本 app 設為 mock app。
- **懸浮搖桿 overlay**：`SYSTEM_ALERT_WINDOW` 懸浮窗 + foreground service，可蓋在任何 app 上即時控制移動方向與速度。
- **app 內地圖（OSMDroid）**：顯示目前模擬位置、點擊地圖選點瞬移、預覽 GPX 路線軌跡。
- **GPX 匯入與自動移動**：
  - 解析 GPX 檔（track/route points）。
  - 播放模式：**循環 loop**（走到終點從起點重來）與**往返 reverse**（原路折返）。
  - **可調速度**：播放時即時調整移動速度（例如 1–30 m/s）。
  - **暫停／續播**，以及在地圖上點路線任一點**從該點開始**。
- Kotlin + Gradle，min SDK 26（Android 8），target 最新穩定版。

## Out of scope
- **反偵測／繞過反作弊**（reason：屬於規避第三方服務安全控制，不協助。本 app 走系統標準 mock，系統與有反作弊機制的 app 可偵測到為模擬位置）。
- **導航路線規劃**（reason：不做 A→B 自動算路，只沿使用者提供的 GPX 走；YAGNI）。
- **雲端同步／帳號系統**（reason：純本機工具，不需要）。
- **iOS**（reason：mock location 機制與此完全不同，另案處理）。

## Success criteria（df-verify 依此驗證）
1. 設為模擬位置 app 後，開啟 mock，其他讀取 GPS 的 app（如 Google Maps 藍點）顯示的位置等於本 app 設定的座標。
2. 懸浮搖桿在切到其他 app 時仍可見且可操作，拖動搖桿時模擬位置依方向與速度連續改變。
3. 匯入一個 GPX 檔後，地圖能畫出路線軌跡；按播放後模擬位置沿軌跡移動，走到終點依所選模式（loop／reverse）繼續。
4. 播放中調整速度滑桿，移動速率即時改變；按暫停位置停住，續播從停住點繼續。
5. 在地圖上點路線某點，播放起點跳到該點。

## Risks / edges
- **權限流程**：mock provider 需使用者手動到開發者選項設定，無法用程式強制；overlay 權限需引導跳系統設定頁。首次啟動要有清楚引導。
- **GPX 格式差異**：不同工具匯出的 GPX 有 `<trk>`／`<rte>`／`<wpt>` 差異，需容錯解析；空檔或無座標點要有錯誤提示。
- **座標間插值**：GPX 點間距不定，需依速度做時間插值，否則移動會一跳一跳。
- **背景存活**：foreground service 需持續通知，避免被系統殺掉導致 mock 中斷。
- **速度單位**：搖桿與 GPX 播放共用同一速度模型，避免兩套速度打架。
