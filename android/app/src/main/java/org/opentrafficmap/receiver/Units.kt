package org.opentrafficmap.receiver

import android.content.Context
import android.os.Build

/**
 * Speed/distance formatting per [Prefs.unitsMode]. AUTO follows the device
 * region (imperial in the US and the two other non-metric countries).
 *
 * Replaces 4 independent "%.0f km/h"-style copies that used to live in
 * MainActivity, FrameLogAdapter, MarkerLayer and FrameDetailSheet.
 */
object Units {

    // US/Liberia/Myanmar never adopted metric; the UK uses metric generally
    // but still signs road speeds in mph — matches the in-app hint text
    // ("miles/mph in the US, UK, …") this pref's own copy promises.
    private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM", "GB")

    private fun isImperial(ctx: Context): Boolean = when (Prefs.unitsMode(ctx)) {
        Prefs.UnitsMode.METRIC   -> false
        Prefs.UnitsMode.IMPERIAL -> true
        Prefs.UnitsMode.AUTO     -> deviceCountry(ctx) in IMPERIAL_COUNTRIES
    }

    private fun deviceCountry(ctx: Context): String {
        val locales = ctx.resources.configuration.locales
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !locales.isEmpty)
            locales[0].country
        else
            @Suppress("DEPRECATION") ctx.resources.configuration.locale.country
    }

    fun formatSpeedMps(ctx: Context, speedMps: Double): String =
        if (isImperial(ctx)) "%.0f mph".format(speedMps * 2.236936)
        else "%.0f km/h".format(speedMps * 3.6)

    fun formatSpeedMps(ctx: Context, speedMps: Float): String = formatSpeedMps(ctx, speedMps.toDouble())

    fun formatDistanceMeters(ctx: Context, meters: Float): String =
        if (isImperial(ctx)) {
            val feet  = meters * 3.28084f
            val miles = meters / 1609.344f
            when {
                feet < 528f  -> "%.0f ft".format(feet)   // < 0.1 mi
                miles < 10f   -> "%.1f mi".format(miles)
                else           -> "%.0f mi".format(miles)
            }
        } else {
            when {
                meters < 1000f  -> "%.0f m".format(meters)
                meters < 10000f -> "%.1f km".format(meters / 1000f)
                else              -> "%.0f km".format(meters / 1000f)
            }
        }
}
