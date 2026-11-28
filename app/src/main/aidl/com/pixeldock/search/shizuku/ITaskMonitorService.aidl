package com.pixeldock.search.shizuku;

import com.pixeldock.search.shizuku.ITouchEventCallback;

interface ITaskMonitorService {
    String getForegroundPackage();
    boolean grantUsageStats(String packageName);
    boolean launchGoogleAppLens();
    boolean launchGeminiVoice();
    boolean prewarmGoogleApp();
    boolean captureScreenToPath(String outputPath);
    byte[] captureScreenJpeg(int maxWidth, int quality);
    boolean isLauncherDrawerOrPopupVisible();
    boolean ensureAccessibilityServiceEnabled(String componentName);
    void setTouchCallback(ITouchEventCallback callback);
    void destroy();
}
