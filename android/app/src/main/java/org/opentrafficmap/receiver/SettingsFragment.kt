package org.opentrafficmap.receiver

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.opentrafficmap.receiver.databinding.FragmentSettingsBinding
import org.opentrafficmap.receiver.databinding.ItemSettingsRowBinding

/**
 * Bottom-nav landing page for Settings/About: one row into the real
 * [SettingsActivity], then "About" split into its own three rows — About,
 * License, and Open source libraries — each its own screen.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindRow(binding.rowSettings, R.drawable.ic_settings, R.string.settings_row_title, R.string.settings_row_subtitle) {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        bindRow(binding.rowAbout, R.drawable.ic_info, R.string.about_row_title, R.string.about_row_subtitle) {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
        bindRow(binding.rowLicense, R.drawable.ic_description, R.string.license_title, R.string.license_row_subtitle) {
            startActivity(Intent(requireContext(), LicenseActivity::class.java))
        }
        bindRow(binding.rowLibraries, R.drawable.ic_library, R.string.open_source_title, R.string.open_source_row_subtitle) {
            startActivity(Intent(requireContext(), OpenSourceLibrariesActivity::class.java))
        }
    }

    private fun bindRow(
        row: ItemSettingsRowBinding, iconRes: Int, titleRes: Int, subtitleRes: Int, onClick: () -> Unit,
    ) {
        row.rowIcon.setImageResource(iconRes)
        row.rowTitle.setText(titleRes)
        row.rowSubtitle.setText(subtitleRes)
        row.root.setOnClickListener { onClick() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "settings"
    }
}
