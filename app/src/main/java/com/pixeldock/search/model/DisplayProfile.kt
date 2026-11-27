package com.pixeldock.search.model

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

enum class DisplayProfile(
    val key: String,
    val displayNameZh: String,
    val defaultBottomMarginDp: Int,
    val defaultXOffsetDp: Int,
    val defaultWidthPercent: Int,
    val defaultHeightDp: Int
) {
    COMPACT_PORTRAIT(
        key = "compact_portrait",
        displayNameZh = "手機／摺疊外螢幕（直向）",
        defaultBottomMarginDp = 112,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 86,
        defaultHeightDp = 48
    ),
    COMPACT_LANDSCAPE(
        key = "compact_landscape",
        displayNameZh = "手機／摺疊外螢幕（橫向）",
        defaultBottomMarginDp = 76,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 48,
        defaultHeightDp = 44
    ),
    EXPANDED_PORTRAIT(
        key = "expanded_portrait",
        displayNameZh = "摺疊展開／中螢幕（直向）",
        defaultBottomMarginDp = 116,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 56,
        defaultHeightDp = 48
    ),
    EXPANDED_LANDSCAPE(
        key = "expanded_landscape",
        displayNameZh = "摺疊展開／中螢幕（橫向）",
        defaultBottomMarginDp = 96,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 46,
        defaultHeightDp = 48
    ),
    TABLET_PORTRAIT(
        key = "tablet_portrait",
        displayNameZh = "平板大螢幕（直向）",
        defaultBottomMarginDp = 118,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 50,
        defaultHeightDp = 50
    ),
    TABLET_LANDSCAPE(
        key = "tablet_landscape",
        displayNameZh = "平板大螢幕（橫向）",
        defaultBottomMarginDp = 96,
        defaultXOffsetDp = 0,
        defaultWidthPercent = 40,
        defaultHeightDp = 48
    );

    data class DisplayMetricsSnapshot(
        val profile: DisplayProfile,
        val widthDp: Int,
        val heightDp: Int,
        val widthPx: Int,
        val heightPx: Int,
        val density: Float
    )

    companion object {
        fun resolve(widthDp: Int, heightDp: Int): DisplayProfile {
            val smallestWidthDp = min(widthDp, heightDp)
            val isLandscape = widthDp > heightDp
            return when {
                smallestWidthDp < 600 -> if (isLandscape) COMPACT_LANDSCAPE else COMPACT_PORTRAIT
                smallestWidthDp < 840 -> if (isLandscape) EXPANDED_LANDSCAPE else EXPANDED_PORTRAIT
                else -> if (isLandscape) TABLET_LANDSCAPE else TABLET_PORTRAIT
            }
        }

        fun fromContext(context: Context): DisplayMetricsSnapshot {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val widthPx: Int
            val heightPx: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                widthPx = bounds.width()
                heightPx = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                widthPx = metrics.widthPixels
                heightPx = metrics.heightPixels
            }

            val widthDp = (widthPx / density).roundToInt()
            val heightDp = (heightPx / density).roundToInt()
            return DisplayMetricsSnapshot(
                profile = resolve(widthDp, heightDp),
                widthDp = widthDp,
                heightDp = heightDp,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density
            )
        }
    }
}
