package com.imageshare.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.imageshare.core.io.SourceItem

@Entity(tableName = "batch_manifest", primaryKeys = ["jobId", "sourceIndex"])
data class BatchManifestEntity(
    val jobId: String,
    val sourceIndex: Int,
    val sourceUriString: String,
    val state: String,
    val storedFilePath: String?,
    val outputMimeType: String?,
    val errorCode: String?,
    val updatedAt: Long,
    val sourceMimeType: String? = null,
    val sourceDisplayName: String? = null,
    val sourceSizeBytes: Long? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    /** Immutable worker configuration, written before a background hand-off is scheduled. */
    val workerPresetId: String? = null,
    val workerPresetJson: String? = null,
)

fun BatchManifestEntity.toSourceItem(): SourceItem = SourceItem(
    uri = Uri.parse(sourceUriString),
    mimeType = sourceMimeType,
    displayName = sourceDisplayName,
    sizeBytes = sourceSizeBytes,
    width = sourceWidth,
    height = sourceHeight,
)

enum class BatchItemError { Decode, Resize, Encode, MetadataApply, Store, Unknown }

@Dao
interface BatchManifestDao {
    @Query("SELECT * FROM batch_manifest WHERE jobId = :jobId ORDER BY sourceIndex")
    suspend fun forJob(jobId: String): List<BatchManifestEntity>

    @Query("SELECT jobId FROM batch_manifest GROUP BY jobId ORDER BY MAX(updatedAt) DESC")
    suspend fun jobIds(): List<String>

    @Query("SELECT jobId FROM batch_manifest WHERE state = 'Pending' GROUP BY jobId ORDER BY MAX(updatedAt) DESC")
    suspend fun pendingJobIds(): List<String>

    @Query("SELECT jobId FROM batch_manifest WHERE state = 'Queued' GROUP BY jobId ORDER BY MIN(updatedAt) ASC")
    suspend fun queuedJobIds(): List<String>

    @Query("SELECT jobId FROM batch_manifest WHERE state IN ('Pending', 'Queued') GROUP BY jobId ORDER BY MAX(updatedAt) DESC")
    suspend fun protectedJobIds(): List<String>

    @Query("DELETE FROM batch_manifest WHERE jobId = :jobId AND state = 'Queued'")
    suspend fun deleteQueuedJob(jobId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<BatchManifestEntity>)

    @Update
    suspend fun update(entry: BatchManifestEntity)

    @Query("DELETE FROM batch_manifest WHERE jobId = :jobId")
    suspend fun deleteJob(jobId: String)

    @Query(
        "DELETE FROM batch_manifest WHERE jobId IN (" +
            "SELECT jobId FROM batch_manifest GROUP BY jobId HAVING " +
            "MAX(updatedAt) < :cutoffMillis AND " +
            "SUM(CASE WHEN state = 'Pending' THEN 1 ELSE 0 END) = 0 AND " +
            "(SUM(CASE WHEN state = 'Queued' THEN 1 ELSE 0 END) = 0 OR " +
            "SUM(CASE WHEN state = 'Queued' THEN 1 ELSE 0 END) = COUNT(*))" +
            ")",
    )
    suspend fun purgeOlderThan(cutoffMillis: Long): Int
}

@Database(entities = [BatchManifestEntity::class], version = 4, exportSchema = true)
abstract class ImageShareDatabase : RoomDatabase() {
    abstract fun batchManifestDao(): BatchManifestDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `batch_manifest_new` (
                        `jobId` TEXT NOT NULL,
                        `sourceIndex` INTEGER NOT NULL,
                        `sourceUriString` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `storedFilePath` TEXT,
                        `outputMimeType` TEXT,
                        `errorCode` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`, `sourceIndex`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `batch_manifest_new` (
                        `jobId`,
                        `sourceIndex`,
                        `sourceUriString`,
                        `state`,
                        `storedFilePath`,
                        `outputMimeType`,
                        `errorCode`,
                        `updatedAt`
                    )
                    SELECT
                        `jobId`,
                        `sourceIndex`,
                        `sourceUriString`,
                        `state`,
                        `storedFilePath`,
                        `outputMimeType`,
                        NULL,
                        `updatedAt`
                    FROM `batch_manifest`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `batch_manifest`")
                db.execSQL("ALTER TABLE `batch_manifest_new` RENAME TO `batch_manifest`")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `sourceMimeType` TEXT")
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `sourceDisplayName` TEXT")
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `sourceSizeBytes` INTEGER")
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `sourceWidth` INTEGER")
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `sourceHeight` INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `workerPresetId` TEXT")
                db.execSQL("ALTER TABLE `batch_manifest` ADD COLUMN `workerPresetJson` TEXT")
            }
        }

        fun create(context: Context): ImageShareDatabase = Room.databaseBuilder(
            context,
            ImageShareDatabase::class.java,
            "imageshare.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
