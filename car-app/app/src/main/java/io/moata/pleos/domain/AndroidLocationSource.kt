package io.moata.pleos.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow





class AndroidLocationSource(private val context: Context) : LocationSource {
    override fun updates(): Flow<TrackPoint> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = context.getSystemService(LocationManager::class.java)
        if (!granted || lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return emptyFlow()
        return callbackFlow {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { trySend(TrackPoint(location.latitude, location.longitude, location.time.takeIf { it > 0 } ?: System.currentTimeMillis())) }
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, TrackRules.MIN_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                close(); return@callbackFlow
            }
            awaitClose { lm.removeUpdates(listener) }
        }
    }
}
