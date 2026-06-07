package com.example.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object GalleryHelper {
    
    private const val TAG = "GalleryHelper"

    /**
     * Direct query standard Android MediaStore to lookup the single latest photographed asset (Image or Video)
     */
    fun getLastSavedMediaUri(context: Context): Uri? {
        val targets = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 1"

        var latestUri: Uri? = null
        var latestTimestamp: Long = -1

        for (baseUri in targets) {
            try {
                context.contentResolver.query(baseUri, projection, null, null, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        
                        val id = cursor.getLong(idCol)
                        val timestamp = cursor.getLong(dateCol)
                        
                        if (timestamp > latestTimestamp) {
                            latestTimestamp = timestamp
                            latestUri = ContentUris.withAppendedId(baseUri, id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing MediaStore query for Uri $baseUri", e)
            }
        }
        return latestUri
    }

    /**
     * Starts an Intent targeting native local photo viewer platforms
     */
    fun openGalleryAtUri(context: Context, uri: Uri?) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (uri != null) {
                    val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                    setDataAndType(uri, mimeType)
                } else {
                    // System baseline view
                    setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching system photo viewer directly, trying generic ACTION_VIEW category.", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Absolute failure of both main and fallback gallery intent targets.", ex)
            }
        }
    }
}
