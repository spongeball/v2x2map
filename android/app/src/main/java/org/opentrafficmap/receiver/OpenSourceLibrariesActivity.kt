package org.opentrafficmap.receiver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.opentrafficmap.receiver.databinding.ActivityOpenSourceLibrariesBinding

class OpenSourceLibrariesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityOpenSourceLibrariesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
