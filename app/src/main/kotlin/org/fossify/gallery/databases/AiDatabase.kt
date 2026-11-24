package org.fossify.gallery.databases

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 文字描述表。只放"钱买来的文字"，不含向量。
 */
@Entity(tableName = "ai_index")
data class AiIndex(
    @PrimaryKey var uri: String = "",
    var path: String = "",
    var description: String = "",
    var ocrText: String = "",
    var model: String = "",
    var sizeBytes: Long = 0L,
    var updatedAt: Long = 0L
)

/**
 * 向量表。与描述表通过 uri 关联，可单独补算、单独导出、单独重建。
 */
@Entity(tableName = "ai_vector")
data class AiVector(
    @PrimaryKey var uri: String = "",
    var vector: ByteArray = ByteArray(0),
    var dim: Int = 0,
    var model: String = "",
    var updatedAt: Long = 0L
)

@Dao
interface AiIndexDao {
    @Query("SELECT * FROM ai_index")
    fun getAll(): List<AiIndex>

    @Query("SELECT * FROM ai_index WHERE uri = :uri LIMIT 1")
    fun getByUri(uri: String): AiIndex?

    @Query("SELECT path FROM ai_index WHERE description LIKE :pattern OR ocrText LIKE :pattern")
    fun searchPathsByKeyword(pattern: String): List<String>

    @Query("SELECT COUNT(*) FROM ai_index")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: AiIndex)

    @Query("DELETE FROM ai_index")
    fun clear()

    @Query("SELECT * FROM ai_index WHERE uri NOT IN (SELECT uri FROM ai_vector) AND description != ''")
    fun getRowsMissingVector(): List<AiIndex>
}

@Dao
interface AiVectorDao {
    @Query("SELECT * FROM ai_vector")
    fun getAll(): List<AiVector>

    @Query("SELECT * FROM ai_vector WHERE uri = :uri LIMIT 1")
    fun getByUri(uri: String): AiVector?

    @Query("SELECT COUNT(*) FROM ai_vector")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(item: AiVector)

    @Query("DELETE FROM ai_vector")
    fun clear()
}

@Database(entities = [AiIndex::class, AiVector::class], version = 2, exportSchema = false)
abstract class AiDatabase : RoomDatabase() {
    abstract fun aiIndexDao(): AiIndexDao

    abstract fun aiVectorDao(): AiVectorDao

    companion object {
        private var db: AiDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1) 先建向量表，把旧表里的向量搬过来
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_vector` (`uri` TEXT NOT NULL, `vector` BLOB NOT NULL, `dim` INTEGER NOT NULL, `model` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO `ai_vector` (`uri`, `vector`, `dim`, `model`, `updatedAt`) " +
                        "SELECT `uri`, `vector`, `dim`, `model`, `updatedAt` FROM `ai_index` WHERE `dim` > 0"
                )
                // 2) 重建描述表，去掉向量列
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_index_new` (`uri` TEXT NOT NULL, `path` TEXT NOT NULL, `description` TEXT NOT NULL, `ocrText` TEXT NOT NULL, `model` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))"
                )
                database.execSQL(
                    "INSERT OR REPLACE INTO `ai_index_new` (`uri`, `path`, `description`, `ocrText`, `model`, `sizeBytes`, `updatedAt`) " +
                        "SELECT `uri`, `path`, `description`, `ocrText`, `model`, `sizeBytes`, `updatedAt` FROM `ai_index`"
                )
                database.execSQL("DROP TABLE `ai_index`")
                database.execSQL("ALTER TABLE `ai_index_new` RENAME TO `ai_index`")
            }
        }

        fun getInstance(context: Context): AiDatabase {
            if (db == null) {
                synchronized(AiDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, AiDatabase::class.java, "ai_index.db")
                            .addMigrations(MIGRATION_1_2)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
