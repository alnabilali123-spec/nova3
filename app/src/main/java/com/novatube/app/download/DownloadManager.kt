package com.novatube.app.download

import android.content.Context
import android.util.Log
import com.novatube.app.NovaTubeApp
import com.novatube.app.data.entity.DownloadEntity
import com.novatube.app.data.entity.DownloadStatus
import com.novatube.app.data.model.RequestedDownload
import com.novatube.app.util.FileUtils
import com.yausername.youtubedl.YoutubeDL
import com.yausername.youtubedl.YoutubeDLException
import com.yausername.youtubedl.YoutubeDLRequest
import com.yausername.youtubedl.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

class DownloadManager(private val context: Context) {

    interface ProgressListener {
        fun onProgress(line: ProgressEvent)
        fun onCompleted(file: File)
        fun onError(message: String, cause: Throwable?)
    }

    data class ProgressEvent(
        val percent: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speed: Long,
        val eta: Long,
        val line: String
    )

    suspend fun run(
        request: RequestedDownload,
        listener: ProgressListener,
        shouldCancel: () -> Boolean = { false }
    ) = withContext(Dispatchers.IO) {
        val targetDir = FileUtils.downloadDir(context)
        targetDir.mkdirs()
        val outputTemplate = "${targetDir.absolutePath}/${FileUtils.sanitizeFileName(request.fileName)}.%(ext)s"

        val dlRequest = YoutubeDLRequest(request.url).apply {
            addOption("-o", outputTemplate)
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-part")
            addOption("--newline")
            addOption("--no-color")
            addOption("--no-warnings")
            addOption("--no-progress")
            if (request.isAudioOnly) {
                addOption("-x")
                addOption("--audio-format", request.audioFormat.ifBlank { "mp3" })
                addOption("--audio-quality", "0")
            } else {
                addOption("-f", request.formatId)
            }
        }

        Log.i(TAG, "Starting yt-dlp for ${request.url} (format=${request.formatId}, audio=${request.isAudioOnly})")

        try {
            val response: YoutubeDLResponse = YoutubeDL.getInstance().execute(dlRequest) { progress ->
                val event = parseProgress(progress)
                if (event != null) listener.onProgress(event)
            }
            Log.i(TAG, "yt-dlp response: code=${response.exitCode} err=${response.err.take(200)}")
            if (shouldCancel()) {
                listener.onError("Cancelled", null)
                return@withContext
            }
            val produced = findProducedFile(targetDir, request)
            if (produced != null && produced.exists() && produced.length() > 0) {
                listener.onCompleted(produced)
            } else {
                listener.onError("yt-dlp did not produce an output file", null)
            }
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "yt-dlp exception", e)
            listener.onError(e.message ?: "yt-dlp failed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Download exception", e)
            listener.onError(e.message ?: "Unknown error", e)
        }
    }

    private fun findProducedFile(dir: File, request: RequestedDownload): File? {
        val sanitized = FileUtils.sanitizeFileName(request.fileName)
        val candidates = dir.listFiles { f ->
            val name = f.nameWithoutExtension
            name.equals(sanitized, ignoreCase = true)
        } ?: emptyArray()
        if (candidates.isNotEmpty()) return candidates.maxByOrNull { it.lastModified() }
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun parseProgress(line: String): ProgressEvent? {
        if (line.isBlank()) return null
        val matcher = PROGRESS_PATTERN.matcher(line)
        if (!matcher.find()) return null
        val percent = matcher.group(1)?.toFloatOrNull() ?: return null
        val total = matcher.group(2)?.parseSize() ?: 0L
        val speed = matcher.group(3)?.parseSpeed() ?: 0L
        val eta = matcher.group(4)?.toLongOrNull() ?: 0L
        return ProgressEvent(
            percent = percent,
            downloadedBytes = (percent / 100f * total).toLong(),
            totalBytes = total,
            speed = speed,
            eta = eta,
            line = line
        )
    }

    companion object {
        private const val TAG = "DownloadManager"

        private val PROGRESS_PATTERN: Pattern =
            Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+([0-9.]+\\s*\\S+)(?:\\s+at\\s+([0-9.]+\\s*\\S+))?(?:\\s+ETA\\s+([0-9:]+))?")

        private fun String.parseSize(): Long? {
            val parts = trim().split(" ")
            if (parts.size != 2) return null
            val value = parts[0].toDoubleOrNull() ?: return null
            return when (parts[1].lowercase()) {
                "b" -> value.toLong()
                "kb", "kib" -> (value * 1024).toLong()
                "mb", "mib" -> (value * 1024 * 1024).toLong()
                "gb", "gib" -> (value * 1024 * 1024 * 1024).toLong()
                else -> null
            }
        }

        private fun String.parseSpeed(): Long? = this.parseSize()
    }
}

suspend fun persistStatus(
    app: NovaTubeApp,
    entity: DownloadEntity,
    status: DownloadStatus,
    errorMessage: String? = null
) {
    when (status) {
        DownloadStatus.RUNNING -> app.downloadRepository.markRunning(entity.id)
        DownloadStatus.QUEUED -> app.downloadRepository.markQueued(entity.id)
        DownloadStatus.PAUSED -> app.downloadRepository.markPaused(entity.id)
        DownloadStatus.CANCELLED -> app.downloadRepository.markCancelled(entity.id)
        DownloadStatus.COMPLETED -> {}
        DownloadStatus.FAILED -> app.downloadRepository.markFailed(entity.id, errorMessage)
    }
}