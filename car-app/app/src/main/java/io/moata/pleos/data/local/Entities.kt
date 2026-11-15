package io.moata.pleos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey





@Entity(tableName = "outbox", indices = [Index(value = ["eventId"], unique = true), Index("state", "seq")])
data class OutboxEvent(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val kind: String,
    val tripId: String?,
    val method: String,
    val path: String,
    val body: String?,
    val createdAt: Long,
    val state: String = STATE_PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"
        const val KIND_READING = "READING"
        const val KIND_REQUEST = "REQUEST"
    }
}

@Entity(tableName = "trip")
data class LocalTrip(
    @PrimaryKey val id: String,
    val groupId: String,
    val state: String,
    val dataMode: String,
    val vehicleType: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val odometerStartKm: Double? = null,
    val lastOdometerKm: Double? = null,
    val lastOdometerAt: Long? = null,
) {
    val isActive: Boolean get() = state in ACTIVE
    companion object { val ACTIVE = setOf("INVITING", "READY", "IN_PROGRESS") }
}

@Entity(tableName = "grp")
data class LocalGroup(@PrimaryKey val id: String, val name: String)
