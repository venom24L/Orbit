package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE id = -1")
    suspend fun getDraftEntry(): VaultEntry?

    @Query("DELETE FROM vault_entries WHERE id = -1")
    suspend fun deleteDraft()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultEntry): Long

    @Delete
    suspend fun delete(entry: VaultEntry)
}
