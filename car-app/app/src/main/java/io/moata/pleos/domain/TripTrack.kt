package io.moata.pleos.domain

import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

data class TrackPoint(val lat: Double, val lon: Double, val atEpochMs: Long)

object TrackRules {




    const val MIN_INTERVAL_MS = 2_000L
    const val MIN_DISTANCE_M = 30.0


    const val MAX_POINTS = 5_000

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: TrackPoint, b: TrackPoint): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val h = sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin(Math.toRadians(b.lon - a.lon) / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun valid(p: TrackPoint) = p.lat in -90.0..90.0 && p.lon in -180.0..180.0 && !(p.lat == 0.0 && p.lon == 0.0)

    fun shouldRecord(last: TrackPoint?, p: TrackPoint): Boolean =
        valid(p) && (last == null || (p.atEpochMs - last.atEpochMs >= MIN_INTERVAL_MS && distanceM(last, p) >= MIN_DISTANCE_M))


    fun thin(points: List<TrackPoint>, max: Int = MAX_POINTS): List<TrackPoint> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[(i * step).toInt().coerceAtMost(points.lastIndex)] }.let { it.dropLast(1) + points.last() }
    }
}


class TrackStore(private val dir: File) {
    private fun file(tripId: String): File {
        require(tripId.matches(SAFE_ID)) { "unsafe trip id" }
        return File(dir, "track-$tripId.csv")
    }

    fun append(tripId: String, p: TrackPoint) {
        dir.mkdirs()
        file(tripId).appendText("${p.lat},${p.lon},${p.atEpochMs}\n")
    }


    fun read(tripId: String): List<TrackPoint> {
        val f = file(tripId)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val c = line.split(',')
            if (c.size != 3) return@mapNotNull null
            val lat = c[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = c[1].toDoubleOrNull() ?: return@mapNotNull null
            val at = c[2].toLongOrNull() ?: return@mapNotNull null
            TrackPoint(lat, lon, at)
        }
    }

    fun clear(tripId: String) { file(tripId).delete() }

    private companion object { val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$") }
}

fun interface LocationSource {

    fun updates(): Flow<TrackPoint>

    companion object { val None = LocationSource { emptyFlow() } }
}


class TripTracker(private val store: TrackStore, private val scope: CoroutineScope, private val source: LocationSource) {
    private var job: Job? = null
    private var tripId: String? = null
    val running: Boolean get() = job?.isActive == true

    fun start(id: String) {
        tripId = id
        if (running) return
        var last = store.read(id).lastOrNull()
        job = scope.launch {
            source.updates().collect { p ->
                if (TrackRules.shouldRecord(last, p)) { store.append(id, p); last = p }
            }
        }
    }


    fun onPermissionGranted() { tripId?.let { start(it) } }

    fun stop() { job?.cancel(); job = null; tripId = null }

    fun points(id: String): List<TrackPoint> = store.read(id)

    fun clear(id: String) { if (tripId == id) stop(); store.clear(id) }
}
