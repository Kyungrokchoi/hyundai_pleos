package io.moata.pleos.data.local

import android.content.Context


class Session(context: Context) {
    private val prefs = context.getSharedPreferences("moata_session", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
    var userId: String?
        get() = prefs.getString("user_id", null)
        set(v) = prefs.edit().putString("user_id", v).apply()
    var nickname: String?
        get() = prefs.getString("nickname", null)
        set(v) = prefs.edit().putString("nickname", v).apply()
    var selectedGroupId: String?
        get() = prefs.getString("group_id", null)
        set(v) = prefs.edit().putString("group_id", v).apply()

    var dataMode: String
        get() = prefs.getString("data_mode", "PLEOS") ?: "PLEOS"
        set(v) = prefs.edit().putString("data_mode", v).apply()


    var lastVehicleType: String
        get() = prefs.getString("last_vehicle", "ICE") ?: "ICE"
        set(v) = prefs.edit().putString("last_vehicle", v).apply()
    var lastUnitPrice: String
        get() = prefs.getString("last_price", "") ?: ""
        set(v) = prefs.edit().putString("last_price", v).apply()
    var lastUnitPriceSource: String
        get() = prefs.getString("last_price_source", "MANUAL") ?: "MANUAL"
        set(v) = prefs.edit().putString("last_price_source", v).apply()
    var lastFuel: String
        get() = prefs.getString("last_fuel", "GASOLINE") ?: "GASOLINE"
        set(v) = prefs.edit().putString("last_fuel", v).apply()
    var lastDriverIncluded: Boolean
        get() = prefs.getBoolean("last_driver_included", true)
        set(v) = prefs.edit().putBoolean("last_driver_included", v).apply()
    var lastEqualSplit: Boolean
        get() = prefs.getBoolean("last_equal_split", false)
        set(v) = prefs.edit().putBoolean("last_equal_split", v).apply()

    val signedIn: Boolean get() = !token.isNullOrBlank()
    fun clear() = prefs.edit().clear().apply()
}
