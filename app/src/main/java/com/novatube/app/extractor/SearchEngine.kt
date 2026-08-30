package com.novatube.app.extractor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.novatube.app.data.model.MediaInfo
import com.novatube.app.data.model.SearchKind
import com.novatube.app.data.model.SearchResult
import com.yausername.youtubedl.YoutubeDL
import com.yausername.youtubedl.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchEngine(private val gson: Gson = Gson()) {

    suspend fun search(query: String, max: Int = 25): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<SearchResult>()
        results += runYtSearch("ytsearch$max:$query", SearchKind.VIDEO, "YouTube")
        results += runYtSearch("scsearch$max:$query", SearchKind.AUDIO, "SoundCloud")
        return@withContext results.distinctBy { it.url }
    }

    suspend fun suggestions(query: String, max: Int = 8): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val request = YoutubeDLRequest("ytsearch$max:$query").apply {
            addOption("--skip-download")
            addOption("--flat-playlist")
            addOption("--print", "%(title)s")
            addOption("--no-warnings")
            addOption("--no-playlist")
        }
        return@withContext runCatching {
            YoutubeDL.getInstance().execute(request).out
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(max)
        }.getOrDefault(emptyList())
    }

    private fun runYtSearch(query: String, kind: SearchKind, platform: String): List<SearchResult> {
        return runCatching {
            val request = YoutubeDLRequest(query).apply {
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("-J")
                addOption("--no-warnings")
                addOption("--no-playlist")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val root = gson.fromJson(response.out, JsonObject::class.java) ?: return emptyList()
            val entries = root.getAsJsonArray("entries") ?: return emptyList()
            entries.mapNotNull { el ->
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = obj.get("title")?.asString ?: return@mapNotNull null
                val url = obj.get("url")?.asString ?: obj.get("webpage_url")?.asString ?: "https://www.youtube.com/watch?v=$id"
                val uploader = obj.get("uploader")?.asString
                val duration = obj.get("duration")?.asLong
                val thumb = obj.get("thumbnails")?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                val viewCount = obj.get("view_count")?.asLong
                SearchResult(
                    id = id,
                    title = title,
                    uploader = uploader,
                    duration = duration,
                    thumbnail = thumb,
                    url = url,
                    kind = kind,
                    platform = platform,
                    viewCount = viewCount
                )
            }
        }.getOrDefault(emptyList())
    }
}