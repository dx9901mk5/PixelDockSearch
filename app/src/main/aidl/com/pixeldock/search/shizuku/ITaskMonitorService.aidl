package com.pixeldock.search.shizuku;

import com.pixeldock.search.shizuku.ITouchEventCallback;

interface ITaskMonitorService {
    String getForegroundPackage();
    boolean grantUsageStats(String packageName);
    boolean launchGoogleAppLens();
    boolean prewarmGoogleApp();
    boolean captureScreenToPath(String outputPath);
    byte[] captureScreenJpeg(int maxWidth, int quality);
    boolean isLauncherDrawerOrPopupVisible();
    void setTouchCallback(ITouchEventCallback callback);
    void destroy();
}
