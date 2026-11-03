package io.moata.pleos.data.local

import android.content.Context

/** The token is an opaque bearer; the server stores only its hash. */
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
    /** PLEOS | MANUAL | DEMO. DEMO is only selectable in debug builds. */
    var dataMode: String
        get() = prefs.getString("data_mode", "PLEOS") ?: "PLEOS"
        set(v) = prefs.edit().putString("data_mode", v).apply()

    // Last trip's cost rule, so "함께 타기" can go straight to the QR once a fuel price is known.
    var lastVehicleType: String
        get() = prefs.getString("last_vehicle", "ICE") ?: "ICE"
        set(v) = prefs.edit().putString("last_vehicle", v).apply()
    var lastUnitPrice: String
        get() = prefs.getString("last_price", "") ?: ""
        set(v) = prefs.edit().putString("last_price", v).apply()
    var lastDriverIncluded: Boolean
        get() = prefs.getBoolean("last_driver_included", true)
        set(v) = prefs.edit().putBoolean("last_driver_included", v).apply()
    var lastEqualSplit: Boolean
        get() = prefs.getBoolean("last_equal_split", false)
        set(v) = prefs.edit().putBoolean("last_equal_split", v).apply()
    var lastEfficiencySource: String
        get() = prefs.getString("last_eff_source", "PLEOS") ?: "PLEOS"
        set(v) = prefs.edit().putString("last_eff_source", v).apply()
    var lastEfficiencyValue: String
        get() = prefs.getString("last_eff_value", "") ?: ""
        set(v) = prefs.edit().putString("last_eff_value", v).apply()
    var lastEfficiencyUnit: String
        get() = prefs.getString("last_eff_unit", "km/L") ?: "km/L"
        set(v) = prefs.edit().putString("last_eff_unit", v).apply()

    val signedIn: Boolean get() = !token.isNullOrBlank()
    fun clear() = prefs.edit().clear().apply()
}
