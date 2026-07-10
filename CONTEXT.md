# CONTEXT — 術語表

- **mock provider**：Android `LocationManager.addTestProvider` / `setTestProviderLocation` 注入模擬座標的官方機制。需使用者在開發者選項指定本 app 為「模擬位置應用程式」。
- **overlay 搖桿**：以 `SYSTEM_ALERT_WINDOW` 權限畫在其他 app 之上的懸浮虛擬搖桿，控制移動方向與速度。
- **foreground service**：常駐前景服務，維持 mock 注入與 overlay 存活，附常駐通知。
- **播放（playback）**：沿匯入的 GPX 軌跡自動移動的過程。
  - **loop**：到終點從起點重來。
  - **reverse**：到終點原路折返。
- **速度模型（speed model）**：搖桿與 GPX 播放共用的單一移動速率設定（m/s），決定每次位置更新的位移量。
- **地圖**：OSMDroid（OpenStreetMap），零 API key。
