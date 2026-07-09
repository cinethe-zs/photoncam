package com.photoncam.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Saves the processed JPEG to the public Pictures/PhotonCam directory
     * via MediaStore (API 29+) or legacy file copy.
     *
     * @return The [Uri] of the saved image.
     */
    suspend fun saveToGallery(file: File): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val filename = "PhotonCam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhotonCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IOException("MediaStore insert returned null")

                try {
                    // A null stream must be a hard failure — silently skipping the write
                    // would leave a 0-byte entry counted as a successful save.
                    val out = resolver.openOutputStream(uri)
                        ?: throw IOException("openOutputStream returned null for $uri")
                    out.use { file.inputStream().use { input -> input.copyTo(it) } }

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

                    uri
                } catch (t: Throwable) {
                    // Remove the half-written pending row so it doesn't linger invisibly
                    // (IS_PENDING=1) in the gallery, then rethrow → outer runCatching → failure.
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PhotonCam",
                ).apply { mkdirs() }

                val dest = File(picturesDir, filename)
                file.copyTo(dest, overwrite = true)

                Uri.fromFile(dest)
            }
        }
    }

    /**
     * Returns an [Intent] that opens the system share sheet for the given [Uri].
     */
    fun buildShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Queries MediaStore for the most recently saved PhotonCam photo.
     * Returns null if no photos exist yet. Used to populate the VIEW button on startup.
     */
    fun getLatestPhotoUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%PhotonCam%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "PhotonCam",
            )
            picturesDir.listFiles()
                ?.filter { it.extension.lowercase() == "jpg" }
                ?.maxByOrNull { it.lastModified() }
                ?.let { Uri.fromFile(it) }
        }
    }

    /**
     * Converts a private cache [File] to a content:// [Uri] via FileProvider
     * so it can be shared with other apps.
     */
    fun toShareableUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
