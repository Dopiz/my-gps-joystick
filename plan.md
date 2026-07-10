# GPS Joystick App Plan

> Tech choice: Kotlin 單模組 app + Gradle（Kotlin DSL），min SDK 26。地圖用 OSMDroid（零 API key），GPX 用內建 XmlPullParser 自解析（格式單純，不引第三方 lib，YAGNI）。狀態由單一 foreground service 持有 in-memory 狀態 + StateFlow 對外，不引 DI／DB（純本機工具，YAGNI）。
> 高風險先做：(1) mock provider 能否真的驅動系統定位是地基，排最前；(2) 裸 overlay 能否蓋在別的 app 上獨立驗證，緊接其後。這兩關過不了後面都沒意義。
> **手動驗證框架（mock app 類別固有，無法純自動化）**：每個涉及定位的切片這樣驗——(a) 設定→開發者選項→「選擇模擬位置應用程式」選本 app；(b) 開 Google Maps 或本 app 地圖切片，觀察藍點/標記是否等於本 app 設定的座標。此流程在下列切片重複套用。

- [ ] Slice 1: 專案骨架能跑 — Gradle app 模組、空 MainActivity、min/target SDK 設定。acceptance：`./gradlew assembleDebug` 成功，裝到裝置/模擬器開得起來顯示空畫面。

- [ ] Slice 2: 共用 PermissionChecker（提前抽出，避免後續重複）— 一個檢查「定位權限 / 是否被設為 mock app / overlay 權限」三態並提供跳轉 intent 的工具類。acceptance：呼叫可正確回報三項權限現況，缺項時能跳到對應系統設定頁。（其他切片一律複用它，不各自重寫）

- [ ] Slice 3: foreground service + mock 注入（地基／最高風險）(depends on: 1, 2) — foreground service 內 addTestProvider + 定時 setTestProviderLocation，帶常駐通知；Activity 放經緯度輸入框 + 開始/停止按鈕發指令給 service。acceptance：依手動驗證框架，按開始後 Google Maps 藍點跳到輸入座標；**切到其他 app 藍點維持**；**從最近任務清單滑掉 app 後 service 由通知重啟仍能續注入**（驗證背景存活/process kill）；未設 mock app／無定位權限時顯示明確提示。

- [ ] Slice 4: speed model + 方向移動引擎（建在 service 內，不搬家）(depends on: 3) — 抽出共用 speed model（m/s），與「依方向向量 + 速度每 tick 更新座標」的移動引擎，置於 service；Activity 放四方向按鈕 + 速度滑桿測試。acceptance：按方向鈕藍點沿該方向連續平滑移動，調滑桿速率即時改變。

- [ ] Slice 5: 裸 overlay 跨 app 驗證（隔離最高 overlay 風險）(depends on: 2) — 只做一個 SYSTEM_ALERT_WINDOW 空白可拖曳懸浮 view + overlay 權限引導（複用 PermissionChecker）。acceptance：授權後切到任意其他 app，懸浮 view 仍可見、可拖曳移動位置。（可與 3/4 並行）

- [ ] Slice 6: 懸浮搖桿邏輯 (depends on: 4, 5) — 把裸 overlay 換成虛擬搖桿 view，方向與力度接到 speed model／移動引擎。acceptance：切到其他 app 上，拖動搖桿使藍點依方向與力度（速度）連續移動，放開回中停止。

- [ ] Slice 7: OSMDroid 地圖 + 目前位置標記 + 點擊瞬移 (depends on: 3, 4) — 地圖訂閱 service 的位置 StateFlow 顯示標記；點地圖任一點發「瞬移」指令給 service。acceptance：標記等於 service 目前 mock 座標；點地圖某處，標記與外部 Maps 藍點都跳到該點。（可與 5/6 並行）

- [ ] Slice 8: GPX 匯入 + 解析 + 畫線 (depends on: 7) — SAF 選 .gpx，XmlPullParser 解析 trk/rte/wpt 座標點，地圖畫 polyline。acceptance：選 GPX 檔畫出對應軌跡；空檔／無座標點顯示錯誤提示不崩潰。

- [ ] Slice 9: GPX 播放核心：點對點插值 + 播放/暫停/續播 (depends on: 4, 8) — 沿軌跡依 speed model 做點間時間插值移動（與方向引擎不同演算法，僅共用 speed model）；播放、暫停、續播。acceptance：按播放藍點沿線平滑移動；調速即時生效；暫停停住、續播從停住點接續。

- [ ] Slice 10: GPX 播放模式：loop／reverse + 從任意點開始 (depends on: 9) — 到終點依模式循環或折返；點軌跡某點從該點起播。acceptance：走到終點依所選模式 loop 或 reverse 繼續；點路線某點播放起點跳該點。

- [ ] Slice 11: 首次啟動引導 + 收尾 (depends on: 3, 6) — 用 PermissionChecker 串出首開引導（設 mock app、授 overlay 與定位），整體串接與空狀態/錯誤處理打磨。acceptance：全新安裝首開，依引導完成三項設定後可正常使用；缺任一權限時有明確提示與跳轉。
