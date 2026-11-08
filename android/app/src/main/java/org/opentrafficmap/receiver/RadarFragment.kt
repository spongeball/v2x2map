package org.opentrafficmap.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.opentrafficmap.receiver.databinding.FragmentRadarBinding

/**
 * Nearby stations by bearing+distance. The real app's equivalent
 * (`view_radar.xml`) wraps a single custom-drawn `RadarView` class — only
 * the range-selector pill and "no contacts" text are plain views there
 * (confirmed via decompile), which this mirrors; the scope itself is our
 * own `RadarView`, not a pixel match of the real one.
 */
class RadarFragment : Fragment() {

    private var _binding: FragmentRadarBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var selectedRange: Float? = null // null = Auto

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRadarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = mapOf(
            binding.radarRangeAuto to null,
            binding.radarRange250  to 250f,
            binding.radarRange500  to 500f,
            binding.radarRange1000 to 1000f,
        )
        tabs.forEach { (tab, meters) ->
            tab.setOnClickListener {
                selectedRange = meters
                binding.radarView.setRange(meters)
                applyTabSelectionStyle(tabs)
            }
        }
        applyTabSelectionStyle(tabs)
        binding.radarView.setRange(selectedRange)

        viewModel.radarContacts.observe(viewLifecycleOwner) { contacts ->
            binding.radarView.setContacts(contacts)
            binding.radarStatus.text = if (contacts.isEmpty()) getString(R.string.radar_status_empty)
                                        else getString(R.string.radar_status, contacts.size)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyTabSelectionStyle(tabs: Map<TextView, Float?>) {
        tabs.forEach { (tab, meters) ->
            val selected = meters == selectedRange
            tab.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tab.alpha = if (selected) 1f else 0.6f
        }
    }

    companion object {
        const val TAG = "radar"
    }
}
