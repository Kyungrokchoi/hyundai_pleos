package io.moata.pleos.core.vehicle

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Device info and the installed Pleos vehicle service, if any. */
class AndroidProbeEnv(private val context: Context) : ProbeEnv {
    override fun environment(): Map<String, String> {
        val pm = context.packageManager
        val m = linkedMapOf(
            "device.model" to Build.MODEL, "device.device" to Build.DEVICE, "device.fingerprint" to Build.FINGERPRINT,
            "android.sdk_int" to Build.VERSION.SDK_INT.toString(),
            "feature.automotive" to pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE).toString(),
            "app.sdk_declared" to PLEOS_SDK_VERSION,
        )
        // merged from the Vehicle AAR manifest: vehicle service current/min version names
        runCatching {
            val md = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            md?.keySet()?.filter { it.startsWith("ai.pleos.playground") }?.sorted()?.forEach { m["manifest.$it"] = md.get(it).toString() }
        }
        // whoever answers the AAR's <queries> action is the vehicle service
        runCatching {
            val svc = pm.queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0)
            m["vehicle_service.resolved"] = if (svc.isEmpty()) "NONE (no service answers ai.pleos.playground.vehicle.VEHICLE_START)"
            else svc.joinToString { it.serviceInfo.packageName + "/" + it.serviceInfo.name }
            svc.firstOrNull()?.let {
                val pi = pm.getPackageInfo(it.serviceInfo.packageName, 0)
                m["vehicle_service.version_name"] = pi.versionName.orEmpty()
                m["vehicle_service.version_code"] = pi.longVersionCode.toString()
            }
        }.onFailure { m["vehicle_service.error"] = "${it::class.java.name}: ${it.message}" }
        return m
    }

    override fun permissions(): Map<String, String> =
        listOf("pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_ENERGY").associateWith {
            if (context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
        }
}
