package com.ufi_toolswidget.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 流量记录 DAO。
 * 查询按 recordType 过滤，分离每日和每小时记录。
 */
@Dao
interface TrafficDao {

    /** 插入或替换：同一 dateKey 只保留一条最新记录 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TrafficRecord)

    // ── 每日记录查询（recordType = 'daily'）──

    /** 查询最近 N 条每日记录（按日期降序） */
    @Query("SELECT * FROM traffic_records WHERE recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<TrafficRecord>

    /** 查询每日记录（Flow 观察） */
    @Query("SELECT * FROM traffic_records WHERE recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrafficRecord>>

    /** 分页查询每日记录 */
    @Query("SELECT * FROM traffic_records WHERE recordType = 'daily' ORDER BY dateKey DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentPaged(limit: Int, offset: Int): List<TrafficRecord>

    // ── 每小时记录查询（recordType = 'hourly'）──

    /** 分页查询每小时记录 */
    @Query("SELECT * FROM traffic_records WHERE recordType = 'hourly' ORDER BY dateKey DESC LIMIT :limit OFFSET :offset")
    suspend fun getHourlyPaged(limit: Int, offset: Int): List<TrafficRecord>

    /** 每小时记录总数 */
    @Query("SELECT COUNT(*) FROM traffic_records WHERE recordType = 'hourly'")
    suspend fun getHourlyCount(): Int

    // ── 通用查询 ──

    /** 按 dateKey 查询单条记录 */
    @Query("SELECT * FROM traffic_records WHERE dateKey = :dateKey")
    suspend fun getByDateKey(dateKey: String): TrafficRecord?

    /** 获取最早记录的 dateKey */
    @Query("SELECT dateKey FROM traffic_records ORDER BY dateKey ASC LIMIT 1")
    suspend fun getOldestDateKey(): String?

    /** 获取记录总数（所有类型） */
    @Query("SELECT COUNT(*) FROM traffic_records")
    suspend fun getCount(): Int

    /** 获取每日记录数量 */
    @Query("SELECT COUNT(*) FROM traffic_records WHERE recordType = 'daily'")
    suspend fun getDailyCount(): Int

    /** 删除指定日期之前的旧记录 */
    @Query("DELETE FROM traffic_records WHERE dateKey < :dateKey")
    suspend fun deleteOlderThan(dateKey: String)

    // ── 月度聚合查询（基于每日记录）──

    /** 分页查询月聚合记录（按月份降序） */
    @Query("""
        SELECT t.id, t.dateKey, t.timestamp, t.dailyRawBytes, t.monthlyRawBytes, t.recordType
        FROM traffic_records t
        INNER JOIN (
            SELECT MAX(dateKey) AS max_dateKey
            FROM traffic_records
            WHERE recordType = 'daily'
            GROUP BY substr(dateKey, 1, 7)
        ) sub ON t.dateKey = sub.max_dateKey
        ORDER BY t.dateKey DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMonthlyPaged(limit: Int, offset: Int): List<TrafficRecord>

    /** 获取去重的月数（基于每日记录） */
    @Query("SELECT COUNT(DISTINCT substr(dateKey, 1, 7)) FROM traffic_records WHERE recordType = 'daily'")
    suspend fun getMonthlyCount(): Int

    /** 按月聚合查询：按 yyyy-MM 分组，取当月最后一条每日记录 */
    @Query("""
        SELECT t.id, t.dateKey, t.timestamp, t.dailyRawBytes, t.monthlyRawBytes, t.recordType
        FROM traffic_records t
        INNER JOIN (
            SELECT MAX(dateKey) AS max_dateKey
            FROM traffic_records
            WHERE recordType = 'daily'
            GROUP BY substr(dateKey, 1, 7)
        ) sub ON t.dateKey = sub.max_dateKey
        ORDER BY t.dateKey DESC
        LIMIT :limit
    """)
    suspend fun getMonthlyRecords(limit: Int): List<TrafficRecord>

    /** 删除全部记录 */
    @Query("DELETE FROM traffic_records")
    suspend fun clearAll()
}
