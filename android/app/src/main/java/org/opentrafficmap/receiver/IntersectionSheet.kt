package org.opentrafficmap.receiver

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * "Traffic light" detail sheet, opened by tapping the assistant card.
 * The lane-geometry section is always the placeholder — MAPEM lane/signal-group
 * parsing isn't implemented, so there's nothing truthful to show there yet.
 */
object IntersectionSheet {

    fun show(context: Context, info: AssistantInfo?) {
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_intersection, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.intersectionPhase).text =
            if (info == null) context.getString(R.string.intersection_no_spat)
            else {
                val phaseLabel = context.getString(when (info.phase) {
                    SpatTemParser.Phase.RED     -> R.string.log_phase_red
                    SpatTemParser.Phase.YELLOW  -> R.string.log_phase_yellow
                    SpatTemParser.Phase.GREEN   -> R.string.log_phase_green
                    SpatTemParser.Phase.UNKNOWN -> R.string.log_phase_unknown
                })
                val timing = info.remainingSeconds?.let { context.getString(R.string.assistant_rest, it.toInt()) }
                    ?: context.getString(R.string.assistant_no_timer)
                "$phaseLabel · $timing · ${Units.formatDistanceMeters(context, info.distanceMeters)}"
            }

        sheet.show()
    }
}
