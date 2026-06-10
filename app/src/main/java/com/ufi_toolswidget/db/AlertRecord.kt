package com.ufi_toolswidget.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 警报记录 Room 实体。
 */
@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["type", "isRead", "timestamp"])
    ]
)
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean = false
)
