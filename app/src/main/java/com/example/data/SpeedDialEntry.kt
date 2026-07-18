package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_dial_entries")
data class SpeedDialEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val url: String,
    val sortOrder: Int
)
