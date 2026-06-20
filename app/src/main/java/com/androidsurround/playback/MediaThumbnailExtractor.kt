package com.androidsurround.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaThumbnailExtractor(private val context: Context) {

    private val cache = LruCache<String, Bitmap>(64)

    suspend fun getAlbumArt(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache.get(key)?.let { return@withContext it }
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val data = retriever.embeddedPicture
            retriever.release()
            if (data != null) {
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bmp != null) cache.put(key, bmp)
                return@withContext bmp
            }
        } catch (_: Exception) { }
        null
    }

    suspend fun getAlbumArt(path: String): Bitmap? = getAlbumArt(Uri.fromFile(File(path)))

    fun getAlbumArtBlocking(uri: Uri): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val data = retriever.embeddedPicture
            retriever.release()
            if (data != null) {
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bmp != null) cache.put(key, bmp)
                return bmp
            }
        } catch (_: Exception) { }
        return null
    }
}
