package com.example.data

import kotlinx.coroutines.flow.Flow

class OrbitRepository(private val database: OrbitDatabase) {
    val vaultEntries: Flow<List<VaultEntry>> = database.vaultDao().getAllEntries()
    val speedDialEntries: Flow<List<SpeedDialEntry>> = database.speedDialDao().getAllEntries()
    
    val vaultFolders: Flow<List<VaultFolder>> = database.vaultDao().getAllFolders()
    val rootVaultEntries: Flow<List<VaultEntry>> = database.vaultDao().getRootEntries()

    fun getEntriesInFolder(folderId: Long): Flow<List<VaultEntry>> {
        return database.vaultDao().getEntriesInFolder(folderId)
    }

    suspend fun insertVaultEntry(entry: VaultEntry) {
        database.vaultDao().insert(entry)
    }

    suspend fun updateVaultEntry(entry: VaultEntry) {
        database.vaultDao().update(entry)
    }

    suspend fun deleteVaultEntry(entry: VaultEntry) {
        database.vaultDao().delete(entry)
    }

    suspend fun insertVaultFolder(folder: VaultFolder) {
        database.vaultDao().insertFolder(folder)
    }

    suspend fun deleteVaultFolder(folder: VaultFolder) {
        database.vaultDao().deleteFolder(folder)
        database.vaultDao().deleteEntriesInFolder(folder.id)
    }

    suspend fun insertSpeedDialEntry(entry: SpeedDialEntry) {
        database.speedDialDao().insert(entry)
    }

    suspend fun deleteSpeedDialEntry(entry: SpeedDialEntry) {
        database.speedDialDao().delete(entry)
    }
}
