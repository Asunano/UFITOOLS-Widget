package com.ufi_toolswidget.db

import androidx.paging.PagingSource
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

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun getAllPaged(): PagingSource<Int, AlertRecord>

    @Query("SELECT * FROM alerts WHERE type = :type ORDER BY timestamp DESC")
    fun getPagedByType(type: String): PagingSource<Int, AlertRecord>

    @Query("SELECT * FROM alerts WHERE isRead = :isRead ORDER BY timestamp DESC")
    fun getPagedByReadStatus(isRead: Boolean): PagingSource<Int, AlertRecord>

    @Query("SELECT * FROM alerts WHERE type = :type AND isRead = :isRead ORDER BY timestamp DESC")
    fun getPagedFiltered(type: String, isRead: Boolean): PagingSource<Int, AlertRecord>

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun getUnreadCount(): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM alerts")
    fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM alerts")
    fun observeTotalCount(): Flow<Int>

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

    /** 删除超出上限的旧记录（保留最新的 maxCount 条） */
    @Query("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY timestamp DESC LIMIT :maxCount)")
    fun deleteOldRecords(maxCount: Int)
}
