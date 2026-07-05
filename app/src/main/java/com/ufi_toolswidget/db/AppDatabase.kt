package com.ufi_toolswidget.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ufi_toolswidget.util.DebugLogger
import com.ufi_toolswidget.util.SPUtil
import org.json.JSONArray

/**
 * Room 数据库。
 *
 * 首次创建时自动从旧 SharedPreferences 迁移历史警报数据。
 */
@Database(entities = [AlertRecord::class, TrafficRecord::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao
    abstract fun trafficDao(): TrafficDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_TO_2 = Migration(1, 2) { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `traffic_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateKey` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `dailyRawBytes` INTEGER NOT NULL, `monthlyRawBytes` INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_traffic_records_dateKey` ON `traffic_records` (`dateKey`)")
        }

        private val MIGRATION_2_TO_3 = Migration(2, 3) { db ->
            // 添加 recordType 列，已有记录默认标记为 "daily"
            db.execSQL("ALTER TABLE `traffic_records` ADD COLUMN `recordType` TEXT NOT NULL DEFAULT 'daily'")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_traffic_records_recordType` ON `traffic_records` (`recordType`)")
            // 将已有的每小时格式记录（dateKey 长度 > 10）标记为 "hourly"
            db.execSQL("UPDATE `traffic_records` SET `recordType` = 'hourly' WHERE length(`dateKey`) > 10")
        }

        private val MIGRATION_3_TO_4 = Migration(3, 4) { db ->
            // 为 alerts 表添加索引，优化分页查询和过滤排序性能
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alerts_timestamp` ON `alerts` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alerts_type_isRead_timestamp` ON `alerts` (`type`, `isRead`, `timestamp`)")
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ufitools.db"
                )
                    .addCallback(MigrationCallback(context.applicationContext))
                    .addMigrations(MIGRATION_1_TO_2, MIGRATION_2_TO_3, MIGRATION_3_TO_4)
                    .build()
                    .also { instance = it }
            }
        }
    }

    /**
     * 数据库首次创建回调：从旧 SharedPreferences 迁移历史警报。
     */

    private class MigrationCallback(private val appContext: Context) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // 直接使用传入的 db 实例进行迁移，避免调用 getInstance() 导致递归重入
            migrateFromSp(db)
        }

        private fun migrateFromSp(db: SupportSQLiteDatabase) {
            try {
                val sp = SPUtil.getSp(appContext)
                val json = sp.getString("alert_history_json", "") ?: return
                if (json.isEmpty()) return

                val arr = JSONArray(json)
                if (arr.length() == 0) return

                // 使用事务保护，确保全部插入成功或全部回滚
                db.beginTransaction()
                try {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        // 直接通过 SupportSQLiteDatabase 插入，绕过 DAO 层（此时 Room 尚未就绪）
                        val values = android.content.ContentValues().apply {
                            put("type", obj.optString("type", ""))
                            put("title", obj.optString("title", ""))
                            put("message", obj.optString("message", ""))
                            put("timestamp", obj.optLong("timestamp"))
                            put("isRead", if (obj.optBoolean("isRead", false)) 1 else 0)
                        }
                        db.insert("alerts", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
                    }
                    // 标记迁移完成（在事务内设置，确保原子性）
                    sp.edit().putBoolean("alert_history_migrated_to_room", true).apply()
                    db.setTransactionSuccessful()
                    DebugLogger.logApi("AppDatabase", "Migrated ${arr.length()} alerts from SP to Room")
                } finally {
                    db.endTransaction()
                }
            } catch (e: Exception) {
                DebugLogger.e("AppDatabase", "SP to Room migration failed: ${e.message}")
            }
        }
    }
}
