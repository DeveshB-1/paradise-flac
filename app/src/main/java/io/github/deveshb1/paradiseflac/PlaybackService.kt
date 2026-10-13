package io.github.deveshb1.paradiseflac

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session extras the UI reads to show the signal path. */
object PathInfo {
    const val CODEC = "codec"
    const val SAMPLE_RATE = "sampleRate"
    const val BITS = "bits"
    const val OUT_SAMPLE_RATE = "outSampleRate"
    const val DEVICE_NAME = "deviceName"
    const val DEVICE_KIND = "deviceKind"
    const val BIT_PERFECT = "bitPerfect"
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var retryJob: Job? = null
    private var pausedAt = 0L

    private var inputFormat: Format? = null
    private var outSampleRate = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.any { AudioPath.kindOf(it.type) == AudioPath.Kind.USB }) AudioPath.applyBitPerfect(this@PlaybackService)
            publishPath()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = publishPath()
    }

    override fun onCreate() {
        super.onCreate()
        AudioPath.applyBitPerfect(this)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Station.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId("radioparadise-main-flac")
                .setUri(Station.STREAM_URL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(Station.NAME)
                        .setStation(Station.NAME)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                )
                .build(),
        )
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, null)
        startPolling()
        publishPath()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = SystemClock.elapsedRealtime()
                retryJob?.cancel()
                return
            }
            // It's live radio: after a real pause, jump back to "now" instead of playing stale buffer.
            if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 10_000) {
                player.seekToDefaultPosition()
            }
            pausedAt = 0L
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Network blips: keep retrying with backoff while the user still wants audio.
            retryJob?.cancel()
            retryJob = scope.launch {
                var wait = 2_000L
                while (isActive && player.playWhenReady && player.playbackState == Player.STATE_IDLE) {
                    delay(wait)
                    player.prepare()
                    wait = (wait * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            inputFormat = format
            publishPath()
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            outSampleRate = audioTrackConfig.sampleRate
            publishPath()
        }
    }

    private fun publishPath() {
        val device = AudioPath.currentOutput(this)
        val format = inputFormat
        val extras = Bundle().apply {
            putString(PathInfo.CODEC, format?.sampleMimeType?.substringAfter('/')?.uppercase() ?: "FLAC")
            putInt(PathInfo.SAMPLE_RATE, format?.sampleRate?.takeIf { it > 0 } ?: 0)
            putInt(PathInfo.BITS, bitsOf(format))
            putInt(PathInfo.OUT_SAMPLE_RATE, outSampleRate)
            putString(PathInfo.DEVICE_NAME, device?.productName?.toString().orEmpty())
            putString(PathInfo.DEVICE_KIND, device?.let { AudioPath.kindOf(it.type).name } ?: AudioPath.Kind.OTHER.name)
            putBoolean(PathInfo.BIT_PERFECT, AudioPath.bitPerfectActive(this@PlaybackService))
        }
        session.setSessionExtras(extras)
    }

    private fun bitsOf(format: Format?): Int = when (format?.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var lastKey: Pair<String, String>? = null
            while (isActive) {
                val track = withContext(Dispatchers.IO) { Station.nowPlaying() }
                if (track != null && (track.artist to track.title) != lastKey) {
                    lastKey = track.artist to track.title
                    showTrack(track)
                }
                // Re-check just after the song is due to change; fall back to a slow poll.
                val waitSec = track?.let { (it.secondsLeft + 3).coerceIn(5, 120) } ?: 15
                delay(waitSec * 1_000L)
            }
        }
    }

    private fun showTrack(track: Station.Track) {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setReleaseYear(track.year)
            .setStation(Station.NAME)
            .setArtworkUri(track.coverUrl?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        // Same URI, new metadata: ExoPlayer updates it in place without interrupting audio.
        val current = player.getMediaItemAt(0)
        player.replaceMediaItem(0, current.buildUpon().setMediaMetadata(metadata).build())
    }
}
