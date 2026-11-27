# PixelDock Search

[繁體中文](#繁體中文說明) | [English](#english)

---

<a id="繁體中文說明"></a>
## 🇹🇼 繁體中文說明

> **免 Root、不干涉原廠啟動器，於系統桌面下方常駐 Pixel 風格 Google 搜尋列，並提供預熱 Google Feed 卡片與原廠負一屏即時滑出動畫——專為手機、摺疊機與平板打造 6 組獨立記憶定位。**

### ✨ 核心特色（v1.1.0）

1. **極簡 App 設定介面 ＋ 桌面直覺拖曳與縮放比例**
   - App 內僅保留權限、偵測模式與「進入桌面編輯位置與縮放比例」入口，無須在設定頁反覆微調數字。
   - 進入桌面編輯模式後，可直接**拖曳移動位置**（靠近水平中心 $\pm 8\text{dp}$ 自動磁吸置中），並支援**左右邊緣手柄拖曳調寬**、**雙指捏合縮放**以及編輯列上的 **`－` / `＋` / `置中` / `重設` / `完成鎖定`**。
2. **跟隨啟動器上滑進入 App 清單同步消失**
   - 搭配無障礙即時滾動監聽與速度追蹤，當您在桌面往上滑動開啟 App 抽屜時，搜尋列會**跟著啟動器的挪動速度同步向上滑出消失**，返回桌面時自動恢復。
3. **預熱 Google Feed ＋ 原廠負一屏滑出動畫**
   - 服務啟動後會在背景預熱載入 Google 頁面（`WebView` 常駐待機）。
   - **從桌面左側邊緣右滑**：頁面早已載入完畢，手指拖曳即可 1:1 順暢拉出 Google 探索卡片。
   - **滑出原廠負一屏（vivo HiBoard / ColorOS / MIUI / Samsung Free 等）**：偵測到原廠負一屏時，立即以原生卡片滑出動畫從左側滑出已預熱的 Google Feed 覆蓋顯示，左滑或按返回鍵即可收起並回到主桌面。
4. **手機／摺疊／平板 6 組獨立記憶定位**
   - 自動區分手機／摺疊外螢幕、摺疊展開、平板的直／橫向共 6 種型態並各自記憶座標與比例。
5. **Shizuku 優先 + 多引擎前景偵測**
   - 透過 **Shizuku UserService** 即時讀取前景套件與系統遮罩狀態（開啟 App、通知欄、鎖定畫面或進入最近任務時自動隱藏），支援 `UsageStats` 備援與「無障礙即時偵測」。
6. **內建 GitHub 自動更新與固定簽章覆蓋安裝**
   - App 內建版本檢查，啟動或手動點擊檢查時若發現 GitHub 新版本即彈窗提示，支援**進度條自動下載**並直接呼叫系統安裝程式覆蓋升級（採固定簽章金鑰，無須移除重裝）。

### 📦 下載與安裝步驟

1. 前往 [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) 下載最新 **`PixelDockSearch-v*.apk`** 並安裝（需 Android 8.0 / API 26 以上）。
2. 開啟 **PixelDock 搜尋列**：
   - 授予「**懸浮窗權限**」與「**授權 Shizuku**」。
   - 若要體驗「上滑跟隨 App 清單同步消失」與「原廠負一屏即時切換預熱 Google Feed」，建議開啟「**無障礙設定**」並選擇無障礙即時偵測。
3. 點擊「**進入桌面編輯位置與縮放比例**」，在手機／展開／平板狀態下分別拖曳與縮放至理想位置即可。

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

> **A rootless, lightweight Android utility that pins a Pixel-style Google Search bar above your stock launcher's dock and provides a pre-warmed Google Feed card with native slide-out animation—with 6 independent position & scale profiles across phones, foldables, and tablets.**

### ✨ Key Features (v1.1.0)

1. **Minimalist App UI + Direct Desktop Drag & Proportional Scaling**
   - No cluttered sliders inside the app—just one button to **Edit Position & Scale on Home Screen**.
   - In desktop edit mode, drag to position (with $\pm 8\text{dp}$ magnetic center snap), drag the **left/right resize handles**, **pinch-to-zoom**, or tap **`－` / `＋` / `Center` / `Reset` / `Lock`** right on the floating bar.
2. **Velocity-Matched Upward Slide-Out with App Drawer**
   - Tracks launcher vertical scroll events and swipe velocity in real time so the search bar **slides upward and fades out in sync with your App Drawer gesture**, then returns smoothly on home.
3. **Pre-Warmed Google Feed + OEM Minus-One Screen Slide-Out Animation**
   - Pre-warms a live Google `WebView` in memory right after service start so content is **already loaded before you swipe**.
   - **Left-Edge Interactive Pull-Out**: Dragging from the left edge pulls the pre-loaded Google Feed card out 1:1 with your finger.
   - **OEM Minus-One Screen Takeover**: When swiping into your stock launcher's Minus-One screen (vivo HiBoard, ColorOS/Oplus, MIUI, Samsung Free, etc.), the pre-warmed Google Feed card slides out from the left with a native card animation; swipe left or press Back to return cleanly to your home screen.
4. **6-Slot Form-Factor & Posture Memory**
   - Independently stores position and scale across Phone/Cover, Foldable Unfolded, and Tablet in both Portrait & Landscape orientations.
5. **Shizuku-First + Multi-Engine Foreground Detection**
   - Uses a **Shizuku UserService** to hide the overlay in apps, notification shade, lock screen, and recents, with `UsageStats` fallback and Accessibility instant detection.
6. **Built-in GitHub Auto-Updater + Fixed Signing Key**
   - Automatically checks GitHub Releases for newer versions, prompts with release notes, **downloads the APK with a live progress bar**, and launches the system installer directly (signed with a fixed key for seamless overwrite upgrades).

### 📦 Download & Setup

1. Download the latest **`PixelDockSearch-v*.apk`** from [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) (requires Android 8.0 / API 26+).
2. Open **PixelDock Search**:
   - Grant **Display over other apps** and **Authorize Shizuku**.
   - Enable **Accessibility Service** if you want real-time App Drawer scroll-following and instant OEM Minus-One screen switching.
3. Tap **Edit Position & Scale on Home Screen** to drag and resize each posture directly on your desktop.

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
