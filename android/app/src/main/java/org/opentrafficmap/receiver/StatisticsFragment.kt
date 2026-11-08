package org.opentrafficmap.receiver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import org.opentrafficmap.receiver.databinding.FragmentStatisticsBinding
import org.opentrafficmap.receiver.databinding.ItemTypeRowBinding

/**
 * Packets/Rate/Active stat cards, a by-type breakdown, and a rate-history
 * sparkline (60s/1h/24h/7d). The real app's equivalent screen is a single
 * custom-drawn `StatsPanel` View (confirmed via decompile, logic not
 * visible without decompiling classes.dex) — this is a from-scratch
 * composed-widget implementation covering the same confirmed data/strings,
 * not a pixel match.
 */
class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var selectedRange = MainViewModel.RateRange.SEC

    private val typeRowBindings = mutableMapOf<ItsG5Decoder.MsgType, ItemTypeRowBinding>()
    private lateinit var adapter: FrameLogAdapter
    private var logSampleCounter = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildTypeRows()
        wireRangeTabs()
        setupFrameLog()
        observeViewModel()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll() // pick up whatever happened while this tab was hidden
    }

    override fun onDestroyView() {
        super.onDestroyView()
        typeRowBindings.clear()
        _binding = null
    }

    private fun buildTypeRows() {
        val inflater = LayoutInflater.from(requireContext())
        for (type in ItsG5Decoder.MsgType.entries) {
            val row = ItemTypeRowBinding.inflate(inflater, binding.typeRows, true)
            row.typeLabel.text = type.short
            row.typeLabel.setTextColor(type.color)
            typeRowBindings[type] = row
        }
    }

    private fun wireRangeTabs() {
        val tabs = mapOf(
            binding.tabSec  to MainViewModel.RateRange.SEC,
            binding.tabHour to MainViewModel.RateRange.HOUR,
            binding.tabDay  to MainViewModel.RateRange.DAY,
            binding.tabWeek to MainViewModel.RateRange.WEEK,
        )
        tabs.forEach { (view, range) ->
            view.setOnClickListener {
                selectedRange = range
                applyTabSelectionStyle(tabs)
                refreshRateChart()
            }
        }
        applyTabSelectionStyle(tabs)
    }

    private fun applyTabSelectionStyle(tabs: Map<TextView, MainViewModel.RateRange>) {
        tabs.forEach { (view, range) ->
            val selected = range == selectedRange
            view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            view.alpha = if (selected) 1f else 0.6f
        }
    }

    private fun observeViewModel() {
        // logStats already ticks at 1Hz — reuse that cadence for everything on this screen
        // instead of a second independent timer.
        viewModel.logStats.observe(viewLifecycleOwner) { refreshAll() }
        viewModel.newFrames.observe(viewLifecycleOwner) { frames -> handleNewFrames(frames) }
        viewModel.lastLocation.observe(viewLifecycleOwner) { adapter.userLocation = it }
    }

    private fun setupFrameLog() {
        adapter = FrameLogAdapter(requireContext()) { f -> FrameDetailSheet.show(requireContext(), f) }
        binding.log.layoutManager = LinearLayoutManager(requireContext())
        binding.log.adapter = adapter
    }

    private fun handleNewFrames(frames: List<Frame>) {
        // Mirrors MapFragment's High Rate Mode sampling: a RecyclerView row per frame
        // doesn't scale at >=1000 pkt/s, so only 1 in 10 frames reaches the log.
        val forLog = if (viewModel.highRateMode) frames.filter { logSampleCounter++ % 10 == 0 } else frames
        if (forLog.isNotEmpty()) adapter.addFrames(forLog)
        if (binding.emptyLog.visibility != View.GONE) binding.emptyLog.visibility = View.GONE
    }

    private fun refreshAll() {
        if (_binding == null) return
        val (total, rate) = viewModel.logStats.value ?: (0 to 0)
        binding.cardPackets.statValue.text = total.toString()
        binding.cardPackets.statLabel.text = getString(R.string.stats_packets)
        binding.cardRate.statValue.text = rate.toString()
        binding.cardRate.statLabel.text = getString(R.string.stats_rate)
        binding.cardActive.statValue.text = viewModel.activeStationCount().toString()
        binding.cardActive.statLabel.text = getString(R.string.stats_active)

        val counts = viewModel.typeCountsSnapshot()
        val max = (counts.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        typeRowBindings.forEach { (type, row) ->
            val count = counts[type] ?: 0L
            row.typeCount.text = count.toString()
            row.typeBar.pivotX = 0f
            row.typeBar.scaleX = (count.toFloat() / max).coerceIn(0.01f, 1f)
        }

        binding.rateNowLabel.text = getString(R.string.stats_now, rate)
        refreshRateChart()
    }

    private fun refreshRateChart() {
        if (_binding == null) return
        val history = viewModel.rateHistory(selectedRange)
        binding.rateChart.setData(history)
        val spanSeconds = selectedRange.bucketCount * (selectedRange.bucketMs / 1000)
        binding.rateSpanLabel.text = "-${formatSpan(spanSeconds)}"
    }

    private fun formatSpan(totalSeconds: Long): String = when {
        totalSeconds < 60 -> "${totalSeconds}s"
        totalSeconds < 3600 -> "${totalSeconds / 60}m"
        totalSeconds < 86400 -> "${totalSeconds / 3600}h"
        else -> "${totalSeconds / 86400}d"
    }

    companion object {
        const val TAG = "statistics"
    }
}
