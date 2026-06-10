package com.ufi_toolswidget.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 流量使用记录 Room 实体。
 *
 * 每天记录一次日流量和月流量，通过 [dateKey]（格式 "yyyy-MM-dd"）去重，
 * 每天只保留一条当日最新记录。
 * 开启每小时记录后，额外以 "yyyy-MM-dd-HH" 为 key 记录每小时累计值。
 */
@Entity(
    tableName = "traffic_records",
    indices = [
        Index(value = ["dateKey"], unique = true),
        Index(value = ["recordType"])
    ]
)
data class TrafficRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期键，格式 "yyyy-MM-dd"（daily）或 "yyyy-MM-dd-HH"（hourly） */
    val dateKey: String,
    /** 记录时间戳（毫秒） */
    val timestamp: Long,
    /** 日流量原始字节数（设备 API 报告的当日累计值） */
    val dailyRawBytes: Long,
    /** 月流量原始字节数（设备 API 报告的当月累计值） */
    val monthlyRawBytes: Long,
    /** 记录类型："daily" 或 "hourly"，用于查询过滤 */
    val recordType: String = "daily"
)