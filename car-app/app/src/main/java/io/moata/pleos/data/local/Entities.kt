package io.moata.pleos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per HTTP request or reading, replayed in `seq` order. `eventId` is unique, so enqueueing twice is a
 * no-op; the server is idempotent on the same client-generated ids.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["eventId"], unique = true), Index("state", "seq")])
data class OutboxEvent(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val kind: String,          // READING | REQUEST
    val tripId: String?,
    val method: String,
    val path: String,          // relative to /api/v1
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
        const val STATE_FAILED = "FAILED"   // rejected by the server (4xx); kept for inspection
        const val KIND_READING = "READING"
        const val KIND_REQUEST = "REQUEST"
    }
}

@Entity(tableName = "trip")
data class LocalTrip(
    @PrimaryKey val id: String,
    val groupId: String,
    val state: String,         // INVITING | READY | IN_PROGRESS | ENDED | REVIEW | CONFIRMED | CANCELLED
    val dataMode: String,      // PLEOS | MANUAL | DEMO
    val vehicleType: String,   // ICE | EV
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
