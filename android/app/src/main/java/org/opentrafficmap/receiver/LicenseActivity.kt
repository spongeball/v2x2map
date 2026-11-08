package org.opentrafficmap.receiver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.opentrafficmap.receiver.databinding.ActivityLicenseBinding

class LicenseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
