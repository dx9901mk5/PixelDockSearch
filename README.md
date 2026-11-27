# PixelDock Search

[繁體中文](#繁體中文說明) | [English](#english)

---

<a id="繁體中文說明"></a>
## 🇹🇼 繁體中文說明

> **免 Root、不干涉原廠啟動器，於系統桌面下方常駐 Pixel 風格 Google 搜尋列，並支援桌面左側邊緣右滑開啟 Google 探索 (Feed)——專為手機、摺疊機與平板打造 6 組獨立記憶定位。**

### ✨ 核心特色

1. **不干涉原廠啟動器**
   - 保留原廠啟動器的多工手勢、過渡動畫與平板／摺疊機專屬 Dock，無須改用第三方啟動器即可享有 Pixel 桌面搜尋體驗。
   - 點擊搜尋膠囊本體開啟 Google 文字搜尋、麥克風開啟語音搜尋、右側圖示開啟 Google Lens。
2. **手機／摺疊／平板 6 組獨立記憶定位**
   - 依據螢幕尺寸與方向自動偵測切換，並各自記憶座標（底部距離 `Y`、左右偏移 `X`、寬度比例、高度）：
     - 手機／摺疊外螢幕（直向 / 橫向）
     - 摺疊展開／中螢幕（直向 / 橫向）
     - 平板大螢幕（直向 / 橫向）
   - 支援在桌面直接**拖曳定位**（靠近水平中心 $\pm 8\text{dp}$ 自動磁吸置中），也可於 App 內以滑桿精細微調。
3. **Shizuku 優先 + 多引擎前景偵測**
   - 透過 **Shizuku UserService** 即時讀取前景套件與系統遮罩狀態（開啟 App、下拉通知欄、鎖定畫面或進入最近任務時自動隱藏）。
   - 支援自動授權 `UsageStats` 備援，以及「無障礙即時偵測」零延遲切換模式。
4. **桌面左側邊緣右滑開啟 Google 探索 (Feed)**
   - 僅在偵測到目前位於原廠桌面時啟用左側邊緣透明觸發條，向右滑動即可震動回饋並開啟 Google 探索 (`googleapp://discover`)。
   - 進入任何 App 時會與搜尋列同步自動隱藏，完全不影響系統原本的側滑返回手勢。
5. **外觀與主題**
   - 支援 **跟隨系統 / 深色 / 淺色 / Material You 動態取色**（Android 12+）與背景不透明度調整。
6. **固定簽章發行（支援直接覆蓋升級）**
   - Release APK 採用固定簽章金鑰編譯，後續版本更新可直接覆蓋安裝，無須移除重裝或遺失定位設定。

### 📦 下載與安裝步驟

1. 前往 [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) 下載最新 **`PixelDockSearch-v*.apk`** 並安裝（需 Android 8.0 / API 26 以上）。
2. 開啟 **PixelDock 搜尋列**：
   - 點擊「**懸浮窗權限**」授予顯示在其他應用程式上層權限。
   - 點擊「**授權 Shizuku**」（請先確認 Shizuku App 已啟動並完成無線除錯配對）。
   - 開啟「**啟用桌面 Google 搜尋列**」。
3. **分開校準各螢幕型態位置**：
   - 在手機／摺疊合起狀態下，點擊「**前往桌面拖曳定位**」，將搜尋列拖至頁碼指示點與 Dock 之間，點擊「**完成鎖定**」。
   - 展開摺疊機、旋轉橫向或切換至平板後，再進行一次拖曳定位（或長按搜尋列進入拖曳），App 會自動切換並獨立記憶該組座標。

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

> **A rootless, lightweight Android utility that pins a Pixel-style Google Search bar above your stock launcher's dock and adds a left-edge swipe gesture to open Google Discover (Feed)—with 6 independent position/size profiles across phones, foldables, and tablets.**

### ✨ Key Features

1. **Zero Interference with Stock Launchers**
   - Keep your OEM launcher's native animations, gesture navigation, and tablet/foldable multi-icon Dock—no third-party launcher replacement required.
   - Tap the pill for Google text search, the mic icon for voice search, or the Lens icon for Google Lens.
2. **6-Slot Form-Factor & Posture Memory**
   - Automatically detects display dimensions and orientation, storing independent layouts (`bottomMarginDp`, `xOffsetDp`, `widthPercent`, `heightDp`) for:
     - Phone / Foldable Cover Screen (Portrait & Landscape)
     - Foldable Unfolded / Medium Screen (Portrait & Landscape)
     - Tablet Large Screen (Portrait & Landscape)
   - Drag directly on the home screen (with horizontal center magnetic snapping within $\pm 8\text{dp}$) or fine-tune with sliders in the app.
3. **Shizuku-First + Multi-Engine Foreground Detection**
   - Uses a **Shizuku UserService** to detect the foreground package and system overlays (automatically hiding the bar when inside apps, notification shade, lock screen, or recents).
   - Includes automatic `UsageStats` fallback and an optional Accessibility Service mode for zero-latency switching.
4. **Left-Edge Swipe for Google Discover (Feed)**
   - Renders a subtle edge trigger strip **only while on the stock home screen**; swipe right to trigger haptic feedback and open Google Discover (`googleapp://discover`).
   - Automatically hides inside all apps so it never interferes with system back-swipe gestures.
5. **Theming & Appearance**
   - Supports **System / Dark / Light / Material You Dynamic Color** (Android 12+) and adjustable background opacity.
6. **Fixed Cryptographic Signing**
   - Official APK releases are signed with a **fixed signing key**, allowing future updates to install directly over previous versions without uninstalling or losing saved positions.

### 📦 Download & Setup

1. Download the latest **`PixelDockSearch-v*.apk`** from [**Releases**](https://github.com/dx9901mk5/PixelDockSearch/releases/latest) (requires Android 8.0 / API 26+).
2. Open **PixelDock Search**:
   - Grant **Display over other apps** permission.
   - Tap **Authorize Shizuku** (ensure Shizuku is running).
   - Enable **Home Screen Google Search Bar**.
3. **Calibrate Each Posture Separately**:
   - While folded / on phone, tap **Go to Home & Drag to Position**, place the bar between your page indicator dots and dock, then tap **Lock Position**.
   - Unfold your device or rotate to landscape/tablet and calibrate again—each posture saves its own coordinates independently.

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
