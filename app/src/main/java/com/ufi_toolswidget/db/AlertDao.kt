package com.ufi_toolswidget.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 警报记录 DAO。
 */
@Dao
interface AlertDao {

    // ── 分页查询（LIMIT/OFFSET）──

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPage(limit: Int, offset: Int): List<AlertRecord>

    @Query("SELECT * FROM alerts WHERE type = :type ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageByType(type: String, limit: Int, offset: Int): List<AlertRecord>

    @Query("SELECT * FROM alerts WHERE isRead = :isRead ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageByReadStatus(isRead: Boolean, limit: Int, offset: Int): List<AlertRecord>

    @Query("SELECT * FROM alerts WHERE type = :type AND isRead = :isRead ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getPageFiltered(type: String, isRead: Boolean, limit: Int, offset: Int): List<AlertRecord>

    // ── 计数 ──

    @Query("SELECT COUNT(*) FROM alerts")
    fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM alerts")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun getUnreadCount(): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM alerts WHERE type = :type")
    fun getCountByType(type: String): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = :isRead")
    fun getCountByReadStatus(isRead: Boolean): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE type = :type AND isRead = :isRead")
    fun getCountFiltered(type: String, isRead: Boolean): Int

    // ── 写操作 ──

    @Insert
    fun insert(record: AlertRecord)

    @Update
    fun update(record: AlertRecord)

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :id")
    fun markRead(id: Long)

    @Query("UPDATE alerts SET isRead = 1")
    fun markAllRead()

    @Query("DELETE FROM alerts WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM alerts")
    fun clearAll()

    @Query("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY timestamp DESC LIMIT :maxCount)")
    fun deleteOldRecords(maxCount: Int)
}
