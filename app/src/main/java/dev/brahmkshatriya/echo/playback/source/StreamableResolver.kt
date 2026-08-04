package dev.brahmkshatriya.echo.playback.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource.Resolver
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.playback.MediaItemUtils.metadataKey
import dev.brahmkshatriya.echo.playback.MediaItemUtils.toKey
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource.Companion.uri
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
class StreamableResolver(
    private val context: Context,
    private val current: MutableMap<String, Result<Streamable.Media.Server>>,
) : Resolver {

    @OptIn(UnstableApi::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val (id, serverIndex, sourceIndex, extensionId) = dataSpec.uri.toString().toKey().getOrNull() ?: return dataSpec
        val originalKey = dataSpec.uri.toString()

        val serverResult = current[id]
        if (serverResult != null) {
            val streamable = runCatching {
                serverResult.getOrThrow().sources[sourceIndex]
            }
            val uri = streamable.map {
                var finalUri = it.uri
                val sourceUri = finalUri.toString()
                if (!it.isLive) {
                    if (sourceUri.isNullOrEmpty()) {
                        val cached = context.getFromCache<String>(originalKey, "player")
                        if (cached != null) {
                            finalUri = Uri.parse(cached)
                        }
                    } else {
                        context.saveToCache(originalKey, sourceUri, "player")
                    }
                }
                finalUri
            }
            return dataSpec.copy(
                uri = uri.getOrNull(),
                key = originalKey,
                customData = streamable
            )
        }

        // Offline recovery: search for ANY cached index of this track
        for (i in 0..20) {
            val altKey = metadataKey(id, i, 0, extensionId)
            val cachedUri = context.getFromCache<String>(altKey, "player")
            if (cachedUri != null) {
                return dataSpec.copy(
                    uri = Uri.parse(cachedUri),
                    key = altKey, // Use the key that was used when caching
                    customData = Result.success(cachedUri.toSource())
                )
            }
        }

        return dataSpec
    }

    companion object {

        @OptIn(UnstableApi::class)
        fun DataSpec.copy(
            uri: Uri? = null,
            uriPositionOffset: Long? = null,
            httpMethod: Int? = null,
            httpBody: ByteArray? = null,
            httpRequestHeaders: Map<String, String>? = null,
            position: Long? = null,
            length: Long? = null,
            key: String? = null,
            flags: Int? = null,
            customData: Any? = null,
        ): DataSpec {
            return DataSpec.Builder()
                .setUri(uri ?: this.uri)
                .setUriPositionOffset(uriPositionOffset ?: this.uriPositionOffset)
                .setHttpMethod(httpMethod ?: this.httpMethod)
                .setHttpBody(httpBody ?: this.httpBody)
                .setHttpRequestHeaders(httpRequestHeaders ?: this.httpRequestHeaders)
                .setPosition(position ?: this.position)
                .setLength(length ?: this.length)
                .setKey(key ?: this.key)
                .setFlags(flags ?: this.flags)
                .setCustomData(customData ?: this.customData)
                .build()
        }
    }
}