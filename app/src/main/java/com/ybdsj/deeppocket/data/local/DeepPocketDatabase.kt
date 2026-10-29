package com.ybdsj.deeppocket.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ybdsj.deeppocket.data.local.dao.ApiKeyDao
import com.ybdsj.deeppocket.data.local.dao.BalanceSampleDao
import com.ybdsj.deeppocket.data.local.entity.ApiKeyEntity
import com.ybdsj.deeppocket.data.local.entity.BalanceSampleEntity

/**
 * 本地数据库。
 *
 * 只存 API Key 的**密文**与余额采样，不存任何明文凭证。
 * 数据库文件位于应用私有目录，且 AndroidManifest 里 allowBackup=false，
 * 不会随云备份或换机迁移把密文（甚至明文）带走。
 *
 * 版本历史：
 * - v1：api_keys
 * - v2：新增 balance_samples（余额采样，供"今日用量"估算使用）
 */
@Database(
    entities = [ApiKeyEntity::class, BalanceSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class DeepPocketDatabase : RoomDatabase() {

    abstract fun apiKeyDao(): ApiKeyDao

    abstract fun balanceSampleDao(): BalanceSampleDao

    companion object {
        private const val DATABASE_NAME = "deep_pocket.db"

        /**
         * v1 → v2：只新增一张空表，不触碰已有 api_keys 数据。
         * 用户在设备上已经保存了真实 Key，绝不能用破坏性迁移把它清掉。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `balance_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`currency` TEXT NOT NULL, " +
                        "`total` TEXT NOT NULL, " +
                        "`granted` TEXT NOT NULL, " +
                        "`topped_up` TEXT NOT NULL, " +
                        "`sampled_at` INTEGER NOT NULL, " +
                        "`api_key_id` INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_balance_samples_currency_sampled_at` " +
                        "ON `balance_samples` (`currency`, `sampled_at`)",
                )
            }
        }

        @Volatile
        private var instance: DeepPocketDatabase? = null

        fun getInstance(context: Context): DeepPocketDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DeepPocketDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
