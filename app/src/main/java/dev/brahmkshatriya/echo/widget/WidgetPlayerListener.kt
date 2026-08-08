package dev.brahmkshatriya.echo.widget

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.brahmkshatriya.echo.playback.PlayerCommands.imageCommand
import dev.brahmkshatriya.echo.utils.Serializer.getParcel

class WidgetPlayerListener(
    private val update: (Bitmap?) -> Unit
) : Player.Listener {

    var controller: MediaController? = null
    private var result: ListenableFuture<SessionResult>? = null
    private var image: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getImage() {
        result?.cancel(true)
        val future = controller?.sendCustomCommand(imageCommand, Bundle.EMPTY)
        future?.addListener({
            // Custom command future completes on the session's IO worker thread (PlayerCallback uses
            // scope.future -> Dispatchers.IO), so MediaController calls inside update(image) must run on
            // the main thread. Cancelled or raced futures throw CancellationException on get() — swallow it.
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                image = result.extras.getParcel<Bitmap>("image")
            }
            mainHandler.post { update(image) }
        }, MoreExecutors.directExecutor())
        result = future
    }

    fun removed() {
        result?.cancel(true)
        result = null
        controller?.removeListener(this)
        controller = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        getImage()
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        getImage()
        update(image)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        update(image)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        update(image)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        update(image)
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        update(image)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        update(image)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        update(image)
    }
}