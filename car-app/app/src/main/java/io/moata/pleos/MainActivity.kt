package io.moata.pleos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import io.moata.pleos.ui.AppViewModel
import io.moata.pleos.ui.MoataRoot

class MainActivity : ComponentActivity() {
    private val container get() = (application as MoataApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, AppViewModel.factory(container))[AppViewModel::class.java]
        setContent { MoataRoot(vm) }
        handleProbe(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProbe(intent)
    }

    /** Debug builds only: adb shell am start -n io.moata.pleos/.MainActivity --ez moata.probe true [--ei moata.probe.listen_sec 20] */
    private fun handleProbe(i: Intent?) {
        if (BuildConfig.DEMO_TOOLS && i?.getBooleanExtra("moata.probe", false) == true) {
            container.runProbe(i.getIntExtra("moata.probe.listen_sec", 10))
        }
    }
}
