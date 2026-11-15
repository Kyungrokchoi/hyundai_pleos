package io.moata.pleos.domain

import io.moata.pleos.core.vehicle.SignalType
import io.moata.pleos.core.vehicle.VehicleSample
import io.moata.pleos.data.remote.CostRuleOut
import java.math.RoundingMode





object EfficiencyRules {
    const val SOURCE = "ESTIMATE"
    const val ELECTRIC = "ELECTRIC"


    fun fuelType(vehicleType: String, fuel: String): String = if (vehicleType == "EV") ELECTRIC else fuel

    fun signalFor(vehicleType: String): SignalType = if (vehicleType == "EV") SignalType.EV_EFFICIENCY else SignalType.FUEL_ECONOMY


    fun shouldUpload(sample: VehicleSample): Boolean = sample.usable

    fun unit(ev: Boolean): String = if (ev) "km/kWh" else "km/L"

    fun sourceLabel(source: String): String? = when (source) {
        "ESTIMATE" -> "표시연비 평균 추정"
        "PLEOS" -> "차량 제공"
        "DEMO" -> "가상 데이터"
        "MANUAL" -> "직접 입력"
        else -> null
    }


    fun figure(raw: String?): String? = raw?.toBigDecimalOrNull()?.setScale(1, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()

    fun label(ev: Boolean): String = if (ev) "전비" else "연비"


    fun valueText(rule: CostRuleOut?): String? {
        rule ?: return null
        val value = figure(rule.efficiencyKmPerUnit) ?: return null
        val source = sourceLabel(rule.efficiencySource)
        return "$value ${unit(rule.vehicleType == "EV")}" + (source?.let { " · $it" } ?: "")
    }


    fun plainValue(rule: CostRuleOut?): String? {
        rule ?: return null
        val value = figure(rule.efficiencyKmPerUnit) ?: return null
        return "$value ${unit(rule.vehicleType == "EV")}"
    }


    fun displayLine(rule: CostRuleOut?): String? = valueText(rule)?.let { "${label(rule?.vehicleType == "EV")} $it" }
}
