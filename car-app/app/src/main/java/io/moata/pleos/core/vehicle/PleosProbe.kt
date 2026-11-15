package io.moata.pleos.core.vehicle

import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json





@Serializable
data class ProbeStep(
    val id: String,
    val api: String,
    val documentedUnit: String? = null,
    val outcome: String,
    val value: String? = null,
    val valueIsNull: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0,
)

@Serializable
data class ProbeListenerResult(
    val registered: Boolean,
    val registerError: String? = null,
    val updates: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val polledGetter: List<String> = emptyList(),
    val windowMs: Long = 0,
    val unregistered: Boolean = false,
)

@Serializable
data class ProbeReport(
    val startedAt: String,
    val finishedAt: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val permissions: Map<String, String> = emptyMap(),
    val initialize: ProbeStep? = null,
    val steps: List<ProbeStep> = emptyList(),
    val listener: ProbeListenerResult? = null,
    val release: ProbeStep? = null,

    val order: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val verdict: String = "NOT_RUN",
)

interface ProbeEnv {
    fun environment(): Map<String, String>
    fun permissions(): Map<String, String>
}

class PleosProbe(
    private val apis: PleosApis,
    private val env: ProbeEnv,
    private val timeoutMs: Long = 5_000,
    private val listenMs: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val order = CopyOnWriteArrayList<String>()
    private val t0 = now()
    private fun mark(e: String) { order += "${now() - t0}ms $e"; log("EVENT $e") }

    private suspend fun <T> step(id: String, api: String, unit: String?, call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): ProbeStep {
        mark("$id:call")
        val s = now()
        val o = awaitCallback<T>(timeoutMs) { ok, fail -> call(ok, fail) }
        val elapsed = now() - s
        mark("$id:callback(${o.javaClass.simpleName})")
        val r = when (o) {
            is CallbackOutcome.Complete -> ProbeStep(id, api, unit, "COMPLETE", o.value?.toString(), o.value == null, null, elapsed)
            is CallbackOutcome.Failed -> ProbeStep(id, api, unit, "FAILED", error = "${o.error::class.java.name}: ${o.error.message}", elapsedMs = elapsed)
            is CallbackOutcome.Timeout -> ProbeStep(id, api, unit, "TIMEOUT", error = o.describe(), elapsedMs = elapsed)
            is CallbackOutcome.Threw -> ProbeStep(id, api, unit, "THREW", error = "${o.error::class.java.name}: ${o.error.message}", elapsedMs = elapsed)
        }
        log("STEP ${r.id} ${r.outcome} value=${r.value} null=${r.valueIsNull} err=${r.error} ${r.elapsedMs}ms")
        return r
    }

    suspend fun run(): ProbeReport {
        val started = Instant.ofEpochMilli(now()).toString()
        log("MOATA_PROBE_BEGIN $started")
        var init: ProbeStep? = null
        val steps = mutableListOf<ProbeStep>()
        var listener: ProbeListenerResult? = null
        var release: ProbeStep? = null
        try {

            mark("initialize:call")
            val is0 = now()
            init = try {
                apis.initialize()
                ProbeStep("initialize", "Vehicle.initialize()", outcome = "COMPLETE", elapsedMs = now() - is0)
            } catch (t: Throwable) {
                ProbeStep("initialize", "Vehicle.initialize()", outcome = "THREW", error = "${t::class.java.name}: ${t.message}", elapsedMs = now() - is0)
            }
            mark("initialize:callback(${init.outcome})")
            log("STEP initialize ${init.outcome} err=${init.error}")

            if (init.outcome == "COMPLETE") {
                fun cap(id: String, api: String, f: (ok: (CapabilityConfig) -> Unit, fail: (Exception) -> Unit) -> Unit) =
                    suspend { step<CapabilityConfig>(id, api, null) { ok, fail -> f(ok, fail) } }
                val plan = listOf(
                    cap("cap.odometer.current", "Odometer.checkCurrentDistanceDrivenCapabilities") { a, b -> apis.odometer.checkCurrentDistanceDrivenCapabilities(a, b) },
                    cap("cap.fuel.current", "Fuel.checkCurrentFuelEconomyCapabilities") { a, b -> apis.fuel.checkCurrentFuelEconomyCapabilities(a, b) },
                    cap("cap.fuel.cumulative", "Fuel.checkCumulativeFuelEconomyCapabilities") { a, b -> apis.fuel.checkCumulativeFuelEconomyCapabilities(a, b) },
                    cap("cap.ev.efficiency.current", "EvBattery.checkCurrentEvEfficiencyCapabilities") { a, b -> apis.evBattery.checkCurrentEvEfficiencyCapabilities(a, b) },
                    cap("cap.ev.soc", "EvBattery.checkEvBatteryStateOfChargeCapabilities") { a, b -> apis.evBattery.checkEvBatteryStateOfChargeCapabilities(a, b) },
                )
                plan.forEach { steps += it() }


                steps += step<Float?>("get.odometer.current", "Odometer.getCurrentDistanceDriven", "km") { a, b -> apis.odometer.getCurrentDistanceDriven(a, b) }
                steps += step<Float?>("get.odometer.trip.current", "Odometer.getTripCurrentDrivingDistance", "m") { a, b -> apis.odometer.getTripCurrentDrivingDistance(a, b) }
                steps += step<Float?>("get.odometer.trip.cumulative", "Odometer.getTripCumulativeDrivingDistance", "m") { a, b -> apis.odometer.getTripCumulativeDrivingDistance(a, b) }
                steps += step<Float?>("get.fuel.current", "Fuel.getCurrentFuelEconomy", "L/100km") { a, b -> apis.fuel.getCurrentFuelEconomy(a, b) }
                steps += step<Float?>("get.fuel.cumulative", "Fuel.getCumulativeFuelEconomy", "L/100km") { a, b -> apis.fuel.getCumulativeFuelEconomy(a, b) }
                steps += step<Float?>("get.fuel.instantaneous", "Fuel.getInstantaneousFuelEconomy", "L/100km") { a, b -> apis.fuel.getInstantaneousFuelEconomy(a, b) }
                steps += step<Float?>("get.ev.efficiency.current", "EvBattery.getCurrentEvEfficiency", "km/kWh") { a, b -> apis.evBattery.getCurrentEvEfficiency(a, b) }
                steps += step<Float?>("get.ev.efficiency.cumulative", "EvBattery.getCumulativeEvEfficiency", "km/kWh") { a, b -> apis.evBattery.getCumulativeEvEfficiency(a, b) }
                steps += step<Int?>("get.ev.soc", "EvBattery.getEvBatteryStateOfCharge", "%") { a, b -> apis.evBattery.getEvBatteryStateOfCharge(a, b) }


                listener = listen()
            }
        } finally {

            mark("release:call")
            val r0 = now()
            release = try {
                apis.release()
                ProbeStep("release", "Vehicle.release()", outcome = "COMPLETE", elapsedMs = now() - r0)
            } catch (t: Throwable) {
                ProbeStep("release", "Vehicle.release()", outcome = "THREW", error = "${t::class.java.name}: ${t.message}", elapsedMs = now() - r0)
            }
            mark("release:done(${release.outcome})")
        }
        val all = listOfNotNull(init) + steps
        val counts = all.groupingBy { it.outcome }.eachCount()
        val real = steps.filter { it.id.startsWith("get.") }
        val verdict = when {
            init?.outcome != "COMPLETE" -> "BLOCKED: initialize ${init?.outcome}"
            real.any { it.outcome == "COMPLETE" && !it.valueIsNull } -> "SDK_RETURNED_VALUES"
            real.all { it.outcome == "TIMEOUT" } -> "BLOCKED: every getter timed out (vehicle service not bound?)"
            else -> "NO_VALUES: getters failed or returned null"
        }
        val report = ProbeReport(started, Instant.ofEpochMilli(now()).toString(), env.environment(), env.permissions(), init, steps, listener, release, order.toList(), counts, verdict)
        log("MOATA_PROBE_END verdict=$verdict counts=$counts")
        return report
    }

    private suspend fun listen(): ProbeListenerResult {
        val updates = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val polled = CopyOnWriteArrayList<String>()
        val l0 = now()
        val l = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) { updates += "${now() - l0}:$value"; mark("listener:callback") }
            override fun onFailed(e: Exception) { failures += "${e::class.java.name}: ${e.message}"; mark("listener:callback(onFailed)") }
        }
        var registerError: String? = null
        mark("listener:register")
        try { apis.odometer.registerDistanceDriven(l) } catch (t: Throwable) { registerError = "${t::class.java.name}: ${t.message}" }
        if (registerError == null) {
            var waited = 0L
            while (waited < listenMs) {
                val o = awaitCallback<Float?>(timeoutMs) { a, b -> apis.odometer.getCurrentDistanceDriven(a, b) }
                polled += "${now() - l0}:${if (o is CallbackOutcome.Complete) o.value.toString() else o.describe()}"
                delay(1_000); waited += 1_000
            }
        }
        var unregistered = false
        if (registerError == null) {
            mark("listener:unregister")
            unregistered = runCatching { apis.odometer.unregisterDistanceDriven(l) }.isSuccess
        }
        return ProbeListenerResult(registerError == null, registerError, updates.toList(), failures.toList(), polled.toList(), now() - l0, unregistered)
    }

    companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }
        fun encode(r: ProbeReport): String = json.encodeToString(r)
    }
}
