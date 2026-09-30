package com.example.data.drive

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.utils.BackupHelper
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DriveBackupFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdTime: String,
    val timestamp: Long
) {
    val formattedDate: String
        get() = try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = parser.parse(createdTime.take(19)) ?: Date(timestamp)
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(timestamp))
        }

    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
        }
}

class GoogleDriveBackupManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveBackup"
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val OAUTH_SCOPE_STRING = "oauth2:$DRIVE_SCOPE"
        const val FOLDER_NAME = "Amar Dukan Backups"
        private const val PREFS_NAME = "google_drive_backup_prefs"
        private const val KEY_DRIVE_ACCOUNT_EMAIL = "drive_account_email"
        private const val KEY_LAST_BACKUP_TIME = "last_drive_backup_time"
        private const val KEY_FOLDER_ID = "drive_backup_folder_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastKnownToken: String? = null

    var savedAccountEmail: String?
        get() = prefs.getString(KEY_DRIVE_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_DRIVE_ACCOUNT_EMAIL, value).apply()

    var lastBackupTime: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_TIME, value).apply()

    private var cachedFolderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_ID, value).apply()

    /**
     * Checks if the device is actively connected to the Internet.
     */
    fun isDeviceOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Lists all registered Google accounts available on the device.
     */
    fun getAvailableGoogleAccounts(): List<String> {
        return try {
            val accountManager = android.accounts.AccountManager.get(context)
            val accounts = accountManager.getAccountsByType("com.google")
            accounts.mapNotNull { it.name }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list device Google accounts: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds the best matching Account on device.
     */
    private fun resolveGoogleAccount(preferredEmail: String): Account {
        val trimmed = preferredEmail.trim()
        val available = getAvailableGoogleAccounts()
        val matchedName = available.find { it.equals(trimmed, ignoreCase = true) }
            ?: available.firstOrNull()
            ?: trimmed
        return Account(matchedName, "com.google")
    }

    /**
     * Obtains an OAuth2 Bearer token for Google Drive API.
     * Automatically invalidates expired tokens if forceFresh is requested.
     */
    suspend fun getAccessToken(
        accountEmail: String,
        forceFresh: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetEmail = accountEmail.trim()
        if (targetEmail.isBlank()) {
            return@withContext Result.failure(Exception("Please provide a valid Google Account email address."))
        }

        val isOnline = isDeviceOnline()
        if (!isOnline) {
            return@withContext Result.failure(
                Exception("No Internet Connection: Please check your Wi-Fi or mobile data connection and try again.")
            )
        }

        val account = resolveGoogleAccount(targetEmail)

        if (forceFresh) {
            lastKnownToken?.let { token ->
                try {
                    GoogleAuthUtil.clearToken(context, token)
                } catch (_: Throwable) {
                    try { GoogleAuthUtil.invalidateToken(context, token) } catch (_: Throwable) {}
                }
            }
        }

        try {
            val token = GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE_STRING)
            lastKnownToken = token
            savedAccountEmail = account.name
            Result.success(token)
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "User consent needed for Google Drive: ${e.message}")
            Result.failure(e)
        } catch (e: java.io.IOException) {
            val msg = e.message ?: ""
            Log.w(TAG, "IO error obtaining Drive token for ${account.name}: $msg")

            // If forceFresh was not set, try clearing cached token and retry once
            if (!forceFresh) {
                lastKnownToken?.let {
                    try { GoogleAuthUtil.clearToken(context, it) } catch (_: Throwable) {}
                }
                try {
                    val retryToken = GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE_STRING)
                    lastKnownToken = retryToken
                    savedAccountEmail = account.name
                    return@withContext Result.success(retryToken)
                } catch (retryEx: Exception) {
                    Log.w(TAG, "Token retry after clear also failed: ${retryEx.message}")
                }
            }

            if (msg.contains("AccountNotPresent", ignoreCase = true)) {
                Result.failure(
                    Exception("Google Account '${account.name}' is not signed into this Android device. Please add it in Android Settings -> Accounts, or use Local JSON Backup.")
                )
            } else if (!isDeviceOnline()) {
                Result.failure(
                    Exception("No Internet Connection: Please check your Wi-Fi or mobile data connection and try again.")
                )
            } else {
                Result.failure(
                    Exception("Google Auth Service Issue: Connected to internet, but Google Play Services could not authenticate '${account.name}'. ($msg). Please ensure Google Play Services is updated or re-select your account.")
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Google Play Services broker security exception: ${e.message}")
            Result.failure(Exception("Google Account service is unavailable on this device. Please verify Google Play Services permissions."))
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring Drive token: ${e.message}", e)
            val msg = e.message ?: ""
            if (msg.contains("AccountNotPresent", ignoreCase = true)) {
                Result.failure(
                    Exception("Google Account '${account.name}' is not registered on this device. Please sign in via Device Settings or use Local JSON Backup.")
                )
            } else {
                Result.failure(Exception("Google Drive authentication failed: ${e.localizedMessage ?: msg}"))
            }
        }
    }

    /**
     * Invalidates a cached token to force a fresh one on next attempt.
     */
    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (_: Throwable) {
            try {
                GoogleAuthUtil.invalidateToken(context, token)
            } catch (e: Throwable) {
                Log.w(TAG, "Token invalidation note: ${e.message}")
            }
        }
        if (lastKnownToken == token) {
            lastKnownToken = null
        }
    }

    /**
     * Executes a Google Drive operation with automatic token refresh on HTTP 401 / expired credentials.
     */
    suspend fun <T> executeWithTokenRefresh(
        accountEmail: String,
        operation: suspend (token: String) -> Result<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        val initialTokenRes = getAccessToken(accountEmail)
        if (initialTokenRes.isFailure) {
            return@withContext Result.failure(
                initialTokenRes.exceptionOrNull() ?: Exception("Failed to obtain Google Drive access token")
            )
        }

        val token = initialTokenRes.getOrThrow()
        val firstAttempt = operation(token)
        if (firstAttempt.isSuccess) {
            return@withContext firstAttempt
        }

        val error = firstAttempt.exceptionOrNull()
        val errorMsg = error?.message ?: ""
        val isAuthError = errorMsg.contains("401", ignoreCase = true) ||
                errorMsg.contains("invalid authentication credentials", ignoreCase = true) ||
                errorMsg.contains("session expired", ignoreCase = true) ||
                errorMsg.contains("UNAUTHORIZED", ignoreCase = true)

        if (isAuthError) {
            Log.i(TAG, "Google Drive token rejected/expired. Automatically refreshing token and retrying...")
            invalidateToken(token)
            val freshTokenRes = getAccessToken(accountEmail, forceFresh = true)
            if (freshTokenRes.isSuccess) {
                val freshToken = freshTokenRes.getOrThrow()
                Log.i(TAG, "Retrying Drive operation with newly refreshed token...")
                return@withContext operation(freshToken)
            }
        }

        return@withContext firstAttempt
    }

    private fun parseDriveHttpError(code: Int, body: String): String {
        val parsedMsg = try {
            val root = JSONObject(body)
            val errObj = root.optJSONObject("error")
            errObj?.optString("message", "") ?: ""
        } catch (_: Exception) {
            ""
        }
        val detail = if (parsedMsg.isNotBlank()) ": $parsedMsg" else if (body.isNotBlank()) ": ${body.take(160)}" else ""
        return when (code) {
            401 -> "Google Drive authentication rejected (HTTP 401$detail). Token refreshed, please retry."
            403 -> "Google Drive permission denied (HTTP 403$detail). Ensure Drive API is enabled and account has sufficient storage."
            404 -> "Google Drive resource not found (HTTP 404$detail)."
            429 -> "Google Drive rate limit exceeded (HTTP 429). Please wait a moment and try again."
            in 500..599 -> "Google Drive server temporary error (HTTP $code$detail). Please try again shortly."
            else -> "Google Drive request failed (HTTP $code$detail)"
        }
    }

    /**
     * Finds or creates the dedicated "Amar Dukan Backups" folder in user's Google Drive.
     */
    suspend fun getOrCreateBackupFolder(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cachedId = cachedFolderId
            if (!cachedId.isNullOrBlank()) {
                val verifyRequest = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$cachedId?fields=id,name,trashed")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(verifyRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val obj = JSONObject(body)
                        if (!obj.optBoolean("trashed", false)) {
                            return@withContext Result.success(cachedId)
                        }
                    } else if (response.code == 401) {
                        return@withContext Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                    }
                }
            }

            // Search for existing folder
            val query = "mimeType='application/vnd.google-apps.folder' and name='$FOLDER_NAME' and trashed=false"
            val searchUrl = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&spaces=drive&fields=files(id,name)"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(searchRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val filesArray = root.optJSONArray("files") ?: JSONArray()
                    if (filesArray.length() > 0) {
                        val folderId = filesArray.getJSONObject(0).getString("id")
                        cachedFolderId = folderId
                        return@withContext Result.success(folderId)
                    }
                } else if (response.code == 401) {
                    return@withContext Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                } else {
                    return@withContext Result.failure(Exception(parseDriveHttpError(response.code, body)))
                }
            }

            // Create new folder
            val createJson = JSONObject().apply {
                put("name", FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }
            val createBody = createJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
            val createRequest = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .addHeader("Authorization", "Bearer $token")
                .post(createBody)
                .build()

            httpClient.newCall(createRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val createdObj = JSONObject(body)
                    val newFolderId = createdObj.getString("id")
                    cachedFolderId = newFolderId
                    Result.success(newFolderId)
                } else if (response.code == 401) {
                    Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                } else {
                    Result.failure(Exception(parseDriveHttpError(response.code, body)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateBackupFolder error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads a complete snapshot JSON file to the dedicated Google Drive folder.
     */
    suspend fun backupNow(
        token: String,
        labelNote: String? = null
    ): Result<DriveBackupFile> = withContext(Dispatchers.IO) {
        try {
            // 1. Get or create folder
            val folderRes = getOrCreateBackupFolder(token)
            if (folderRes.isFailure) {
                return@withContext Result.failure(folderRes.exceptionOrNull() ?: Exception("Cannot access Drive backup folder"))
            }
            val folderId = folderRes.getOrThrow()

            // 2. Export database JSON string
            val jsonRes = BackupHelper.exportDataToJsonString(context)
            if (jsonRes.isFailure) {
                return@withContext Result.failure(jsonRes.exceptionOrNull() ?: Exception("Failed to generate backup JSON"))
            }
            val jsonPayload = jsonRes.getOrThrow()

            val timestampStr = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val cleanLabel = labelNote?.trim()?.replace(Regex("[^a-zA-Z0-9_-]"), "_")?.take(30)
            val fileName = if (!cleanLabel.isNullOrBlank()) {
                "amar_dukan_backup_${timestampStr}_$cleanLabel.json"
            } else {
                "amar_dukan_backup_$timestampStr.json"
            }

            // 3. Upload multipart/related file according to Google Drive v3 REST API spec
            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("mimeType", "application/json")
                put("parents", JSONArray().put(folderId))
                if (!labelNote.isNullOrBlank()) {
                    put("description", "Amar Dukan Backup: $labelNote")
                }
            }

            val multipartRelatedType = "multipart/related".toMediaType()
            val metadataPart = metadataJson.toString()
                .toRequestBody("application/json; charset=UTF-8".toMediaType())
            val mediaPart = jsonPayload
                .toRequestBody("application/json; charset=UTF-8".toMediaType())

            val multipartBody = MultipartBody.Builder()
                .setType(multipartRelatedType)
                .addPart(metadataPart)
                .addPart(mediaPart)
                .build()

            val uploadRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,createdTime,modifiedTime")
                .addHeader("Authorization", "Bearer $token")
                .post(multipartBody)
                .build()

            httpClient.newCall(uploadRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val fileObj = JSONObject(responseBody)
                    val backupFile = DriveBackupFile(
                        id = fileObj.getString("id"),
                        name = fileObj.optString("name", fileName),
                        sizeBytes = fileObj.optLong("size", jsonPayload.toByteArray().size.toLong()),
                        createdTime = fileObj.optString("createdTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())),
                        timestamp = System.currentTimeMillis()
                    )
                    lastBackupTime = System.currentTimeMillis()
                    Result.success(backupFile)
                } else if (response.code == 401) {
                    Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                } else {
                    Result.failure(Exception(parseDriveHttpError(response.code, responseBody)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "backupNow error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lists all backup files present in the dedicated Drive folder.
     */
    suspend fun listBackups(token: String): Result<List<DriveBackupFile>> = withContext(Dispatchers.IO) {
        try {
            val folderRes = getOrCreateBackupFolder(token)
            if (folderRes.isFailure) {
                return@withContext Result.failure(folderRes.exceptionOrNull() ?: Exception("Cannot access Drive folder"))
            }
            val folderId = folderRes.getOrThrow()

            val query = "'$folderId' in parents and trashed=false"
            val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&orderBy=createdTime desc&fields=files(id,name,size,createdTime,modifiedTime)"

            val listRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(listRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(responseBody)
                    val filesArray = root.optJSONArray("files") ?: JSONArray()
                    val resultList = mutableListOf<DriveBackupFile>()
                    for (i in 0 until filesArray.length()) {
                        val f = filesArray.getJSONObject(i)
                        val name = f.optString("name", "backup.json")
                        val id = f.getString("id")
                        val size = f.optLong("size", 0L)
                        val created = f.optString("createdTime", "")
                        resultList.add(
                            DriveBackupFile(
                                id = id,
                                name = name,
                                sizeBytes = size,
                                createdTime = created,
                                timestamp = parseTimestampFromIso(created)
                            )
                        )
                    }
                    Result.success(resultList)
                } else if (response.code == 401) {
                    Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                } else {
                    Result.failure(Exception(parseDriveHttpError(response.code, responseBody)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listBackups error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a selected backup JSON file from Google Drive and restores database tables.
     */
    suspend fun restoreFromBackup(token: String, fileId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(downloadRequest).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                response.close()
                if (response.code == 401) {
                    return@withContext Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                }
                return@withContext Result.failure(Exception(parseDriveHttpError(response.code, errorBody)))
            }

            val bodyStream: InputStream = response.body?.byteStream()
                ?: return@withContext Result.failure(Exception("Empty response body from Google Drive"))

            val restoreResult = BackupHelper.importDataFromJson(context, bodyStream)
            response.close()
            restoreResult
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackup error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes a specific backup file from Google Drive.
     */
    suspend fun deleteBackup(token: String, fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val deleteUrl = "https://www.googleapis.com/drive/v3/files/$fileId"
            val deleteRequest = Request.Builder()
                .url(deleteUrl)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            httpClient.newCall(deleteRequest).execute().use { response ->
                if (response.isSuccessful || response.code == 204) {
                    Result.success(true)
                } else if (response.code == 401) {
                    Result.failure(Exception("Google Drive session expired (HTTP 401)"))
                } else {
                    val body = response.body?.string() ?: ""
                    Result.failure(Exception(parseDriveHttpError(response.code, body)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteBackup error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseTimestampFromIso(iso: String): Long {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = parser.parse(iso.take(19))
            date?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}

