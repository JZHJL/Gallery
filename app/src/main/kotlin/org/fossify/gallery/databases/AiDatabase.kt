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

@Entity(tableName = "ai_index")
data class AiIndex(
    @PrimaryKey var uri: String = "",
    var path: String = "",
    var description: String = "",
    var ocrText: String = "",
    var vector: ByteArray = ByteArray(0),
    var dim: Int = 0,
    var model: String = "",
    var sizeBytes: Long = 0L,
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
}

@Database(entities = [AiIndex::class], version = 1, exportSchema = false)
abstract class AiDatabase : RoomDatabase() {
    abstract fun aiIndexDao(): AiIndexDao

    companion object {
        private var db: AiDatabase? = null

        fun getInstance(context: Context): AiDatabase {
            if (db == null) {
                synchronized(AiDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, AiDatabase::class.java, "ai_index.db")
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
