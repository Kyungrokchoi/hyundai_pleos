package io.moata.pleos

import android.app.Application
import android.util.Log
import androidx.room.Room
import io.moata.pleos.core.vehicle.AndroidProbeEnv
import io.moata.pleos.core.vehicle.DemoVehicleDataSource
import io.moata.pleos.core.vehicle.ManualVehicleDataSource
import io.moata.pleos.core.vehicle.PleosProbe
import io.moata.pleos.core.vehicle.PleosVehicleDataSource
import io.moata.pleos.core.vehicle.ProbeReport
import io.moata.pleos.core.vehicle.RealPleosApis
import io.moata.pleos.core.vehicle.VehicleDataSource
import io.moata.pleos.data.local.MoataDatabase
import io.moata.pleos.data.local.Session
import io.moata.pleos.data.remote.ApiClient
import io.moata.pleos.data.remote.DrainReport
import io.moata.pleos.data.remote.OsmTileSource
import io.moata.pleos.data.remote.OutboxProcessor
import io.moata.pleos.domain.AndroidLocationSource
import io.moata.pleos.domain.AutoTollLedger
import io.moata.pleos.domain.CarUxStateSource
import io.moata.pleos.domain.DrivingState
import io.moata.pleos.domain.TileSource
import io.moata.pleos.domain.TrackStore
import io.moata.pleos.domain.TripRecorder
import io.moata.pleos.domain.TripRepository
import io.moata.pleos.domain.TripTracker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MoataApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Room.databaseBuilder(app, MoataDatabase::class.java, "moata.db").build()
    val session = Session(app)
    val api = ApiClient(BuildConfig.API_BASE_URL, { session.token })
    val outbox = OutboxProcessor(db.outbox(), api)
    val repo = TripRepository(db.trips(), db.outbox())
    val recorder = TripRecorder(repo, scope)
    val tracker = TripTracker(TrackStore(File(app.filesDir, "tracks")), scope, AndroidLocationSource(app))
    val tollLedger = AutoTollLedger(File(app.filesDir, "tolls"))
    val tiles: TileSource = OsmTileSource(ApiClient.defaultClient(), File(app.cacheDir, "osm-tiles"))
    val ux = CarUxStateSource(app)


    val pleos: PleosVehicleDataSource by lazy { PleosVehicleDataSource(RealPleosApis(app)) }
    val demo = DemoVehicleDataSource()
    val manual = ManualVehicleDataSource()
    fun source(mode: String): VehicleDataSource = when (mode) { "DEMO" -> demo; "MANUAL" -> manual; else -> pleos }


    val uxOverride = MutableStateFlow<DrivingState?>(null)
    val drivingState: StateFlow<DrivingState> =
        combine(ux.state, uxOverride) { real, o -> if (BuildConfig.DEMO_TOOLS) o ?: real else real }
            .stateIn(scope, SharingStarted.Eagerly, DrivingState.UNKNOWN)

    val lastDrain = MutableStateFlow<DrainReport?>(null)

    fun start() {
        ux.start()
        scope.launch {
            while (true) {
                if (session.signedIn) lastDrain.value = runCatching { outbox.drain() }.getOrNull()
                delay(5_000)
            }
        }
    }

    suspend fun flush(): DrainReport = outbox.drain().also { lastDrain.value = it }

    val probeResult = MutableStateFlow<ProbeReport?>(null)
    val probeRunning = MutableStateFlow(false)

    fun runProbe(listenSec: Int = 10) {
        if (!probeRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val probe = PleosProbe(RealPleosApis(app), AndroidProbeEnv(app), listenMs = listenSec * 1_000L, log = { logProbe(it) })
                val report = probe.run()
                val json = PleosProbe.encode(report)
                File(app.filesDir, "probe-result.json").writeText(json)
                logProbe("RESULT_FILE ${app.filesDir}/probe-result.json")
                json.chunked(3_000).forEachIndexed { i, c -> Log.i(PROBE_TAG, "JSON[$i] $c") }
                probeResult.value = report
            } catch (t: Throwable) {
                Log.e(PROBE_TAG, "PROBE_CRASH ${t::class.java.name}: ${t.message}", t)
            } finally {
                probeRunning.value = false
            }
        }
    }

    private fun logProbe(m: String) { Log.i(PROBE_TAG, m) }

    companion object { const val PROBE_TAG = "MOATA_PROBE" }
}
