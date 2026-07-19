package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE folderId = :folderId ORDER BY timestamp DESC")
    fun getEntriesInFolder(folderId: Long): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE folderId IS NULL ORDER BY timestamp DESC")
    fun getRootEntries(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE id = -1")
    suspend fun getDraftEntry(): VaultEntry?

    @Query("DELETE FROM vault_entries WHERE id = -1")
    suspend fun deleteDraft()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultEntry): Long

    @Update
    suspend fun update(entry: VaultEntry)

    @Delete
    suspend fun delete(entry: VaultEntry)

    // Folder operations
    @Query("SELECT * FROM vault_folders ORDER BY timestamp DESC")
    fun getAllFolders(): Flow<List<VaultFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: VaultFolder): Long

    @Delete
    suspend fun deleteFolder(folder: VaultFolder)

    @Query("DELETE FROM vault_entries WHERE folderId = :folderId")
    suspend fun deleteEntriesInFolder(folderId: Long)
}
