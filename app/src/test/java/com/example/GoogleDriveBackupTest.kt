package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.DriveBackupFile
import com.example.data.drive.GoogleDriveBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleDriveBackupTest {

    @Test
    fun testDriveBackupFileFormatting() {
        val backupFile = DriveBackupFile(
            id = "file_12345",
            name = "amar_dukan_backup_2026-08-23_100000.json",
            sizeBytes = 2048576, // ~1.95 MB -> 2.0 MB
            createdTime = "2026-08-23T10:00:00Z",
            timestamp = 1787488800000L
        )

        assertEquals("file_12345", backupFile.id)
        assertEquals("amar_dukan_backup_2026-08-23_100000.json", backupFile.name)
        assertTrue(backupFile.formattedSize.contains("MB"))
        assertNotNull(backupFile.formattedDate)
    }

    @Test
    fun testGoogleDriveManagerInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GoogleDriveBackupManager(context)
        assertNotNull(manager)
        assertEquals("Amar Dukan Backups", GoogleDriveBackupManager.FOLDER_NAME)
        assertEquals("https://www.googleapis.com/auth/drive.file", GoogleDriveBackupManager.DRIVE_SCOPE)
    }

    @Test
    fun testEmptyAccountValidation() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GoogleDriveBackupManager(context)
        val result = manager.getAccessToken("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("valid Google Account email") == true)
    }
}
