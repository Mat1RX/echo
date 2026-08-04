package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.loadStreamableMedia
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.downloaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isFullyCached
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.state
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isForced
import dev.brahmkshatriya.echo.playback.MediaItemUtils.metadataKey
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.stateNullable
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.playableString
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

@UnstableApi
class StreamableLoader(
    private val app: App,
    private val cache: SimpleCache,
    private val extensionListFlow: StateFlow<List<MusicExtension>>,
    private val downloadFlow: StateFlow<List<Downloader.Info>>,
    private val healthMonitor: HealthMonitor,
) {
    private suspend fun tryRecover(mediaItem: MediaItem): Pair<MediaItem, Result<Streamable.Media.Server>>? {
        val state = mediaItem.stateNullable ?: return null
        val track = state.item
        val extId = mediaItem.extensionId
        
        // 1. Get cached metadata to have the full streamables list
        val cachedMetadata = runCatching { Cached.getMedia<Track>(app, extId, track.id).getOrThrow() }.getOrNull()
        val streamables = (cachedMetadata?.item ?: track).servers
        
        val serverIdx = mediaItem.serverIndex
        
        // 2. If user explicitly selected a quality, try it FIRST if it's cached.
        if (mediaItem.isForced) {
            val sourceIdx = mediaItem.sourceIndex
            val key = metadataKey(track.id, serverIdx, sourceIdx, extId)
            val cachedUri = app.context.getFromCache<String>(key, "player")
            if (cache.isFullyCached(key)) {
                return mediaItem to Result.success(Streamable.Media.Server(listOf((cachedUri ?: "").toSource()), false))
            }
            return null
        }

        // 3. Auto mode: search for ANY cached quality index
        for (i in streamables.indices) {
            val key = metadataKey(track.id, i, 0, extId)
            val cachedUri = app.context.getFromCache<String>(key, "player")
            if (cache.isFullyCached(key) || (app.networkFlow.value == NetworkConnection.NotConnected && cachedUri != null)) {
                val loadedMediaItem = if (mediaItem.isLoaded) mediaItem else {
                    val loadedState = cachedMetadata ?: return null
                    MediaItemUtils.buildLoaded(app, downloadFlow.value, mediaItem, loadedState)
                }
                val newMediaItem = MediaItemUtils.buildServer(loadedMediaItem, i)
                return newMediaItem to Result.success(Streamable.Media.Server(listOf((cachedUri ?: "").toSource()), false))
            }
        }
        return null
    }

    suspend fun load(mediaItem: MediaItem) = withContext(Dispatchers.IO) {
        tryRecover(mediaItem)?.let { return@withContext it }
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(30_000) {
                extensionListFlow.first { it.isNotEmpty() }
                val new = if (mediaItem.isLoaded) mediaItem
                else MediaItemUtils.buildLoaded(
                    app, downloadFlow.value, mediaItem, loadTrack(mediaItem)
                )

                val server = async { loadServer(new) }
                val background =
                    async { if (new.backgroundIndex < 0) null else loadBackground(new).getOrNull() }
                val subtitle = async { if (new.subtitleIndex < 0) null else loadSubtitle(new).getOrNull() }

                MediaItemUtils.buildWithBackgroundAndSubtitle(
                    new, background.await(), subtitle.await()
                ) to server.await()
            }
        } catch (e: TimeoutCancellationException) {
            healthMonitor.report(
                HealthMonitor.ExtensionResolutionTimeout(mediaItem.extensionId, System.currentTimeMillis() - startMs),
                HealthMonitor.Scope.PERSISTENT, 60 * 60 * 1000L
            )
            throw e
        }
    }

    private suspend fun <T> withClient(
        mediaItem: MediaItem,
        block: suspend (Extension<*>) -> Result<T>
    ): Result<T> {
        val extension = extensionListFlow.getExtensionOrThrow(mediaItem.extensionId)
        return block(extension)
    }

    private suspend fun loadTrack(item: MediaItem): MediaState.Loaded<Track> {
        val stub = item.state.item
        Log.d("EchoPlayback", "loadTrack stub: id=${stub.id} servers=${stub.servers.map { it.id }} extras=${stub.extras}")
        val track = withClient(item) {
            Cached.loadMedia(app, it, item.state)
        }
        val result = track.getOrThrow()
        Log.d("EchoPlayback", "loadTrack result: id=${result.item.id} servers=${result.item.servers.map { it.id }}")
        return result
    }

    private suspend fun loadServer(mediaItem: MediaItem): Result<Streamable.Media.Server> {
        val downloaded = mediaItem.downloaded
        val servers = mediaItem.track.servers
        val index = mediaItem.serverIndex
        val key = metadataKey(mediaItem.track.id, index, 0, mediaItem.extensionId)

        if (cache.isFullyCached(key)) {
            return Result.success(Streamable.Media.Server(listOf("".toSource()), false))
        }

        if (!downloaded.isNullOrEmpty() && servers.size == index) {
            return runCatching {
                Streamable.Media.Server(
                    downloaded.map { Uri.fromFile(File(it)).toString().toSource() },
                    true
                )
            }
        }
        return withClient(mediaItem) {
            runCatching {
                val isPlayable = mediaItem.track.playableString(app.context)
                if (isPlayable != null) throw TrackUnavailableException(isPlayable)
                val streamable = servers.getOrNull(index)
                    ?: throw TrackUnavailableException("Server not found")
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Server
            }.recoverCatching { throwable ->
                // Search for ANY cached index to avoid failing here.
                // StreamableResolver will do the actual recovery using the disk cache key.
                for (i in servers.indices) {
                    val key = metadataKey(mediaItem.track.id, index, i, mediaItem.extensionId)
                    if (app.context.getFromCache<String>(key, "player") != null) {
                        return@recoverCatching Streamable.Media.Server(listOf("".toSource()), false)
                    }
                }
                throw throwable
            }
        }
    }

    private suspend fun loadBackground(mediaItem: MediaItem): Result<Streamable.Media.Background> {
        val streams = mediaItem.track.backgrounds
        val index = mediaItem.backgroundIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Background
            }
        }
    }

    private suspend fun loadSubtitle(mediaItem: MediaItem): Result<Streamable.Media.Subtitle> {
        val streams = mediaItem.track.subtitles
        val index = mediaItem.subtitleIndex
        val streamable = streams[index]
        return withClient(mediaItem) {
            runCatching {
                loadStreamableMedia(
                    app, it, mediaItem.track, streamable
                ).getOrThrow() as Streamable.Media.Subtitle
            }
        }
    }
}