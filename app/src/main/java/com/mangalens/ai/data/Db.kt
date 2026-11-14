package com.mangalens.ai.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey val id: String,
    val source: String,
    val translated: String,
    val srcLang: String,
    val tgtLang: String,
    val createdAt: Long
)

@Dao
interface TranslationDao {
    @Query("SELECT * FROM translations WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: TranslationEntity)

    @Query("SELECT * FROM translations ORDER BY createdAt DESC LIMIT 300")
    fun history(): Flow<List<TranslationEntity>>

    @Query("DELETE FROM translations")
    suspend fun clear()

    @Query("DELETE FROM translations WHERE createdAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [TranslationEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TranslationDao

    companion object {
        fun get(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "mangalens.db").build()
    }
}
