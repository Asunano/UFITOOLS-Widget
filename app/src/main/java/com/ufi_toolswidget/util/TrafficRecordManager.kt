package com.ufi_toolswidget.util

import android.content.Context
import com.ufi_toolswidget.db.AppDatabase
import com.ufi_toolswidget.db.TrafficDao
import com.ufi_toolswidget.db.TrafficRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 流量记录管理器。
 *
 * 负责在每次成功获取 WiFi 数据后，记录当前日流量和月流量到 Room。
 * 每日记录以 "yyyy-MM-dd" 为 key，使用 REPLACE 策略确保每天只保留最新累计值。
 * 开启每小时记录后，额外以 "yyyy-MM-dd-HH" 为 key 存储每小时累计值。
 * 差值计算在显示层完成，存储层始终保存设备 API 报告的原始累计值。
 */
object TrafficRecordManager {

    private const val TAG = "TrafficRecordManager"
    private const val MAX_RECORDS = 366  // 最多保留 1 年

    /** 日期格式化器（线程安全，仅用于格式化取当前日期） */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val hourlyDateFormat = SimpleDateFormat("yyyy-MM-dd-HH", Locale.getDefault())

    @Volatile
    private var dao: TrafficDao? = null

    fun initDatabase(context: Context) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) {
                    dao = AppDatabase.getInstance(context).trafficDao()
                }
            }
        }
    }

    private fun getDao(): TrafficDao =
        dao ?: throw IllegalStateException("TrafficRecordManager not initialized. Call initDatabase() first.")

    /**
     * 记录流量数据。
     *
     * 始终保存一条每日记录（累计值），开启每小时记录时额外保存一条每小时记录（累计值）。
     * 存储层不做差值计算，差值在显示时由上层计算。
     *
     * @param context Context
     * @param dailyRawBytes 日流量字节数（设备 API 报告的当日累计值）
     * @param monthlyRawBytes 月流量字节数（设备 API 报告的当月累计值）
     */
    suspend fun saveRecord(context: Context, dailyRawBytes: Long, monthlyRawBytes: Long) {
        // 检查总开关
        if (!SPUtil.getTrafficRecordEnabled(context)) {
            DebugLogger.logApi(TAG, "Traffic recording disabled by master switch")
            return
        }
        withContext(Dispatchers.IO) {
            val hourlyEnabled = SPUtil.getTrafficHourlyRecordEnabled(context)
            val now = Date()
            val ts = System.currentTimeMillis()

            // 始终保存每日记录（累计值）— SimpleDateFormat 非线程安全，需 synchronized
            val dailyKey = synchronized(dateFormat) { dateFormat.format(now) }
            val dailyRecord = TrafficRecord(
                dateKey = dailyKey,
                timestamp = ts,
                dailyRawBytes = dailyRawBytes,
                monthlyRawBytes = monthlyRawBytes,
                recordType = "daily"
            )
            getDao().upsert(dailyRecord)
            DebugLogger.logApi(TAG, "Daily saved: $dailyKey daily=$dailyRawBytes monthly=$monthlyRawBytes")

            // 开启每小时记录时，额外保存每小时记录（累计值）
            if (hourlyEnabled) {
                val hourlyKey = synchronized(hourlyDateFormat) { hourlyDateFormat.format(now) }
                val hourlyRecord = TrafficRecord(
                    dateKey = hourlyKey,
                    timestamp = ts,
                    dailyRawBytes = dailyRawBytes,
                    monthlyRawBytes = monthlyRawBytes,
                    recordType = "hourly"
                )
                getDao().upsert(hourlyRecord)
                DebugLogger.logApi(TAG, "Hourly saved: $hourlyKey daily=$dailyRawBytes monthly=$monthlyRawBytes")
            }

            // 超过上限时清理最旧的记录
            cleanupIfNeeded()
        }
    }

    /** 查询最近 N 天的每日记录（按日期降序） */
    suspend fun getRecent(context: Context, limit: Int = 31): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getRecent(limit) }

    /** 查询最近 N 个月的流量记录（按月聚合，按月份降序） */
    suspend fun getMonthly(context: Context, limit: Int = 12): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getMonthlyRecords(limit) }

    /** 分页查询每日记录 */
    suspend fun getRecentPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getRecentPaged(limit, offset) }

    /** 分页查询每小时记录 */
    suspend fun getHourlyPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getHourlyPaged(limit, offset) }

    /** 每小时记录总数 */
    suspend fun getHourlyCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getHourlyCount() }

    /** 分页查询月聚合记录 */
    suspend fun getMonthlyPaged(context: Context, limit: Int, offset: Int): List<TrafficRecord> =
        withContext(Dispatchers.IO) { getDao().getMonthlyPaged(limit, offset) }

    /** 获取去重的月数 */
    suspend fun getMonthlyCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getMonthlyCount() }

    /** 观察最近 N 天的每日记录（Flow） */
    fun observeRecent(context: Context, limit: Int = 31): Flow<List<TrafficRecord>> =
        getDao().observeRecent(limit)

    /** 获取某一天的记录 */
    suspend fun getByDateKey(context: Context, dateKey: String): TrafficRecord? =
        withContext(Dispatchers.IO) { getDao().getByDateKey(dateKey) }

    /** 获取每日记录总数 */
    suspend fun getCount(context: Context): Int =
        withContext(Dispatchers.IO) { getDao().getDailyCount() }

    /** 删除超过上限的旧记录 */
    private suspend fun cleanupIfNeeded() {
        try {
            val count = getDao().getCount()
            if (count > MAX_RECORDS) {
                val oldestKey = getDao().getOldestDateKey() ?: return
                // 删除最旧的 1/3 记录（批量清理，避免每次插入都触发）
                val dateKeys = getDao().getRecent(count)
                val cutoffIndex = count - MAX_RECORDS
                if (cutoffIndex > 0 && cutoffIndex < dateKeys.size) {
                    val cutoffKey = dateKeys[dateKeys.size - cutoffIndex].dateKey
                    getDao().deleteOlderThan(cutoffKey)
                    DebugLogger.logApi(TAG, "Cleanup: removed records older than $cutoffKey")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Cleanup failed: ${e.message}")
        }
    }
}
