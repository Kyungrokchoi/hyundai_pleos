package io.moata.pleos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.moata.pleos.AppContainer
import io.moata.pleos.BuildConfig
import io.moata.pleos.core.vehicle.DataSource
import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.SampleRules
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.local.LocalGroup
import io.moata.pleos.data.local.LocalTrip
import io.moata.pleos.data.remote.ApiJson
import io.moata.pleos.data.remote.ApiResult
import io.moata.pleos.data.remote.ApprovalIn
import io.moata.pleos.data.remote.AuthOut
import io.moata.pleos.data.remote.CharacterOut
import io.moata.pleos.data.remote.CostItemIn
import io.moata.pleos.data.remote.CostRuleIn
import io.moata.pleos.data.remote.CouponOut
import io.moata.pleos.data.remote.DrainReport
import io.moata.pleos.data.remote.FuelPriceOut
import io.moata.pleos.data.remote.GroupCreateIn
import io.moata.pleos.data.remote.GroupOut
import io.moata.pleos.data.remote.GuestAuthIn
import io.moata.pleos.data.remote.HistoryItem
import io.moata.pleos.data.remote.InviteCreateIn
import io.moata.pleos.data.remote.InviteOut
import io.moata.pleos.data.remote.MemberDecisionIn
import io.moata.pleos.data.remote.PlaceOut
import io.moata.pleos.data.remote.SettlementOut
import io.moata.pleos.data.remote.TollEstimateIn
import io.moata.pleos.data.remote.TollEstimateOut
import io.moata.pleos.data.remote.TollPointIn
import io.moata.pleos.data.remote.TripSummary
import io.moata.pleos.domain.DrivingState
import io.moata.pleos.domain.EfficiencyRules
import io.moata.pleos.domain.Feature
import io.moata.pleos.domain.HeaderRules
import io.moata.pleos.domain.TollRules
import io.moata.pleos.domain.TrackPoint
import io.moata.pleos.domain.TrackRules
import io.moata.pleos.domain.UxPolicy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

enum class Screen { SIGN_IN, HOME, GROUPS, TRIP_SETUP, INVITE, DRIVING, SUMMARY, SETTLEMENT, GARDEN, DEBUG }

enum class ManualPurpose { START_ODOMETER, END_ODOMETER }


data class Msg(val text: String, val detail: String? = null, val error: Boolean = true, val retry: (() -> Unit)? = null, val retryLabel: String = "다시 시도")
data class ManualPrompt(val purpose: ManualPurpose, val reason: String)

data class TripForm(
    val vehicleType: String = "ICE", val dataMode: String = "PLEOS",
    val unitPrice: String = "", val driverIncluded: Boolean = true, val equalSplit: Boolean = false,
    val unitPriceSource: String = PriceSource.MANUAL,
    val fuel: String = "GASOLINE",
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val signedIn: Boolean = false, val nickname: String? = null, val userId: String? = null,
    val groups: List<GroupOut> = emptyList(), val groupId: String? = null,
    val trip: LocalTrip? = null, val summary: TripSummary? = null,
    val invite: InviteOut? = null, val joinUrl: String? = null,
    val character: CharacterOut? = null, val history: List<HistoryItem> = emptyList(), val coupons: List<CouponOut> = emptyList(),
    val busy: Boolean = false, val msg: Msg? = null,
    val offline: Boolean = false, val pending: Int = 0, val failed: Int = 0,
    val dataMode: String = "PLEOS", val live: VehicleSample? = null, val drivingState: DrivingState = DrivingState.UNKNOWN,
    val manualPrompt: ManualPrompt? = null,
    val form: TripForm = TripForm(),
    val fuelNote: String? = null,
    val fuelHint: String? = null,
    val tollEstimate: TollEstimateOut? = null, val tollFailed: Boolean = false,
    val tollAuto: Set<String> = emptySet(),
    val route: List<TrackPoint> = emptyList(),
    val startPlace: PlaceOut? = null, val endPlace: PlaceOut? = null,
) {

    val isAdmin: Boolean get() = nickname?.trim()?.equals(AppViewModel.ADMIN_NICKNAME, ignoreCase = true) == true
    val groupName: String? get() = groups.firstOrNull { it.id == groupId }?.name
    val restricted: Boolean get() = UxPolicy.isRestricted(drivingState)
}

class AppViewModel(private val c: AppContainer) : ViewModel() {
    private fun savedForm() = TripForm(
        vehicleType = c.session.lastVehicleType, dataMode = c.session.dataMode,
        unitPrice = c.session.lastUnitPrice, driverIncluded = c.session.lastDriverIncluded, equalSplit = c.session.lastEqualSplit,
        unitPriceSource = c.session.lastUnitPriceSource, fuel = c.session.lastFuel,
    )

    private fun saveForm(f: TripForm) {
        c.session.lastUnitPriceSource = f.unitPriceSource; c.session.lastFuel = f.fuel
        c.session.lastVehicleType = f.vehicleType; c.session.lastUnitPrice = f.unitPrice
        c.session.lastDriverIncluded = f.driverIncluded; c.session.lastEqualSplit = f.equalSplit
    }

    private val _ui = MutableStateFlow(
        UiState(signedIn = c.session.signedIn, nickname = c.session.nickname, userId = c.session.userId,
            groupId = c.session.selectedGroupId, dataMode = c.session.dataMode, form = savedForm(),
            screen = if (c.session.signedIn) Screen.HOME else Screen.SIGN_IN),
    )
    val ui: StateFlow<UiState> = _ui

    init {
        viewModelScope.launch {
            c.drivingState.collect { s ->
                _ui.update { it.copy(drivingState = s) }

                if (!UxPolicy.isRestricted(s) && _ui.value.screen == Screen.SUMMARY && _ui.value.tollEstimate != null) viewModelScope.launch { applyTolls() }
            }
        }
        viewModelScope.launch { c.db.outbox().pendingCount().collect { n -> _ui.update { it.copy(pending = n) } } }
        viewModelScope.launch { c.db.outbox().failedCount().collect { n -> _ui.update { it.copy(failed = n) } } }
        viewModelScope.launch { c.recorder.live.collect { s -> _ui.update { it.copy(live = s) } } }
        viewModelScope.launch {
            c.lastDrain.collect { d -> if (d != null) _ui.update { it.copy(offline = d.stoppedBecause == DrainReport.Stop.OFFLINE) } }
        }
        viewModelScope.launch { recover(); if (c.session.signedIn) { refreshGroups(); } }
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                val s = _ui.value
                if (s.signedIn && s.trip != null && s.screen in POLLED) fetchSummary()
            }
        }
    }


    private suspend fun recover() {
        val t = c.repo.open() ?: return
        _ui.update { it.copy(trip = t, dataMode = t.dataMode) }
        if (t.state == "IN_PROGRESS") {
            val src = c.source(t.dataMode)
            if (src.connect().isSuccess) c.recorder.start(t.id, src)
            c.tracker.start(t.id)
            _ui.update { it.copy(screen = Screen.DRIVING) }
        } else if (t.state == "INVITING" || t.state == "READY") {
            _ui.update { it.copy(screen = Screen.INVITE) }
        } else {
            _ui.update { it.copy(screen = Screen.SUMMARY) }
            loadRoute()
            fetchSummary()
            requestTolls()
        }
    }

    fun go(s: Screen) = _ui.update { it.copy(screen = s, msg = null) }
    fun dismissMsg() = _ui.update { it.copy(msg = null) }
    fun setForm(f: TripForm) = _ui.update { it.copy(form = f) }
    fun setDataMode(m: String) {
        if (m == "DEMO" && !BuildConfig.DEMO_TOOLS) return
        c.session.dataMode = m
        _ui.update { it.copy(dataMode = m, form = it.form.copy(dataMode = m)) }
    }
    fun setUxOverride(s: DrivingState?) { if (BuildConfig.DEMO_TOOLS) c.uxOverride.value = s }
    fun demoAdvance(km: Double) = c.demo.advance(km)
    fun demoReset() = c.demo.reset()
    fun runProbe(listenSec: Int) = c.runProbe(listenSec)
    val tiles get() = c.tiles
    val probeResult get() = c.probeResult
    val probeRunning get() = c.probeRunning
    fun requeueFailed(): Job = viewModelScope.launch { c.db.outbox().requeueFailed(); sync() }
    fun signOut() { c.session.clear(); _ui.update { UiState(screen = Screen.SIGN_IN, drivingState = it.drivingState) } }

    private fun busy(b: Boolean) = _ui.update { it.copy(busy = b) }
    private fun error(text: String, detail: String? = null, retry: (() -> Unit)? = null) {
        if (detail != null) android.util.Log.w("MOATA", "$text | $detail")
        _ui.update { it.copy(busy = false, msg = Msg(text, detail, true, retry)) }
    }





    private fun parked(): Boolean = !_ui.value.restricted


    private fun <T> alreadyDone(r: ApiResult<T>, vararg codes: String): Boolean = r is ApiResult.Rejected && r.errorCode in codes

    private fun <T> unwrap(r: ApiResult<T>, retry: (() -> Unit)? = null): T? = when (r) {
        is ApiResult.Ok -> { _ui.update { it.copy(offline = false) }; r.value }
        is ApiResult.Rejected -> { error(r.messageKo, "${r.code} ${r.errorCode.orEmpty()}".trim(), retry); null }
        is ApiResult.Offline -> {
            android.util.Log.w("MOATA", "offline: ${r.reason}")
            _ui.update { it.copy(offline = true, busy = false, msg = Msg("서버에 연결할 수 없어요. 기록은 차량에 저장돼 있어요.", r.reason, true, retry)) }; null
        }
        ApiResult.Unauthorized -> { _ui.update { it.copy(busy = false, msg = Msg("로그인이 만료되었어요. 닉네임으로 다시 시작해 주세요.", null, true, { signOut() }, "다시 로그인")) }; null }
    }

    fun signIn(nickname: String): Job = viewModelScope.launch {
        val name = nickname.trim()
        if (name.isEmpty()) { error("닉네임을 입력해 주세요."); return@launch }
        busy(true)
        val out = unwrap(c.api.post<GuestAuthIn, AuthOut>("/auth/guest", GuestAuthIn(name, CONSENT_VERSION))) { signIn(nickname) } ?: return@launch
        c.session.token = out.token; c.session.userId = out.userId; c.session.nickname = out.nickname
        _ui.update { it.copy(signedIn = true, nickname = out.nickname, userId = out.userId, busy = false, screen = Screen.HOME) }
        refreshGroups()
    }

    fun refreshGroups(): Job = viewModelScope.launch {
        val list = unwrap(c.api.get<List<GroupOut>>("/groups")) { refreshGroups() }
        if (list == null) {
            val cached = c.db.trips().groups()
            _ui.update { it.copy(groups = cached.map { g -> GroupOut(g.id, g.name, "", "") }) }
            return@launch
        }
        c.db.trips().upsertGroups(list.map { LocalGroup(it.id, it.name) })
        _ui.update { s -> s.copy(groups = list, groupId = s.groupId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id) }
        _ui.value.groupId?.let { c.session.selectedGroupId = it }
    }


    fun createGroup(name: String, id: String = UUID.randomUUID().toString()): Job = viewModelScope.launch {
        if (name.isBlank()) { error("그룹 이름을 입력해 주세요."); return@launch }
        busy(true)
        unwrap(c.api.post<GroupCreateIn, GroupOut>("/groups", GroupCreateIn(id, name.trim()))) { createGroup(name, id) } ?: return@launch
        c.session.selectedGroupId = id
        _ui.update { it.copy(groupId = id, busy = false, screen = Screen.HOME) }
        refreshGroups()
    }

    fun selectGroup(id: String) { c.session.selectedGroupId = id; _ui.update { it.copy(groupId = id, screen = Screen.HOME) } }

    private fun ruleFrom(f: TripForm) = CostRuleIn(
        vehicleType = f.vehicleType, fuelType = EfficiencyRules.fuelType(f.vehicleType, f.fuel), efficiencySource = EfficiencyRules.SOURCE,
        unitPriceKrw = f.unitPrice, unitPriceSource = f.unitPriceSource, driverIncluded = f.driverIncluded,
        energyPolicy = if (f.equalSplit) "EQUAL_SPLIT" else "SEGMENT_SHARED",
    )

    private fun invalidForm(f: TripForm): String? = when {
        f.unitPrice.toDoubleOrNull()?.let { it > 0 } != true -> if (f.vehicleType == "EV") "전기요금(원/kWh)을 입력해 주세요." else "유가(원/L)를 입력해 주세요."
        else -> null
    }





    fun startFlow(): Job = viewModelScope.launch {
        _ui.update { s -> if (s.form.vehicleType == "EV") s else s.copy(form = s.form.copy(unitPriceSource = PriceSource.OPINET)) }
        if (_ui.value.form.wantsAutoPrice()) { busy(true); fetchAutoPrice(); busy(false) }
        val f = _ui.value.form
        if (invalidForm(f) == null) createTripAndInvite(f)
        else error("오늘 유가를 불러오지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.", null) { startFlow() }
    }


    private suspend fun fetchAutoPrice() {
        val before = _ui.value.form
        if (!before.wantsAutoPrice()) return
        val r = c.api.get<FuelPriceOut>("/fuel-price?fuel=${before.fuel}")
        _ui.update { s ->
            if (s.form.unitPrice != before.unitPrice || s.form.fuel != before.fuel) return@update s
            when (r) {
                is ApiResult.Ok -> s.copy(form = s.form.withAutoPrice(r.value), fuelNote = fuelPriceLabel(r.value), fuelHint = null)
                else -> {
                    android.util.Log.w("MOATA", "fuel price not available: $r")
                    val kept = s.form.afterFailedFetch()
                    s.copy(form = kept, fuelNote = null, fuelHint = if (kept.unitPrice.isBlank()) "오늘 유가를 불러오지 못했어요. 직접 입력해 주세요." else null)
                }
            }
        }
    }

    fun setUnitPrice(v: String) = _ui.update { it.copy(form = it.form.withTypedPrice(v), fuelNote = null, fuelHint = null) }


    fun setFuel(fuel: String) {
        _ui.update { it.copy(form = it.form.copy(fuel = fuel)) }
        viewModelScope.launch { fetchAutoPrice() }
    }

    fun createTripAndInvite(f: TripForm): Job = viewModelScope.launch {
        val s = _ui.value
        val gid = s.groupId ?: run { error("먼저 그룹을 선택해 주세요."); return@launch }
        if (!UxPolicy.isAllowed(Feature.TRIP_SETUP, s.drivingState)) return@launch
        invalidForm(f)?.let { error(it); return@launch }
        busy(true)
        saveForm(f)
        val mode = f.dataMode
        val trip = c.repo.createTrip(UUID.randomUUID().toString(), gid, mode, f.vehicleType, ruleFrom(f))
        _ui.update { it.copy(trip = trip, dataMode = trip.dataMode) }
        makeInvite(trip)
    }

    fun updateRule(f: TripForm): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        invalidForm(f)?.let { error(it); return@launch }
        busy(true)
        c.flush()
        unwrap(c.api.call<TripSummary>("PUT", "/trips/${t.id}/cost-rule", ApiJson.encodeToString(ruleFrom(f)))) { updateRule(f) } ?: return@launch
        saveForm(f); busy(false); fetchSummary()
        _ui.update { it.copy(screen = Screen.INVITE) }
    }

    fun makeInvite(trip: LocalTrip? = _ui.value.trip): Job = viewModelScope.launch {
        val t = trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val drain = c.flush()
        if (drain.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 연결되면 다시 시도해 주세요.", null) { makeInvite(t) }; return@launch }
        val inv = unwrap(c.api.post<InviteCreateIn, InviteOut>("/groups/${t.groupId}/invites", InviteCreateIn(tripId = t.id))) { makeInvite(t) } ?: return@launch
        _ui.update { it.copy(invite = inv, joinUrl = BuildConfig.WEB_BASE_URL.trimEnd('/') + inv.joinPath, busy = false, screen = Screen.INVITE) }
        refreshSummary()
    }

    fun decideMember(userId: String, approve: Boolean): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val r = c.api.post<MemberDecisionIn, io.moata.pleos.data.remote.MemberOut>("/groups/${t.groupId}/members/$userId/decision", MemberDecisionIn(if (approve) "APPROVE" else "REJECT"))
        if (!alreadyDone(r, "MEMBER_NOT_PENDING")) unwrap(r) { decideMember(userId, approve) } ?: return@launch
        busy(false); refreshSummary()
    }


    fun markBoarded(userId: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.repo.board(t.id, userId, alight = false); sync(); delay(300); refreshSummary()
    }


    fun setBoarded(userId: String, onBoard: Boolean): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.repo.board(t.id, userId, alight = !onBoard); sync(); delay(300); refreshSummary()
    }

    fun cancelTrip(): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.recorder.stop(); c.tracker.clear(t.id); c.tollLedger.clear(t.id); c.repo.cancel(t.id); sync()
        _ui.update { it.copy(trip = null, summary = null, invite = null, joinUrl = null, tollEstimate = null, tollFailed = false, tollAuto = emptySet(), route = emptyList(), startPlace = null, endPlace = null, screen = Screen.HOME) }
    }

    fun refreshSummary(): Job = viewModelScope.launch { fetchSummary() }


    private suspend fun fetchSummary(): TripSummary? {
        val t = _ui.value.trip ?: return null
        return when (val r = c.api.get<TripSummary>("/trips/${t.id}/summary")) {
            is ApiResult.Ok -> { _ui.update { it.copy(summary = r.value, offline = false) }; r.value }
            is ApiResult.Offline -> { _ui.update { it.copy(offline = true) }; null }
            is ApiResult.Rejected -> { if (r.code != 404) _ui.update { it.copy(msg = Msg(r.messageKo, "${r.code}")) }; null }
            ApiResult.Unauthorized -> { unwrap(r); null }
        }
    }

    private fun sync(): Job = viewModelScope.launch { c.flush() }


    fun startTrip(boardFor: List<String> = emptyList()): Job = viewModelScope.launch {
        val s = _ui.value
        val t = s.trip ?: return@launch
        if (!UxPolicy.isAllowed(Feature.TRIP_START_END, s.drivingState)) return@launch
        busy(true)
        boardFor.forEach { c.repo.board(t.id, it, alight = false) }
        if (t.state == "INVITING") {
            val d = c.flush()
            if (d.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 연결 후 다시 시도해 주세요.", null) { startTrip() }; return@launch }
            unwrap(c.api.call<Unit>("POST", "/trips/${t.id}/ready")) { startTrip() } ?: return@launch
            c.repo.markReady(t.id)
        }
        val src = c.source(t.dataMode)
        val conn = src.connect()
        if (conn.isFailure) { error("차량 데이터에 연결하지 못했어요.", conn.exceptionOrNull()?.message) { startTrip() }; return@launch }
        val odo = saneOdometer(src, t)
        val odometerKm = odo.usableValue
        if (odometerKm != null && t.dataMode != "MANUAL" && SampleRules.implausibleOdometer(odometerKm, c.db.trips().highestOdometer(t.id, t.dataMode))) {
            error("차량 주행거리를 제대로 읽지 못했어요. 잠시 후 다시 눌러 주세요. 임의의 값으로 대체하지 않았어요.", "start odometer $odometerKm") { startTrip() }
            return@launch
        }
        if (!odo.usable) {

            if (t.dataMode == "MANUAL") { _ui.update { it.copy(busy = false, manualPrompt = ManualPrompt(ManualPurpose.START_ODOMETER, "계기판의 현재 주행거리(km)를 입력해 주세요.")) } }
            else error("차량 주행거리를 읽을 수 없어요. 임의의 값으로 대체하지 않았어요.", odo.error) { startTrip() }
            return@launch
        }
        val started = c.repo.start(t.id, odo) ?: return@launch
        c.recorder.start(started.id, src)
        c.tracker.start(started.id)
        sync()
        _ui.update { it.copy(trip = started, busy = false, screen = Screen.DRIVING) }
        refreshSummary()
    }

    fun endTrip(): Job = viewModelScope.launch {
        val s = _ui.value
        val t = s.trip ?: return@launch
        if (!UxPolicy.isAllowed(Feature.TRIP_START_END, s.drivingState)) return@launch
        busy(true)
        val src = c.source(t.dataMode)
        val odo = saneOdometer(src, t)
        val start = t.odometerStartKm
        val odometerKm = odo.usableValue
        if (odometerKm != null && t.dataMode != "MANUAL" && (odometerKm <= 0.0 || (start != null && odometerKm < start))) {
            error("도착 주행거리를 제대로 읽지 못했어요. 잠시 후 다시 눌러 주세요. 임의의 값으로 대체하지 않았어요.", "end odometer $odometerKm start $start") { endTrip() }
            return@launch
        }
        if (!odo.usable) {
            if (t.dataMode == "MANUAL") _ui.update { it.copy(busy = false, manualPrompt = ManualPrompt(ManualPurpose.END_ODOMETER, "도착 시 계기판의 주행거리(km)를 입력해 주세요.")) }
            else error("도착 주행거리를 읽을 수 없어요. 임의의 값으로 대체하지 않았어요.", odo.error) { endTrip() }
            return@launch
        }
        c.recorder.stop()
        c.tracker.stop()
        offerVehicleEfficiency(src, t)
        val ended = c.repo.end(t.id, odo)
        _ui.update { it.copy(trip = ended, busy = false, screen = Screen.SUMMARY, tollEstimate = null, tollFailed = false, tollAuto = emptySet(), route = emptyList(), startPlace = null, endPlace = null) }
        loadRoute()
        c.flush()
        fetchSummary()
        requestTolls()
    }


    private fun loadRoute() {
        val t = _ui.value.trip ?: return
        val route = TrackRules.thin(c.tracker.points(t.id), ROUTE_POINTS_MAX)
        _ui.update { it.copy(route = route) }
        if (route.isNotEmpty()) viewModelScope.launch { loadPlaces(route.first(), route.last()) }
    }


    private suspend fun loadPlaces(start: TrackPoint, end: TrackPoint) {
        suspend fun name(p: TrackPoint): PlaceOut? =
            when (val r = c.api.get<PlaceOut>("/places/region?lat=%.6f&lon=%.6f".format(java.util.Locale.ROOT, p.lat, p.lon))) {
                is ApiResult.Ok -> r.value
                else -> { android.util.Log.w("MOATA", "place name not available: $r"); null }
            }
        val a = name(start)
        val b = name(end)
        _ui.update { it.copy(startPlace = a, endPlace = b) }
    }


    fun estimateTolls(): Job = viewModelScope.launch { requestTolls() }





    private suspend fun requestTolls() {
        val t = _ui.value.trip ?: return
        val track = TrackRules.thin(c.tracker.points(t.id))
        if (track.isEmpty()) return
        val occupants = (_ui.value.summary ?: fetchSummary())?.participants?.count { it.isDriver || it.intervals.isNotEmpty() }?.takeIf { it > 0 }
        val body = TollEstimateIn(track.map { TollPointIn(it.lat, it.lon, Instant.ofEpochMilli(it.atEpochMs).toString()) }, TollRules.VEHICLE_CLASS_PASSENGER, occupants)
        when (val r = c.api.call<TollEstimateOut>("POST", "/trips/${t.id}/tolls/estimate", ApiJson.encodeToString(body))) {
            is ApiResult.Ok -> { _ui.update { it.copy(tollEstimate = r.value, tollFailed = false) }; applyTolls() }
            else -> { android.util.Log.w("MOATA", "toll estimate not available: $r"); _ui.update { it.copy(tollFailed = true) } }
        }
    }

    private val tollLock = Mutex()






    private suspend fun applyTolls() = tollLock.withLock {
        val t = _ui.value.trip ?: return@withLock
        val est = _ui.value.tollEstimate ?: return@withLock
        if (!est.dataAvailable) return@withLock
        val done = c.tollLedger.added(t.id)
        _ui.update { it.copy(tollAuto = done) }
        val due = TollRules.due(TollRules.lines(est, t.id), done)
        if (due.isEmpty() || !parked()) return@withLock
        due.forEach { c.repo.addCostItem(t.id, CostItemIn(it.id, TollRules.ITEM_KIND, it.amountKrw, TollRules.DEFAULT_ALLOCATION, roadName = it.roadName, estimated = true)) }
        c.tollLedger.record(t.id, due.map { it.id })
        _ui.update { it.copy(tollAuto = done + due.map { l -> l.id }) }
        c.flush()
        fetchSummary()
    }





    private suspend fun offerVehicleEfficiency(src: io.moata.pleos.core.vehicle.VehicleDataSource, t: LocalTrip) {
        val sample = src.read(EfficiencyRules.signalFor(t.vehicleType))
        if (EfficiencyRules.shouldUpload(sample)) c.repo.recordReading(t.id, sample)
        else android.util.Log.w("MOATA", "${sample.type.name} not available from the vehicle: ${sample.error}")
    }


    private suspend fun saneOdometer(src: io.moata.pleos.core.vehicle.VehicleDataSource, t: LocalTrip): VehicleSample {
        if (t.dataMode == "MANUAL") return src.read(SignalType.ODOMETER)
        val floor = maxOf(c.db.trips().highestOdometer(t.id, t.dataMode) ?: 0.0, t.odometerStartKm ?: 0.0).takeIf { it > 0.0 }
        var s = src.read(SignalType.ODOMETER)
        repeat(3) {
            val value = s.usableValue ?: return s
            if (!SampleRules.implausibleOdometer(value, floor)) return s
            android.util.Log.w("MOATA", "implausible odometer ${s.value} (floor $floor), reading again")
            delay(700); s = src.read(SignalType.ODOMETER)
        }
        return s
    }


    fun submitManual(purpose: ManualPurpose, value: String): Job = viewModelScope.launch {
        val v = value.toDoubleOrNull()
        if (v == null || v <= 0 && purpose != ManualPurpose.START_ODOMETER) { error("0보다 큰 숫자를 입력해 주세요."); return@launch }
        _ui.update { it.copy(manualPrompt = null) }
        c.manual.setOdometer(v)
        if (purpose == ManualPurpose.START_ODOMETER) startTrip() else endTrip()
    }

    fun dismissManual() = _ui.update { it.copy(manualPrompt = null) }

    fun addCostItem(kind: String, amount: String, allocation: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        val krw = amount.toIntOrNull()
        if (krw == null || krw <= 0) { error("금액은 1원 이상의 정수로 입력해 주세요."); return@launch }
        c.repo.addCostItem(t.id, CostItemIn(UUID.randomUUID().toString(), kind, krw, allocation))
        sync(); delay(300); refreshSummary()
    }

    fun deleteCostItem(id: String): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        c.repo.deleteCostItem(t.id, id); sync(); delay(300); refreshSummary()
    }

    fun previewSettlement(): Job = viewModelScope.launch {
        val t = _ui.value.trip ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val d = c.flush()
        if (d.stoppedBecause == DrainReport.Stop.OFFLINE) { error("서버에 연결할 수 없어요. 기록은 안전하게 저장돼 있어요.", null) { previewSettlement() }; return@launch }
        unwrap(c.api.call<SettlementOut>("POST", "/trips/${t.id}/settlements/preview")) { previewSettlement() } ?: return@launch
        busy(false); fetchSummary()
        _ui.update { it.copy(screen = Screen.SETTLEMENT) }
    }

    fun decide(settlementId: String, approve: Boolean): Job = viewModelScope.launch {
        val tripId = _ui.value.trip?.id ?: return@launch
        if (!parked()) return@launch
        busy(true)
        val r = c.api.post<ApprovalIn, SettlementOut>("/settlements/$settlementId/approvals", ApprovalIn(if (approve) "APPROVE" else "DISPUTE"))
        if (!alreadyDone(r, "TRIP_LOCKED")) unwrap(r) { decide(settlementId, approve) } ?: return@launch
        busy(false)
        if (fetchSummary()?.state == "CONFIRMED") c.repo.close(tripId, confirmed = true)
    }


    fun goHome() {
        val s = _ui.value
        if (HeaderRules.homeAction(s.trip != null, s.summary?.state) == HeaderRules.HomeAction.FINISH_TRIP) finishTrip() else go(Screen.HOME)
    }


    fun finishTrip(openMoa: Boolean = false): Job = viewModelScope.launch {
        val t = _ui.value.trip
        t?.let {
            c.repo.close(it.id, confirmed = _ui.value.summary?.state == "CONFIRMED"); c.tracker.clear(it.id); c.tollLedger.clear(it.id)
        }
        val dropped = t?.let { c.db.outbox().dropFailedFor(it.id) } ?: 0
        if (dropped > 0) android.util.Log.w("MOATA", "dropped $dropped refused outbox events of closed trip ${t?.id}")
        _ui.update { it.copy(trip = null, summary = null, invite = null, joinUrl = null, tollEstimate = null, tollFailed = false, tollAuto = emptySet(), route = emptyList(), startPlace = null, endPlace = null, screen = if (openMoa) Screen.GARDEN else Screen.HOME,
            msg = if (dropped > 0) Msg("이 운행에서 서버가 받지 않은 기록 ${dropped}건을 정리했어요.", error = false) else it.msg) }
        loadGarden()
    }

    fun loadGarden(): Job = viewModelScope.launch {
        val gid = _ui.value.groupId ?: return@launch
        unwrap(c.api.get<CharacterOut>("/groups/$gid/character"))?.let { ch -> _ui.update { it.copy(character = ch) } }
        unwrap(c.api.get<List<HistoryItem>>("/groups/$gid/history"))?.let { h -> _ui.update { it.copy(history = h) } }
        unwrap(c.api.get<List<CouponOut>>("/groups/$gid/rewards"))?.let { r -> _ui.update { it.copy(coupons = r) } }
    }

    companion object {
        const val CONSENT_VERSION = "2026-11-v1"
        const val ADMIN_NICKNAME = "admin"
        private const val ROUTE_POINTS_MAX = 2_000
        private val POLLED = setOf(Screen.INVITE, Screen.DRIVING, Screen.SUMMARY, Screen.SETTLEMENT)
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(c) as T
        }
    }
}
