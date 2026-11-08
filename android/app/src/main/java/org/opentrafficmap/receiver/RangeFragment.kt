package org.opentrafficmap.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.opentrafficmap.receiver.databinding.FragmentRangeBinding
import org.opentrafficmap.receiver.databinding.ItemRangeEntryBinding

/**
 * "Max Range Mode": tracks the furthest-received packet while a measurement
 * is running, plus a Top-5 leaderboard and CSV export. The real app's
 * equivalent (`view_range.xml`) is a single custom-drawn `RangeView` class,
 * not visible without decompiling classes.dex — this is a composed-widget
 * implementation of the same confirmed behavior/strings, not a pixel match.
 */
class RangeFragment : Fragment() {

    private var _binding: FragmentRangeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var defaultToggleTint: android.content.res.ColorStateList? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        defaultToggleTint = binding.btnRangeToggle.backgroundTintList
        binding.btnRangeToggle.setOnClickListener {
            if (viewModel.rangeRunning.value == true) viewModel.stopRangeMeasuring()
            else viewModel.startRangeMeasuring()
        }
        binding.btnRangeFilter.setOnClickListener {
            val ctx = requireContext()
            TypeFilterDialog.show(ctx, R.string.range_filter, Prefs.rangeFilterTypes(ctx)) { newFilter ->
                Prefs.setRangeFilterTypes(ctx, newFilter)
            }
        }
        binding.btnRangeExport.setOnClickListener { exportCsv() }

        viewModel.rangeRunning.observe(viewLifecycleOwner) { running ->
            binding.btnRangeToggle.text = getString(if (running) R.string.range_stop else R.string.range_start)
            binding.btnRangeToggle.backgroundTintList = if (running)
                android.content.res.ColorStateList.valueOf(StatusColors.RED) else defaultToggleTint
            updateStatus()
        }
        viewModel.rangeCount.observe(viewLifecycleOwner) { updateStatus() }
        viewModel.rangeTop5Live.observe(viewLifecycleOwner) { top5 -> renderTop5(top5) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateStatus() {
        val running = viewModel.rangeRunning.value == true
        val count = viewModel.rangeCount.value ?: 0
        binding.rangeStatus.text = if (running) getString(R.string.range_running, count)
                                    else getString(R.string.range_idle)
    }

    private fun renderTop5(top5: List<RangeEntry>) {
        val ctx = requireContext()
        val furthest = top5.firstOrNull()
        if (furthest == null) {
            binding.rangeFurthestValue.text = Units.formatDistanceMeters(ctx, 0f)
            binding.rangeFurthestDetail.text = ""
        } else {
            binding.rangeFurthestValue.text = Units.formatDistanceMeters(ctx, furthest.distanceMeters)
            binding.rangeFurthestDetail.text = getString(
                R.string.range_furthest, furthest.type.short, timeFmt.format(Date(furthest.timestampMs))
            )
        }

        binding.top5Rows.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        top5.forEachIndexed { i, entry ->
            val row = ItemRangeEntryBinding.inflate(inflater, binding.top5Rows, true)
            row.entryRank.text = (i + 1).toString()
            row.entryDistance.text = Units.formatDistanceMeters(ctx, entry.distanceMeters)
            row.entryType.text = entry.type.short
            row.entryType.setTextColor(entry.type.color)
            row.entryTime.text = timeFmt.format(Date(entry.timestampMs))
        }
    }

    private fun exportCsv() {
        val f = viewModel.exportRangeCsv()
        val msg = if (f != null) getString(R.string.range_export_done, f.name)
                  else getString(R.string.range_export_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val TAG = "range"
    }
}
