package com.hanzidictionary.app.photo

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.hanzidictionary.core.PhotoRef
import com.hanzidictionary.core.PhotoSource

/**
 * [PhotoSource] backed by MediaStore — the most recent images in the device's photo
 * library, newest first. PhotoRef.id is the image content URI (so the recognizer can
 * open it); timestamp is DATE_ADDED (seconds) normalized to millis.
 */
class MediaStorePhotoSource(private val context: Context) : PhotoSource {
    override fun recentPhotos(limit: Int): List<PhotoRef> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val out = ArrayList<PhotoRef>(limit)
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idCol)
                val dateAddedSeconds = cursor.getLong(dateCol)
                val uri = ContentUris.withAppendedId(collection, id)
                out.add(PhotoRef(id = uri.toString(), timestampMillis = dateAddedSeconds * 1000))
            }
        }
        return out
    }
}
