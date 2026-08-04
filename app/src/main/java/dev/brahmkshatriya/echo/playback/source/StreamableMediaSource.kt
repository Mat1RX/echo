package dev.brahmkshatriya.echo.playback.source

import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.backgroundIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isFullyCached
import dev.brahmkshatriya.echo.playback.MediaItemUtils.metadataKey
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.sourceIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.subtitleIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.select
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CACHE_IN_RAM_ONLY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.PRELOAD_TRACK_CACHE
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource.Companion.uri
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.playableString
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

@UnstableApi
class StreamableMediaSource(
    private var mediaItem: MediaItem,
    private val app: App,
    private val scope: CoroutineScope,
    private val state: PlayerState,
    private val loader: StreamableLoader,
    private val cache: SimpleCache,
    private val dataSourceFactory: DataSource.Factory,
    private val cacheFactories: Factories,
    private val factories: Factories,
    private val changeFlow: MutableSharedFlow<Pair<MediaItem, MediaItem>>,
) : CompositeMediaSource<Nothing>() {

    private var error: Throwable? = null
    override fun maybeThrowSourceInfoRefreshError() {
        error?.let { throw IOException(it) }
        super.maybeThrowSourceInfoRefreshError()
    }

    private fun Streamable.Source.isLocal(): Boolean {
        val scheme = uri.scheme
        if (scheme == "file" || scheme == "content") return true
        if (scheme == null) {
            val path = uri.path
            return !path.isNullOrEmpty() && path.startsWith("/")
        }
        return false
    }

    private fun getFactory(mediaItem: MediaItem, index: Int, source: Streamable.Source): Factories {
        val ramOnly = app.context.getSettings().getBoolean(CACHE_IN_RAM_ONLY, false)
        if (source.isLive || source.isLocal()) return factories
        
        val key = metadataKey(mediaItem.track.id, mediaItem.serverIndex, index, mediaItem.extensionId)
        val isFullyCached = cache.isFullyCached(key)
        
        // Check for offline partial cache
        val isOffline = app.networkFlow.value == NetworkConnection.NotConnected
        val cachedUri = app.context.getFromCache<String>(key, "player")
        val hasCacheRecord = cachedUri != null && !cachedUri.startsWith("raw:")
        val forceCache = isFullyCached || (isOffline && hasCacheRecord)
        
        // Fallback to reading from the disk cache if it's already cached or if we're offline,
        // even if 'Cache in RAM only' is enabled, to save bandwidth and prevent offline playback failures.
        return if (ramOnly && !forceCache) factories else cacheFactories
    }

    @Volatile private var released = false
    private var loadJob: Job? = null
    private lateinit var actualSource: MediaSource

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        released = false
        error = null
        super.prepareSourceInternal(mediaTransferListener)
        Log.d("EchoPlayback", "prepareSourceInternal: ${mediaItem.mediaId} \"${mediaItem.mediaMetadata.title}\"")
        val handler = Util.createHandlerForCurrentLooper()
        loadJob = scope.launch {
            state.activeLoadCount.incrementAndGet()
            try {
                var (new, serv) = runCatching { loader.load(mediaItem) }.getOrElse {
                    error = it
                    return@launch
                }
                val server = serv.getOrNull()
                state.servers[new.mediaId] = serv
                state.serverChanged.emit(Unit)
                val sources = server?.sources
                Log.d("EchoPlayback", "stream loaded: ${new.mediaId} server=${server != null} sources=${sources?.size ?: "null"}")
                val sourcesList = sources ?: listOf()
                val selectedIndex = when (sourcesList.size) {
                    0 -> {
                        Log.d("EchoPlayback", "null/empty sources for ${new.mediaId}")
                        error = serv.exceptionOrNull()
                            ?: TrackUnavailableException("No sources available for ${new.mediaId}")
                        return@launch
                    }

                    1 -> 0
                    else -> {
                        val index = mediaItem.sourceIndex
                        val source = sourcesList.getOrNull(index)
                            ?: sourcesList.select(app, new.extensionId) { it.quality }
                        sourcesList.indexOf(source).coerceAtLeast(0)
                    }
                }

                actualSource = when (sourcesList.size) {
                    0 -> return@launch // Handled above
                    1 -> {
                        val source = sourcesList.first()
                        getFactory(new, 0, source).create(new, 0, source)
                    }

                    else -> {
                        if (server?.merged == true) MergingMediaSource(
                            *sourcesList.mapIndexed { index, source ->
                                getFactory(new, index, source).create(new, index, source)
                            }.toTypedArray()
                        ) else {
                            val source = sourcesList[selectedIndex]
                            val wasForced = mediaItem.sourceIndex in sourcesList.indices
                            new = MediaItemUtils.buildSource(new, selectedIndex, forced = wasForced)
                            getFactory(new, selectedIndex, source).create(new, selectedIndex, source)
                        }
                    }
                }

                changeFlow.emit(mediaItem to new)
                mediaItem = new

                if (new.track.playableString(app.context) == null) {
                    val source = server?.sources?.getOrNull(selectedIndex)
                    val isLocal = source?.isLocal() == true
                    val isCacheable = source != null && !source.isLive && !isLocal && (
                        source is Streamable.Source.Raw ||
                        (source is Streamable.Source.Http && source.type != Streamable.SourceType.DASH && source.type != Streamable.SourceType.HLS)
                    )
                    val isPreloadEnabled = app.context.getSettings().getBoolean(PRELOAD_TRACK_CACHE, true)
                    
                    if (isCacheable && isPreloadEnabled) {
                        val key = metadataKey(new.track.id, new.serverIndex, selectedIndex, new.extensionId)
                        if (cache.isFullyCached(key)) {
                            Log.d("EchoCache", "Already fully cached: $key")
                            state.backgroundCacheProgress.update { it + (new.mediaId to (new.track.duration ?: 0L)) }
                        }
                    }
                }

                handler.post {
                    if (released) {
                        Log.d("EchoPlayback", "handler.post: released, skipping prepareChildSource for ${mediaItem.mediaId}")
                        return@post
                    }
                    Log.d("EchoPlayback", "handler.post: prepareChildSource firing for ${mediaItem.mediaId}")
                    runCatching {
                        prepareChildSource(null, actualSource)
                    }.getOrElse {
                        Log.e("EchoPlayback", "prepareChildSource threw for ${mediaItem.mediaId}", it)
                    }
                }
            } finally {
                state.activeLoadCount.decrementAndGet()
            }
        }
    }

    override fun releaseSourceInternal() {
        released = true
        loadJob?.cancel()
        loadJob = null
        state.backgroundCacheProgress.update { it - mediaItem.mediaId }
        super.releaseSourceInternal()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Nothing?, mediaSource: MediaSource, newTimeline: Timeline,
    ) = refreshSourceInfo(newTimeline)

    override fun getMediaItem() = mediaItem

    override fun createPeriod(
        id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long,
    ): MediaPeriod {
        Log.d("EchoPlayback", "createPeriod: ${mediaItem.mediaId} \"${mediaItem.mediaMetadata.title}\"")
        check(::actualSource.isInitialized) { "createPeriod called before source was prepared" }
        return actualSource.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        if (::actualSource.isInitialized) actualSource.releasePeriod(mediaPeriod)
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem) = run {
        this.mediaItem.apply {
            if (retries != mediaItem.retries) return@run false
            if (serverIndex != mediaItem.serverIndex) return@run false
            if (this.sourceIndex != mediaItem.sourceIndex) return@run false
            if (backgroundIndex != mediaItem.backgroundIndex) return@run false
            if (subtitleIndex != mediaItem.subtitleIndex) return@run false
        }
        if (::actualSource.isInitialized) actualSource.canUpdateMediaItem(mediaItem)
        else false
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
        actualSource.updateMediaItem(mediaItem)
    }

    data class Factories(
        val dash: Lazy<MediaSource.Factory>,
        val hls: Lazy<MediaSource.Factory>,
        val default: Lazy<MediaSource.Factory>,
        val cacheDataSourceFactory: DataSource.Factory,
    ) {
        fun create(mediaItem: MediaItem, index: Int, source: Streamable.Source?): MediaSource {
            val type = (source as? Streamable.Source.Http)?.type
            val factory = when (type) {
                Streamable.SourceType.DASH -> dash
                Streamable.SourceType.HLS -> hls
                Streamable.SourceType.Progressive, null -> default
            }
            val new = MediaItemUtils.buildForSource(mediaItem, index, source)
            return factory.value.createMediaSource(new)
        }
    }

    class Factory(
        private val app: App,
        private val scope: CoroutineScope,
        private val state: PlayerState,
        extensions: ExtensionLoader,
        val cache: SimpleCache,
        downloadFlow: StateFlow<List<Downloader.Info>>,
        private val changeFlow: MutableSharedFlow<Pair<MediaItem, MediaItem>>,
        healthMonitor: HealthMonitor,
    ) : MediaSource.Factory {

        private val loader = StreamableLoader(app, cache, extensions.music, downloadFlow, healthMonitor)

        val dataSourceFactory = StreamableDataSource.Factory(app.context)
        val streamableResolver = StreamableResolver(app.context, state.servers)

        private val cacheDataSource = ResolvingDataSource.Factory(
            CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(dataSourceFactory),
            streamableResolver
        )

        private val dataSource = ResolvingDataSource.Factory(
            dataSourceFactory, streamableResolver
        )

        private val cacheFactories = createFactories(cacheDataSource)

        private val factories = createFactories(dataSource)

        private fun createFactories(dataSource: ResolvingDataSource.Factory) = Factories(
            lazily { DashMediaSource.Factory(dataSource) },
            lazily { HlsMediaSource.Factory(dataSource) },
            lazily { DefaultMediaSourceFactory(dataSource) },
            dataSource
        )

        private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
        private fun lazily(factory: () -> MediaSource.Factory) = lazy {
            factory().apply {
                drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorHandlingPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }
        }

        override fun getSupportedTypes() = intArrayOf(
            C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH
        )

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            return this
        }

        override fun createMediaSource(mediaItem: MediaItem) = StreamableMediaSource(
            mediaItem, app, scope, state, loader, cache, dataSourceFactory, cacheFactories, factories, changeFlow
        )
    }
}
