package com.imageshare.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchManifestDaoMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ImageShareDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationFrom1To2ReplacesErrorMessageWithErrorCode() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO batch_manifest (
                    jobId,
                    sourceIndex,
                    sourceUriString,
                    state,
                    storedFilePath,
                    outputMimeType,
                    errorMessage,
                    updatedAt
                ) VALUES ('job', 0, 'content://images/0', 'Failed', NULL, NULL, '/private/path.jpg', 1000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, ImageShareDatabase.MIGRATION_1_2)
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`batch_manifest`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        db.query("SELECT `errorCode` FROM `batch_manifest` WHERE `jobId` = 'job' AND `sourceIndex` = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("errorCode")))
        }
        db.close()

        assertTrue(columns.contains("errorCode"))
        assertFalse(columns.contains("errorMessage"))
    }

    @Test
    fun migrationFrom2To3AddsNullableSourceMetadata() {
        helper.createDatabase(TEST_DB_V2, 2).apply {
            execSQL(
                """
                INSERT INTO batch_manifest (
                    jobId,
                    sourceIndex,
                    sourceUriString,
                    state,
                    storedFilePath,
                    outputMimeType,
                    errorCode,
                    updatedAt
                ) VALUES ('job', 0, 'content://images/0', 'Pending', NULL, NULL, NULL, 1000)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_V2,
            3,
            true,
            ImageShareDatabase.MIGRATION_2_3,
        )
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`batch_manifest`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        db.query(
            """
            SELECT
                `sourceMimeType`,
                `sourceDisplayName`,
                `sourceSizeBytes`,
                `sourceWidth`,
                `sourceHeight`
            FROM `batch_manifest`
            WHERE `jobId` = 'job' AND `sourceIndex` = 0
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("sourceMimeType")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("sourceDisplayName")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("sourceSizeBytes")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("sourceWidth")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("sourceHeight")))
        }
        db.close()

        assertTrue(columns.contains("sourceMimeType"))
        assertTrue(columns.contains("sourceDisplayName"))
        assertTrue(columns.contains("sourceSizeBytes"))
        assertTrue(columns.contains("sourceWidth"))
        assertTrue(columns.contains("sourceHeight"))
    }

    @Test
    fun migrationFrom3To4AddsNullableWorkerConfiguration() {
        helper.createDatabase(TEST_DB_V3, 3).apply {
            execSQL(
                """
                INSERT INTO batch_manifest (
                    jobId,
                    sourceIndex,
                    sourceUriString,
                    state,
                    storedFilePath,
                    outputMimeType,
                    errorCode,
                    updatedAt,
                    sourceMimeType,
                    sourceDisplayName,
                    sourceSizeBytes,
                    sourceWidth,
                    sourceHeight
                ) VALUES (
                    'job',
                    0,
                    'content://images/0',
                    'Queued',
                    '/cache/input.jpg',
                    'image/jpeg',
                    NULL,
                    1000,
                    'image/jpeg',
                    'input.jpg',
                    42,
                    640,
                    480
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_V3,
            4,
            true,
            ImageShareDatabase.MIGRATION_3_4,
        )
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`batch_manifest`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        db.query(
            """
            SELECT
                `sourceMimeType`,
                `sourceDisplayName`,
                `sourceSizeBytes`,
                `sourceWidth`,
                `sourceHeight`,
                `workerPresetId`,
                `workerPresetJson`
            FROM `batch_manifest`
            WHERE `jobId` = 'job' AND `sourceIndex` = 0
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("image/jpeg", cursor.getString(cursor.getColumnIndexOrThrow("sourceMimeType")))
            assertEquals("input.jpg", cursor.getString(cursor.getColumnIndexOrThrow("sourceDisplayName")))
            assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("sourceSizeBytes")))
            assertEquals(640, cursor.getInt(cursor.getColumnIndexOrThrow("sourceWidth")))
            assertEquals(480, cursor.getInt(cursor.getColumnIndexOrThrow("sourceHeight")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("workerPresetId")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("workerPresetJson")))
        }
        db.close()

        assertTrue(columns.contains("workerPresetId"))
        assertTrue(columns.contains("workerPresetJson"))
    }

    private companion object {
        const val TEST_DB = "batch-manifest-migration"
        const val TEST_DB_V2 = "batch-manifest-migration-v2"
        const val TEST_DB_V3 = "batch-manifest-migration-v3"
    }
}
