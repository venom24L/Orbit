package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_folders")
data class VaultFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val timestamp: Long = System.currentTimeMillis()
)
