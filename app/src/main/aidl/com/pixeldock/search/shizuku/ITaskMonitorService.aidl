package com.pixeldock.search.shizuku;

interface ITaskMonitorService {
    String getForegroundPackage();
    boolean grantUsageStats(String packageName);
    boolean launchGoogleAppLens();
    void destroy();
}
