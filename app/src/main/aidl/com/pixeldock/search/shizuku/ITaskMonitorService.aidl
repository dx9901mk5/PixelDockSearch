package com.pixeldock.search.shizuku;

interface ITaskMonitorService {
    String getForegroundPackage();
    boolean grantUsageStats(String packageName);
    void destroy();
}
