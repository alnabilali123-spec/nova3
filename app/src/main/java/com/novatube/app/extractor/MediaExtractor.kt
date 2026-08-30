package com.novatube.app.extractor

import android.util.Log
import com.google.gson.Gson
import com.novatube.app.data.model.MediaInfo
import com.yausername.youtubedl.YoutubeDL
import com.yausername.youtubedl.YoutubeDLException
import com.yausername.youtubedl.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaExtractor(private val gson: Gson = Gson()) {

    suspend fun extract(url: String): MediaInfo = withContext(Dispatchers.IO) {
        Log.i(TAG, "extract() $url")
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-warnings")
            addOption("--no-playlist")
            addOption("--no-color")
            addOption("--no-progress")
        }
        val response = try {
            YoutubeDL.getInstance().execute(request)
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "yt-dlp failed for $url", e)
            throw MediaExtractionException(e.message ?: "yt-dlp error", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected extractor failure", e)
            throw MediaExtractionException(e.message ?: "Unknown error", e)
        }
        val json = response.out
        try {
            gson.fromJson(json, MediaInfo::class.java)
                ?: throw MediaExtractionException("Empty metadata response")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse yt-dlp JSON", e)
            throw MediaExtractionException("Could not parse metadata", e)
        }
    }

    companion object {
        private const val TAG = "MediaExtractor"
    }
}

class MediaExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)