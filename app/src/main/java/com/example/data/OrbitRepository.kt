package com.example.data

import kotlinx.coroutines.flow.Flow

class OrbitRepository(private val database: OrbitDatabase) {
    val vaultEntries: Flow<List<VaultEntry>> = database.vaultDao().getAllEntries()
    val speedDialEntries: Flow<List<SpeedDialEntry>> = database.speedDialDao().getAllEntries()

    suspend fun insertVaultEntry(entry: VaultEntry) {
        database.vaultDao().insert(entry)
    }

    suspend fun deleteVaultEntry(entry: VaultEntry) {
        database.vaultDao().delete(entry)
    }

    suspend fun insertSpeedDialEntry(entry: SpeedDialEntry) {
        database.speedDialDao().insert(entry)
    }

    suspend fun deleteSpeedDialEntry(entry: SpeedDialEntry) {
        database.speedDialDao().delete(entry)
    }
}
