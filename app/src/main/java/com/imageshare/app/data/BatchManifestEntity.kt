package com.imageshare.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity(tableName = "batch_manifest", primaryKeys = ["jobId", "sourceIndex"])
data class BatchManifestEntity(
    val jobId: String,
    val sourceIndex: Int,
    val sourceUriString: String,
    val state: String,
    val storedFilePath: String?,
    val outputMimeType: String?,
    val errorMessage: String?,
    val updatedAt: Long,
)

@Dao
interface BatchManifestDao {
    @Query("SELECT * FROM batch_manifest WHERE jobId = :jobId ORDER BY sourceIndex")
    suspend fun forJob(jobId: String): List<BatchManifestEntity>

    @Query("SELECT DISTINCT jobId FROM batch_manifest ORDER BY updatedAt DESC")
    suspend fun jobIds(): List<String>

    @Query("SELECT DISTINCT jobId FROM batch_manifest WHERE state = 'Pending' ORDER BY updatedAt DESC")
    suspend fun pendingJobIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<BatchManifestEntity>)

    @Update
    suspend fun update(entry: BatchManifestEntity)

    @Query("DELETE FROM batch_manifest WHERE jobId = :jobId")
    suspend fun deleteJob(jobId: String)

    @Query("DELETE FROM batch_manifest WHERE updatedAt < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long): Int
}

@Database(entities = [BatchManifestEntity::class], version = 1, exportSchema = true)
abstract class ImageShareDatabase : RoomDatabase() {
    abstract fun batchManifestDao(): BatchManifestDao

    companion object {
        fun create(context: Context): ImageShareDatabase = Room.databaseBuilder(
            context,
            ImageShareDatabase::class.java,
            "imageshare.db",
        ).build()
    }
}
