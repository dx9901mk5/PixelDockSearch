package com.pixeldock.search.shizuku;

oneway interface ITouchEventCallback {
    void onUpwardDrag(int dragUpPx);
    void onUpwardDragEnd(int dragUpPx, int velocityPxPerSec);
    void onRightSwipe(int dragRightPx, int startXPermille);
    void onRightSwipeEnd(int dragRightPx, int velocityPxPerSec);
}
