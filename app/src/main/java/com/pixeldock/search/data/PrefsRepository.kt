package com.pixeldock.search.data

import android.content.Context
import android.content.SharedPreferences
import com.pixeldock.search.model.DisplayProfile

data class ProfileLayoutConfig(
    val bottomMarginDp: Int,
    val xOffsetDp: Int,
    val widthPercent: Int,
    val heightDp: Int
)

enum class DetectionMode(val key: String) {
    AUTO_SHIZUKU("auto_shizuku"),
    USAGE_STATS("usage_stats"),
    ACCESSIBILITY("accessibility");

    companion object {
        fun fromKey(key: String?): DetectionMode =
            entries.firstOrNull { it.key == key } ?: AUTO_SHIZUKU
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light"),
    DYNAMIC("dynamic");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class FeedTriggerMode(val key: String) {
    FIRST_HOME_PAGE("first_home_page"),
    MINUS_ONE_AUTO_REPLACE("minus_one_auto_replace"),
    MINUS_ONE_EDGE_LAUNCH("minus_one_edge_launch");

    companion object {
        fun fromKey(key: String?): FeedTriggerMode = when (key) {
            "minus_one_screen", MINUS_ONE_AUTO_REPLACE.key -> MINUS_ONE_AUTO_REPLACE
            MINUS_ONE_EDGE_LAUNCH.key -> MINUS_ONE_EDGE_LAUNCH
            else -> FIRST_HOME_PAGE
        }
    }
}

class PrefsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var detectionMode: DetectionMode
        get() = DetectionMode.fromKey(prefs.getString(KEY_DETECTION_MODE, DetectionMode.AUTO_SHIZUKU.key))
        set(value) = prefs.edit().putString(KEY_DETECTION_MODE, value.key).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.key))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.key).apply()

    var bgAlpha: Int
        get() = prefs.getInt(KEY_BG_ALPHA, 90).coerceIn(20, 100)
        set(value) = prefs.edit().putInt(KEY_BG_ALPHA, value.coerceIn(20, 100)).apply()

    var longPressDragEnabled: Boolean
        get() = prefs.getBoolean(KEY_LONG_PRESS_DRAG, true)
        set(value) = prefs.edit().putBoolean(KEY_LONG_PRESS_DRAG, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    var leftEdgeFeedEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEFT_EDGE_FEED, true)
        set(value) = prefs.edit().putBoolean(KEY_LEFT_EDGE_FEED, value).apply()

    var leftEdgeHeightPercent: Int
        get() = prefs.getInt(KEY_LEFT_EDGE_HEIGHT_PCT, 45).coerceIn(20, 80)
        set(value) = prefs.edit().putInt(KEY_LEFT_EDGE_HEIGHT_PCT, value.coerceIn(20, 80)).apply()

    var feedTriggerMode: FeedTriggerMode
        get() = FeedTriggerMode.fromKey(prefs.getString(KEY_FEED_TRIGGER_MODE, FeedTriggerMode.FIRST_HOME_PAGE.key))
        set(value) = prefs.edit().putString(KEY_FEED_TRIGGER_MODE, value.key).apply()

    var searchBarSwipeFeedEnabled: Boolean
        get() = prefs.getBoolean(KEY_BAR_SWIPE_FEED, true)
        set(value) = prefs.edit().putBoolean(KEY_BAR_SWIPE_FEED, value).apply()

    var feedLaunchNativeGoogleApp: Boolean
        get() = prefs.getBoolean(KEY_FEED_NATIVE_GOOGLE_APP, true)
        set(value) = prefs.edit().putBoolean(KEY_FEED_NATIVE_GOOGLE_APP, value).apply()

    var accessibilityKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_KEEP_ALIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCESSIBILITY_KEEP_ALIVE, value).apply()

    var customMinusOnePackage: String
        get() = prefs.getString(KEY_CUSTOM_MINUS_ONE_PKG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_MINUS_ONE_PKG, value).apply()

    var customMinusOneSignature: String
        get() = prefs.getString(KEY_CUSTOM_MINUS_ONE_SIG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_MINUS_ONE_SIG, value).apply()

    var customFirstPageSignature: String
        get() = prefs.getString(KEY_CUSTOM_FIRST_PAGE_SIG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_FIRST_PAGE_SIG, value).apply()

    var extraLauncherPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXTRA_LAUNCHERS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_EXTRA_LAUNCHERS, value).apply()

    fun getLayout(profile: DisplayProfile): ProfileLayoutConfig {
        val prefix = profilePrefix(profile)
        return ProfileLayoutConfig(
            bottomMarginDp = prefs.getInt("${prefix}bottom_dp", profile.defaultBottomMarginDp)
                .coerceIn(0, 400),
            xOffsetDp = prefs.getInt("${prefix}x_offset_dp", profile.defaultXOffsetDp)
                .coerceIn(-400, 400),
            widthPercent = prefs.getInt("${prefix}width_pct", profile.defaultWidthPercent)
                .coerceIn(25, 98),
            heightDp = prefs.getInt("${prefix}height_dp", profile.defaultHeightDp)
                .coerceIn(36, 68)
        )
    }

    fun saveLayout(profile: DisplayProfile, config: ProfileLayoutConfig) {
        val prefix = profilePrefix(profile)
        prefs.edit()
            .putInt("${prefix}bottom_dp", config.bottomMarginDp.coerceIn(0, 400))
            .putInt("${prefix}x_offset_dp", config.xOffsetDp.coerceIn(-400, 400))
            .putInt("${prefix}width_pct", config.widthPercent.coerceIn(25, 98))
            .putInt("${prefix}height_dp", config.heightDp.coerceIn(36, 68))
            .putBoolean("${prefix}customized", true)
            .apply()
    }

    fun resetLayout(profile: DisplayProfile) {
        val prefix = profilePrefix(profile)
        prefs.edit()
            .remove("${prefix}bottom_dp")
            .remove("${prefix}x_offset_dp")
            .remove("${prefix}width_pct")
            .remove("${prefix}height_dp")
            .remove("${prefix}customized")
            .apply()
    }

    fun hasCustomLayout(profile: DisplayProfile): Boolean =
        prefs.getBoolean("${profilePrefix(profile)}customized", false)

    private fun profilePrefix(profile: DisplayProfile): String = "profile_${profile.key}_"

    companion object {
        private const val PREFS_NAME = "pixel_dock_search_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_DETECTION_MODE = "detection_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BG_ALPHA = "bg_alpha"
        private const val KEY_LONG_PRESS_DRAG = "long_press_drag"
        private const val KEY_AUTO_START_BOOT = "auto_start_boot"
        private const val KEY_EXTRA_LAUNCHERS = "extra_launchers"
        private const val KEY_LEFT_EDGE_FEED = "left_edge_feed_enabled"
        private const val KEY_LEFT_EDGE_HEIGHT_PCT = "left_edge_height_pct"
        private const val KEY_FEED_TRIGGER_MODE = "feed_trigger_mode"
        private const val KEY_BAR_SWIPE_FEED = "search_bar_swipe_feed"
        private const val KEY_FEED_NATIVE_GOOGLE_APP = "feed_native_google_app"
        private const val KEY_ACCESSIBILITY_KEEP_ALIVE = "accessibility_keep_alive"
        private const val KEY_CUSTOM_MINUS_ONE_PKG = "custom_minus_one_pkg"
        private const val KEY_CUSTOM_MINUS_ONE_SIG = "custom_minus_one_sig"
        private const val KEY_CUSTOM_FIRST_PAGE_SIG = "custom_first_page_sig"
    }
}
