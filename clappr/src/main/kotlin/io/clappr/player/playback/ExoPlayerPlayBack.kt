package io.clappr.player.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.SimpleExoPlayerView
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import io.clappr.player.base.ErrorCode
import io.clappr.player.base.ErrorInfo
import io.clappr.player.base.Event
import io.clappr.player.base.Options
import io.clappr.player.components.Playback
import io.clappr.player.components.PlaybackSupportInterface
import io.clappr.player.periodicTimer.PeriodicTimeElapsedHandler
import java.io.IOException

open class ExoPlayerPlayback(source: String, mimeType: String? = null, options: Options = Options()) : Playback(source, mimeType, options) {
    companion object : PlaybackSupportInterface {
        override fun supportsSource(source: String, mimeType: String?): Boolean {
            val uri = Uri.parse(source)
            val type = Util.inferContentType(uri.lastPathSegment)
            return type == C.TYPE_SS || type == C.TYPE_HLS || type == C.TYPE_DASH || type == C.TYPE_OTHER
        }

        override val name: String = "exoplayerplayback"
    }

    private val ONE_SECOND_IN_MILLIS: Int = 1000

    private val mainHandler = Handler()
    private val bandwidthMeter = DefaultBandwidthMeter()
    private val eventsListener = ExoplayerEventsListener()
    private var player: SimpleExoPlayer? = null
    private var currentState = State.NONE
    private var trackSelector: DefaultTrackSelector? = null
    private val timeElapsedHandler = PeriodicTimeElapsedHandler(200L, { checkPeriodicUpdates() })
    private var lastBufferPercentageSent = 0.0
    private var lastPositionSent = 0.0

    private val bufferPercentage: Double
        get() = player?.bufferedPercentage?.toDouble() ?: 0.0

    private val playerView: SimpleExoPlayerView
        get() = view as SimpleExoPlayerView

    override val viewClass: Class<*>
        get() = SimpleExoPlayerView::class.java

    override val duration: Double
        get() = player?.duration?.let { it.toDouble() / ONE_SECOND_IN_MILLIS } ?: Double.NaN

    override val position: Double
        get() = player?.currentPosition?.let { it.toDouble() / ONE_SECOND_IN_MILLIS } ?: Double.NaN

    override val state: State
        get() = currentState

    override val canPlay: Boolean
        get() = currentState == State.PAUSED ||
                currentState == State.IDLE ||
                (currentState == State.STALLED && player?.playWhenReady == false)

    override val canPause: Boolean
        get() = currentState == State.PLAYING ||
                currentState == State.STALLED ||
                currentState == State.IDLE

    override val canSeek: Boolean
        get() = duration != 0.0 && currentState != State.ERROR

    init {
        playerView.setUseController(false)
    }

    override fun play(): Boolean {
        if (player == null) setupPlayer()

        if (!canPlay && player != null) return false

        trigger(Event.WILL_PLAY)
        player?.playWhenReady = true
        return true
    }

    override fun pause(): Boolean {
        if (!canPause) return false

        trigger(Event.WILL_PAUSE)
        player?.playWhenReady = false
        return true
    }

    override fun stop(): Boolean {
        trigger(Event.WILL_STOP)
        timeElapsedHandler.cancel()
        player?.stop()
        player?.release()
        player = null
        trigger(Event.DID_STOP)
        return true
    }

    override fun seek(seconds: Int): Boolean {
        if (!canSeek) return false

        trigger(Event.WILL_SEEK)
        player?.seekTo((seconds * 1000).toLong())
        trigger(Event.DID_SEEK)
        triggerPositionUpdateEvent()
        return true
    }

    override fun load(source: String, mimeType: String?): Boolean {
        trigger(Event.WILL_CHANGE_SOURCE)
        this.source = source
        this.mimeType = mimeType
        stop()
        trigger(Event.DID_CHANGE_SOURCE)
        play()
        return true
    }

    private fun mediaSource(uri: Uri): MediaSource {
        val type = Util.inferContentType(uri.lastPathSegment)
        val dataSourceFactory = DefaultDataSourceFactory(context, "agent", bandwidthMeter)

        when (type) {
            C.TYPE_DASH -> return DashMediaSource(uri, dataSourceFactory, DefaultDashChunkSource.Factory(dataSourceFactory), mainHandler, eventsListener)
            C.TYPE_SS -> return SsMediaSource(uri, dataSourceFactory, DefaultSsChunkSource.Factory(dataSourceFactory), mainHandler, eventsListener)
            C.TYPE_HLS -> return HlsMediaSource(uri, dataSourceFactory, mainHandler, eventsListener)
            C.TYPE_OTHER -> return ExtractorMediaSource(uri, dataSourceFactory, DefaultExtractorsFactory(), mainHandler, eventsListener)
            else -> throw IllegalStateException("Unsupported type: " + type)
        }
    }

    private fun setupPlayer() {
        val videoTrackSelectionFactory = AdaptiveVideoTrackSelection.Factory(bandwidthMeter)
        trackSelector = DefaultTrackSelector(mainHandler, videoTrackSelectionFactory)
        player = ExoPlayerFactory.newSimpleInstance(context, trackSelector, DefaultLoadControl())
        player?.playWhenReady = false
        player?.addListener(eventsListener)
        playerView.player = player
        player?.prepare(mediaSource(Uri.parse(source)))
    }

    private fun checkPeriodicUpdates() {
        if (bufferPercentage != lastBufferPercentageSent) triggerBufferUpdateEvent()
        if (position != lastPositionSent) triggerPositionUpdateEvent()
    }

    private fun triggerBufferUpdateEvent() {
        val bundle = Bundle()
        val currentBufferPercentage = bufferPercentage

        bundle.putDouble("percentage", currentBufferPercentage)
        trigger(Event.BUFFER_UPDATE.value, bundle)
        lastBufferPercentageSent = currentBufferPercentage
    }

    private fun triggerPositionUpdateEvent() {
        val bundle = Bundle()
        val currentPosition = position
        val percentage = if (duration != 0.0) (currentPosition / duration) * 100 else 0.0

        bundle.putDouble("percentage", percentage)
        bundle.putDouble("time", currentPosition)
        trigger(Event.POSITION_UPDATE.value, bundle)
        lastPositionSent = currentPosition
    }

    private fun updateState(playWhenReady: Boolean, playbackState: Int) {
        when (playbackState) {
            ExoPlayer.STATE_IDLE -> handleExoplayerIdleState()
            ExoPlayer.STATE_ENDED -> handleExoplayerEndedState()
            ExoPlayer.STATE_BUFFERING -> handleExoplayerBufferingState()
            ExoPlayer.STATE_READY -> handleExoplayerReadyState(playWhenReady)
        }
    }

    private fun handleExoplayerReadyState(playWhenReady: Boolean) {
        if (playWhenReady) {
            currentState = State.PLAYING
            trigger(Event.PLAYING)
            timeElapsedHandler.start()
        } else {
            currentState = State.PAUSED
            trigger(Event.DID_PAUSE)
        }
    }

    private fun handleExoplayerBufferingState() {
        if (currentState != State.NONE) {
            currentState = State.STALLED
            trigger(Event.STALLED)
        }
    }

    private fun handleExoplayerEndedState() {
        currentState = State.IDLE
        trigger(Event.DID_COMPLETE)
        stop()
    }

    private fun handleExoplayerIdleState() {
        timeElapsedHandler.cancel()
        currentState = State.NONE
    }

    private fun trigger(event: Event) {
        trigger(event.value)
    }

    private fun handleError(error: Exception?) {
        if (currentState != State.ERROR) {
            timeElapsedHandler.cancel()
            currentState = State.ERROR
            triggerErrorEvent(error)
        }
    }

    private fun triggerErrorEvent(error: Exception?) {
        val bundle = Bundle()
        val message = error?.message ?: "Exoplayer Error"
        bundle.putParcelable(Event.ERROR.value, ErrorInfo(message, ErrorCode.PLAYBACK_ERROR))
        trigger(Event.ERROR.value, bundle)
    }

    inner class ExoplayerEventsListener() : AdaptiveMediaSourceEventListener, ExtractorMediaSource.EventListener, ExoPlayer.EventListener {
        override fun onLoadError(error: IOException?) {
            handleError(error)
        }

        override fun onLoadError(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long, error: IOException?, wasCanceled: Boolean) {
            handleError(error)
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            updateState(playWhenReady, playbackState)
        }

        override fun onPlayerError(error: ExoPlaybackException?) {
            handleError(error)
        }

        override fun onLoadingChanged(isLoading: Boolean) {
            if (isLoading && currentState == State.NONE) {
                currentState = State.IDLE
                trigger(Event.READY.value)
            }
        }

        override fun onPositionDiscontinuity() {
        }

        override fun onTimelineChanged(timeline: Timeline?, manifest: Any?) {
        }

        override fun onLoadStarted(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long) {
        }

        override fun onDownstreamFormatChanged(trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaTimeMs: Long) {
        }

        override fun onUpstreamDiscarded(trackType: Int, mediaStartTimeMs: Long, mediaEndTimeMs: Long) {
        }

        override fun onLoadCanceled(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long) {
        }

        override fun onLoadCompleted(dataSpec: DataSpec?, dataType: Int, trackType: Int, trackFormat: Format?, trackSelectionReason: Int, trackSelectionData: Any?, mediaStartTimeMs: Long, mediaEndTimeMs: Long, elapsedRealtimeMs: Long, loadDurationMs: Long, bytesLoaded: Long) {
        }
    }
}