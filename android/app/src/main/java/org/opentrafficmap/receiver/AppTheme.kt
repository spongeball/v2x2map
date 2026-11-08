package org.opentrafficmap.receiver

import android.app.Activity
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * Applies the user's theme-color preference. Must be called before
 * setContentView() in every Activity. DynamicColors bakes its seed into
 * the Activity instance it's applied to, so there's no live "re-seed" call —
 * changing the preference takes effect on the next recreate()/relaunch,
 * same as the existing dark-mode toggle.
 */
object AppTheme {
    fun applyTo(activity: Activity) {
        when (Prefs.themeMode(activity)) {
            Prefs.ThemeMode.DEFAULT -> return
            Prefs.ThemeMode.WALLPAPER -> DynamicColors.applyToActivityIfAvailable(activity)
            Prefs.ThemeMode.CUSTOM -> DynamicColors.applyToActivityIfAvailable(
                activity,
                DynamicColorsOptions.Builder().setContentBasedSource(Prefs.themeColor(activity)).build()
            )
        }
    }
}
