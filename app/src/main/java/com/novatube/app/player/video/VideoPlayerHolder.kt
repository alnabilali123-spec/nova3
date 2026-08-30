package com.novatube.app.player.video

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.novatube.app.data.prefs.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

class VideoPlayerHolder(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val preferences: PreferencesRepository
) {
    private val renderersFactory = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters().setForceLowestBitrate(false).build()
    }

    private val dataSourceFactory = DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) NovaTube/1.0")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
    )

    private val mediaSourceFactory = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(dataSourceFactory)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setTrackSelector(trackSelector)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            playWhenReady = true
        }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(playbackState = playbackState)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(errorMessage = error.errorCodeName + ": " + (error.message ?: ""))
        }
    }

    init { player.addListener(listener) }

    fun setSource(uri: String, title: String? = null, headers: Map<String, String> = emptyMap()) {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri)
            .build()
        player.setMediaItem(item)
        player.prepare()
        if (title != null) _state.value = _state.value.copy(title = title)
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2.0f))
        _state.value = _state.value.copy(playbackSpeed = speed)
    }
    fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
        _state.value = _state.value.copy(volume = player.volume)
    }

    fun setPosition(positionMs: Long, durationMs: Long) {
        _state.value = _state.value.copy(position = positionMs, duration = if (durationMs > 0) durationMs else _state.value.duration)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}

data class PlayerUiState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val position: Long = 0,
    val duration: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val errorMessage: String? = null
)