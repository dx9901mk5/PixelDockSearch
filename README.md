# PixelDock Search

[繁體中文](#繁體中文說明) | [English](#english)

---

<a id="繁體中文說明"></a>
## 🇹🇼 繁體中文說明

> **免 Root、不干涉原廠啟動器，於系統桌面下方常駐 Pixel 風格 Google 搜尋列，並提供預熱 Google Feed 跟手卡片滑出動畫與原廠負一屏整合——專為手機、摺疊機與平板打造 6 組獨立記憶定位。**

### ✨ 核心特色（v1.2.0）

1. **跟手 Google Feed 卡片動畫（可半途止煞、嚴格過半才展開）**
   - 服務啟動即預先於背景完成 `WebView` 渲染預熱，滑出瞬間頁面早已載入完畢。
   - 模擬原生啟動器卡片滑出（右緣圓角、半透明過渡與背景暗幕），**1:1 跟隨手指滑動、可停在半路止煞**；**未超過螢幕 50% 放手即平滑歸位隱藏，過半才完全滑出**；展開後於畫面任意處向左滑動同樣支援跟手止煞與過半收起。
2. **雙重滑出方式 ＋ 桌面懸浮確認第一首頁與原廠負一屏**
   - **搜尋列本體右滑**：點擊搜尋列維持原 Google 搜尋功能，在搜尋列上向右拖曳即可跟手拉出 Google Feed（過半展開）。
   - **左側邊緣右滑**：加入 Android 10+ `systemGestureExclusionRects` 排除系統返回手勢干擾，確保左側滑出穩定有反應。
   - **桌面頂部懸浮確認頁**：一鍵回桌面即可於上方懸浮條即時標記哪頁是「第一首頁」、哪頁是「原廠負一屏」（**原廠負一屏將自動隱藏底部搜尋列**），並自由選擇三種行為：
     1. 從「第一首頁」左側滑出 Google Feed
     2. 進入「原廠負一屏」時直接覆蓋成 Google Feed
     3. 保留原廠負一屏，在負一屏左側再滑出 Google Feed
3. **不遮擋長按選單、不出現在 App 清單、進 App 瞬間消失**
   - 強化 OriginOS / ColorOS / HyperOS / OneUI 節點與視窗偵測：長按桌面圖示彈出快捷選單（「解除安裝／應用程式資訊／圖示樣式」等）時立即隱藏不遮擋；進入「全部應用程式 / 原子元件」App 清單時自動消失；點入任何 App 透過事件即時推播（<16ms）與高速輪詢瞬間隱藏。
4. **極簡設定頁 ＋ 桌面直覺拖曳與縮放比例**
   - App 內不塞繁瑣數值滑桿，點擊「進入桌面編輯位置與縮放比例」即可在桌面直接拖曳定位（靠近水平中心 $\pm 8\text{dp}$ 磁吸置中）、拉動左右手柄、雙指捏合縮放或點擊 `－ / ＋ / 置中 / 重設 / 完成鎖定`。
5. **手機／摺疊／平板 6 組獨立記憶定位**
   - 自動區分手機／摺疊外螢幕、摺疊展開、平板的直／橫向共 6 種型態並各自記憶座標與比例。
6. **內建 GitHub 自動更新與固定簽章覆蓋安裝**
   - 開啟 App 自動檢查 GitHub 最新 Release，有更新即彈窗提示，支援**進度條自動下載**並直接呼叫安裝程式覆蓋升級（採固定簽章金鑰，無須移除重裝）。

### 📦 下載與安裝步驟

1. 前往 [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) 下載最新 **`PixelDockSearch-v*.apk`** 並安裝（需 Android 8.0 / API 26 以上）。
2. 開啟 **PixelDock 搜尋列**：
   - 授予「**懸浮窗權限**」與「**授權 Shizuku**」。
   - 建議一併開啟「**無障礙設定**」以享有瞬間隱藏、長按選單避讓、App 清單自動隱藏與頁面特徵識別。
3. 點擊「**確認第一首頁與原廠負一屏（回桌面懸浮設定）**」與「**進入桌面編輯位置與縮放比例**」完成個人化配置。

### 🛠️ 自行建置（Build from Source）

- **環境需求**：JDK 17、Android SDK (`compileSdk 35`)。
- 若要在本地端產出可直接覆蓋的固定簽章版本，請於 `app/pixeldocksearch-signing.p12` 放置自己的 PKCS12 金鑰（本公開儲存庫不含私鑰；若無此檔案，Gradle 仍可正常編譯無簽章或預設 Debug 版本）：
  ```powershell
  .\gradlew.bat testDebugUnitTest assembleRelease
  ```

### 👨‍💻 關於我（About Me）

- **開發者**：**dx9901mk5**
- **GitHub 主頁**：https://github.com/dx9901mk5
- **聯絡信箱**：`dx9901mk5@gmail.com`
- 熱衷於開發免 Root、結合 **Shizuku** 與系統整合體驗的 Android 實用工具，致力於在不破壞原廠系統流暢度與手勢的前提下，補足摺疊機、平板與跨品牌 ROM 的操作便利性。
- **其他相關專案**：
  - [Android-CN-System-Enhancer](https://github.com/dx9901mk5/Android-CN-System-Enhancer) — Android 陸版系統四合一補強（來電音樂、相簿守護、語音轉 Gemini、環境隱身）
  - [FanRecentSwitcher](https://github.com/dx9901mk5/FanRecentSwitcher) — Android 單手扇形最近任務切換器 × 邊緣手勢 × 模擬 Dock
  - [DxVoice-IME](https://github.com/dx9901mk5/DxVoice-IME) — 離線 AI 智慧語音輸入法（自動切回原輸入法、60+ 離線語系）
  - [PowerKeyCamera](https://github.com/dx9901mk5/PowerKeyCamera) — 透過 Shizuku 雙擊電源鍵快啟鎖屏／安全相機
  - [DX-Calendar](https://github.com/dx9901mk5/DX-Calendar) — 全尺寸奢華金主題 Android 智慧日曆

---

<a id="english"></a>
## 🇺🇸 English

> **A rootless, lightweight Android utility that pins a Pixel-style Google Search bar above your stock launcher's dock and provides a pre-warmed, finger-following Google Feed card with OEM Minus-One screen integration—with 6 independent position & scale profiles across phones, foldables, and tablets.**

### ✨ Key Features (v1.2.0)

1. **Finger-Following Google Feed Card Animation (Halfway Hold + Strict 50% Snap)**
   - Pre-warms and renders Google in memory right after service startup so content is already loaded before you swipe.
   - Simulates a native launcher card slide-out (rounded right edge, translucent fade-in, dimmed scrim): **tracks your finger 1:1, holds cleanly at any halfway stop, glides back hidden if released before 50%, and completes opening only past 50%**. Swipe left anywhere on the open card to dismiss with the same physics.
2. **Dual Swipe Triggers + Floating Desktop Page Confirmation HUD**
   - **Swipe Right on the Search Bar**: Tapping keeps standard Google search, while dragging right directly across the search bar pulls out Google Feed 1:1 (strict 50% threshold).
   - **Fixed Left-Edge Swipe**: Uses Android 10+ `systemGestureExclusionRects` so system back gestures never swallow the pull-out strip.
   - **Desktop Top Confirmation HUD**: Jump straight to your home screen to mark your **First Home Page** and **OEM Minus-One Screen** (the bottom search bar automatically hides on your Minus-One screen) and choose between 3 behaviors:
     1. Left-edge pull from First Home Page
     2. Auto-replace OEM Minus-One screen with Google Feed
     3. Keep OEM Minus-One screen and launch Google Feed via left-edge swipe inside Minus-One
3. **No Popup Blocking, Hidden in App Drawer & Instant Hide on App Launch**
   - Detects OEM long-press app menus (Uninstall / App info / Icon style) and App Drawers (`All Apps` / `OriginOS 全部應用程式 / 原子元件`) so the search bar never obscures them; event-driven Accessibility push (<16ms) and fast polling hide the bar immediately when launching apps.
4. **Minimalist App UI + Direct Desktop Drag & Proportional Scaling**
   - Drag to position (magnetic center snap within $\pm 8\text{dp}$), drag left/right resize handles, pinch-to-zoom, or use `－ / ＋ / Center / Reset / Lock` directly on your home screen.
5. **6-Slot Form-Factor & Posture Memory**
   - Independently remembers layout coordinates and scale across Phone/Cover, Foldable Unfolded, and Tablet in Portrait & Landscape orientations.
6. **Built-in GitHub Auto-Updater + Fixed Signing Key**
   - Automatically checks GitHub Releases, prompts on new versions, downloads with a live progress bar, and installs directly over existing versions using the same fixed signing key.

### 📦 Download & Setup

1. Download the latest **`PixelDockSearch-v*.apk`** from [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) (requires Android 8.0 / API 26+).
2. Open **PixelDock Search**, grant **Display over other apps**, **Authorize Shizuku**, and enable **Accessibility Service** for instant app-launch hiding, popup avoidance, app drawer hiding, and page identification.
3. Tap **Confirm First Page & OEM Minus-One** and **Edit Position & Scale on Home Screen** to tailor it to your device.

### 🛠️ Build from Source

- **Requirements**: JDK 17, Android SDK (`compileSdk 35`).
- Place your own PKCS12 keystore at `app/pixeldocksearch-signing.p12` if building a fixed-signed release (private keystores are excluded from this repository; builds still succeed without it):
  ```powershell
  .\gradlew.bat testDebugUnitTest assembleRelease
  ```

### 👨‍💻 About Me

- **Developer**: **dx9901mk5**
- **GitHub Profile**: https://github.com/dx9901mk5
- **Email**: `dx9901mk5@gmail.com`
- Passionate about building rootless, **Shizuku-powered** Android utilities that enhance foldables, tablets, and OEM ROMs without sacrificing native system smoothness or gestures.
- **More Projects**:
  - [Android-CN-System-Enhancer](https://github.com/dx9901mk5/Android-CN-System-Enhancer) — 4-in-1 Chinese ROM Enhancer (VoIP Call Music, Photo Sync Guard, Voice-to-Gemini, Environment Stealth)
  - [FanRecentSwitcher](https://github.com/dx9901mk5/FanRecentSwitcher) — One-handed fan recent app switcher, edge gestures & simulated Dock
  - [DxVoice-IME](https://github.com/dx9901mk5/DxVoice-IME) — Offline AI voice keyboard with auto-return to previous IME
  - [PowerKeyCamera](https://github.com/dx9901mk5/PowerKeyCamera) — Double-press power key to launch secure lockscreen camera via Shizuku
  - [DX-Calendar](https://github.com/dx9901mk5/DX-Calendar) — Luxury gold Material Android smart calendar
