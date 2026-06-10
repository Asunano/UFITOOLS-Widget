package com.ufi_toolswidget.util

import android.content.Context
import android.content.Intent
import com.ufi_toolswidget.db.AlertDao
import com.ufi_toolswidget.db.AlertRecord
import com.ufi_toolswidget.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock

/**
 * 警报历史管理器（Room 实现）。
 *
 * 提供与旧 SP 版本兼容的 API，内部使用 Room 数据库存储。
 * 所有写操作通过 [writeLock] 串行化，防止清空与新增同时执行导致
 * PagingSource 失效崩溃。
 *
 * 所有 Room DAO 调用统一使用 [Dispatchers.IO]，已移除 [androidx.room.RoomDatabase.Builder.allowMainThreadQueries]。
 */
object AlertHistoryManager {

    private const val TAG = "AlertHistoryManager"
    const val ACTION_DATA_CHANGED = "com.ufi_toolswidget.ALERT_HISTORY_CHANGED"

    /** SharedPreferences 键 */
    const val PREF_KEY_PAGE_SIZE = "alert_page_size"
    const val PREF_KEY_MAX_COUNT = "alert_max_count"
    const val DEFAULT_PAGE_SIZE = 10
    const val DEFAULT_MAX_COUNT = 500  // 0 = 无限制

    private val writeLock = ReentrantLock()

    @Volatile
    private var dao: AlertDao? = null

    fun initDatabase(context: Context) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) {
                    dao = AppDatabase.getInstance(context).alertDao()
                }
            }
        }
    }

    private fun getDao(): AlertDao =
        dao ?: throw IllegalStateException("AlertHistoryManager not initialized. Call initDatabase() first.")

    // ── 设置读写（SharedPreferences，不涉及 Room） ──

    fun getPageSize(ctx: Context): Int =
        ctx.getSharedPreferences("ufitools_prefs", Context.MODE_PRIVATE)
            .getInt(PREF_KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE)

    fun getMaxCount(ctx: Context): Int =
        ctx.getSharedPreferences("ufitools_prefs", Context.MODE_PRIVATE)
            .getInt(PREF_KEY_MAX_COUNT, DEFAULT_MAX_COUNT)

    suspend fun saveSettings(ctx: Context, pageSize: Int, maxCount: Int) {
        ctx.getSharedPreferences("ufitools_prefs", Context.MODE_PRIVATE).edit()
            .putInt(PREF_KEY_PAGE_SIZE, pageSize)
            .putInt(PREF_KEY_MAX_COUNT, maxCount)
            .apply()
        // 保存后立即执行清理
        if (maxCount > 0) enforceMaxCount(maxCount)
        notifyChanged(ctx)
    }

    /** 删除超出上限的旧记录（IO 线程） */
    private suspend fun enforceMaxCount(maxCount: Int) {
        if (maxCount > 0) {
            writeLock.lock()
            try {
                withContext(Dispatchers.IO) { getDao().deleteOldRecords(maxCount) }
            } finally { writeLock.unlock() }
        }
    }

    // ── Flow 观察（Room 内部已使用后台线程） ──

    /** 未读数量观察（Flow，实时响应 Room 变更） */
    fun observeUnreadCount(): Flow<Int> = getDao().observeUnreadCount()

    /** 总数观察（Flow） */
    fun observeTotalCount(): Flow<Int> = getDao().observeTotalCount()

    // ── 同步计数（IO 线程） ──

    suspend fun getUnreadCount(): Int = withContext(Dispatchers.IO) { getDao().getUnreadCount() }
    suspend fun getTotalCount(): Int = withContext(Dispatchers.IO) { getDao().getTotalCount() }
    suspend fun getCountByType(type: String): Int = withContext(Dispatchers.IO) { getDao().getCountByType(type) }
    suspend fun getCountByReadStatus(isRead: Boolean): Int = withContext(Dispatchers.IO) { getDao().getCountByReadStatus(isRead) }
    suspend fun getCountFiltered(type: String, isRead: Boolean): Int = withContext(Dispatchers.IO) { getDao().getCountFiltered(type, isRead) }

    // ── 分页查询（IO 线程） ──

    suspend fun getPage(limit: Int, offset: Int): List<AlertRecord> =
        withContext(Dispatchers.IO) { getDao().getPage(limit, offset) }
    suspend fun getPageByType(type: String, limit: Int, offset: Int): List<AlertRecord> =
        withContext(Dispatchers.IO) { getDao().getPageByType(type, limit, offset) }
    suspend fun getPageByReadStatus(isRead: Boolean, limit: Int, offset: Int): List<AlertRecord> =
        withContext(Dispatchers.IO) { getDao().getPageByReadStatus(isRead, limit, offset) }
    suspend fun getPageFiltered(type: String, isRead: Boolean, limit: Int, offset: Int): List<AlertRecord> =
        withContext(Dispatchers.IO) { getDao().getPageFiltered(type, isRead, limit, offset) }

    // ── 写操作（加锁 + IO 线程） ──

    /** 添加一条警报记录（加锁，插入后自动清理超限旧记录） */
    suspend fun addAlert(ctx: Context, type: String, title: String, message: String) {
        writeLock.lock()
        try {
            val record = AlertRecord(
                type = type,
                title = title,
                message = message,
                timestamp = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { getDao().insert(record) }
            // 插入后检查上限
            val maxCount = getMaxCount(ctx)
            if (maxCount > 0) withContext(Dispatchers.IO) { getDao().deleteOldRecords(maxCount) }
            DebugLogger.logApi(TAG, "Alert added: type=$type title=$title")
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 标记单条为已读（加锁 + IO 线程） */
    suspend fun markRead(ctx: Context, id: Long) {
        writeLock.lock()
        try {
            withContext(Dispatchers.IO) { getDao().markRead(id) }
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 标记全部已读（加锁 + IO 线程） */
    suspend fun markAllRead(ctx: Context) {
        writeLock.lock()
        try {
            withContext(Dispatchers.IO) { getDao().markAllRead() }
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 删除单条记录（加锁 + IO 线程） */
    suspend fun remove(ctx: Context, id: Long) {
        writeLock.lock()
        try {
            withContext(Dispatchers.IO) { getDao().deleteById(id) }
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    /** 清空全部记录（加锁 + IO 线程，防止与 addAlert 并发导致 PagingSource 崩溃） */
    suspend fun clearAll(ctx: Context) {
        writeLock.lock()
        try {
            withContext(Dispatchers.IO) { getDao().clearAll() }
        } finally {
            writeLock.unlock()
        }
        notifyChanged(ctx)
    }

    private fun notifyChanged(ctx: Context) {
        val intent = Intent(ACTION_DATA_CHANGED).apply {
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(intent)
    }
}
