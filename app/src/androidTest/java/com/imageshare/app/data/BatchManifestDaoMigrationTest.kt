package com.imageshare.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
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

    private companion object {
        const val TEST_DB = "batch-manifest-migration"
    }
}
