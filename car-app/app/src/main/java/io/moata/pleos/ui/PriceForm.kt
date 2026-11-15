package io.moata.pleos.ui

import io.moata.pleos.data.remote.FuelPriceOut


object PriceSource {
    const val MANUAL = "MANUAL"
    const val OPINET = "OPINET"
}


val FUEL_CHOICES = listOf("GASOLINE" to "휘발유", "DIESEL" to "경유", "LPG" to "LPG")


fun TripForm.withTypedPrice(v: String) = copy(unitPrice = v, unitPriceSource = PriceSource.MANUAL)

fun TripForm.withAutoPrice(p: FuelPriceOut) = copy(unitPrice = p.priceKrw.toString(), unitPriceSource = PriceSource.OPINET)


fun TripForm.wantsAutoPrice() = vehicleType != "EV" && (unitPrice.isBlank() || unitPriceSource == PriceSource.OPINET)


fun TripForm.afterFailedFetch() = if (unitPriceSource == PriceSource.OPINET && unitPrice.isNotBlank()) copy(unitPriceSource = PriceSource.MANUAL) else this

fun fuelPriceLabel(p: FuelPriceOut) = "오피넷 ${p.region} 평균 · 자동 입력"
