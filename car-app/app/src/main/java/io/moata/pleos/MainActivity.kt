package io.moata.pleos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import io.moata.pleos.ui.AppViewModel
import io.moata.pleos.ui.MoataRoot

class MainActivity : ComponentActivity() {
    private val container get() = (application as MoataApp).container

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) container.tracker.onPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, AppViewModel.factory(container))[AppViewModel::class.java]
        setContent { MoataRoot(vm) }
        handleProbe(intent)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProbe(intent)
    }


    private fun handleProbe(i: Intent?) {
        if (BuildConfig.DEMO_TOOLS && i?.getBooleanExtra("moata.probe", false) == true) {
            container.runProbe(i.getIntExtra("moata.probe.listen_sec", 10))
        }
    }
}
