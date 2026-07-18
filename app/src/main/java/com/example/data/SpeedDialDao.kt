package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedDialDao {
    @Query("SELECT * FROM speed_dial_entries ORDER BY sortOrder ASC, id ASC")
    fun getAllEntries(): Flow<List<SpeedDialEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SpeedDialEntry): Long

    @Delete
    suspend fun delete(entry: SpeedDialEntry)
}
