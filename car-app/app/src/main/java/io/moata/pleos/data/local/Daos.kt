package io.moata.pleos.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: OutboxEvent): Long

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY seq ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxEvent>

    @Query("UPDATE outbox SET state = 'DONE', lastError = NULL WHERE seq IN (:seqs)")
    suspend fun markDone(seqs: List<Long>)

    @Query("UPDATE outbox SET state = 'FAILED', lastError = :error WHERE seq IN (:seqs)")
    suspend fun markFailed(seqs: List<Long>, error: String)

    @Query("UPDATE outbox SET attempts = :attempts, nextAttemptAt = :next, lastError = :error WHERE seq IN (:seqs)")
    suspend fun recordRetry(seqs: List<Long>, attempts: Int, next: Long, error: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'FAILED'")
    fun failedCount(): Flow<Int>

    @Query("SELECT * FROM outbox WHERE state = 'FAILED' ORDER BY seq DESC LIMIT 20")
    suspend fun failed(): List<OutboxEvent>


    @Query("DELETE FROM outbox WHERE tripId = :tripId AND state = 'FAILED'")
    suspend fun dropFailedFor(tripId: String): Int


    @Query("UPDATE outbox SET state = 'PENDING', attempts = 0, nextAttemptAt = 0 WHERE state = 'FAILED'")
    suspend fun requeueFailed()

    @Query("SELECT COUNT(*) FROM outbox WHERE tripId = :tripId AND kind = 'READING'")
    suspend fun readingCount(tripId: String): Int
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trip WHERE state IN ('INVITING','READY','IN_PROGRESS') ORDER BY createdAt DESC LIMIT 1")
    suspend fun active(): LocalTrip?


    @Query("SELECT * FROM trip WHERE state IN ('INVITING','READY','IN_PROGRESS','ENDED','REVIEW','DISPUTED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun open(): LocalTrip?

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun get(id: String): LocalTrip?


    @Query("SELECT MAX(lastOdometerKm) FROM trip WHERE id != :exclude AND dataMode = :mode")
    suspend fun highestOdometer(exclude: String, mode: String): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: LocalTrip)

    @Query("SELECT * FROM grp ORDER BY name")
    suspend fun groups(): List<LocalGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<LocalGroup>)

    @Query("DELETE FROM trip")
    suspend fun clearTrips()
}

@Database(entities = [OutboxEvent::class, LocalTrip::class, LocalGroup::class], version = 1, exportSchema = true)
abstract class MoataDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun trips(): TripDao
}
