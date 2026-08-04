package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 🚀 Feature: Hardware-bypassable Loudness Normalization
 * This processor now ONLY handles the heavy PCM Loudness Normalization (Dual-stage LUT).
 * All fade logic (skip, pause, resume, crossfade) has been moved to ExoPlayer's `player.volume`,
 * which completely eliminates buffer latency, audio pops, and complex multi-threaded math.
 * 
 * If Normalization is turned off, this processor reports `isActive() = false` and ExoPlayer 
 * physically bypasses it, saving massive amounts of CPU and battery.
 */
@OptIn(UnstableApi::class)
class AudioEffectsProcessor : BaseAudioProcessor() {

    @Volatile var isProcessingEnabled = true
    @Volatile var normalizationEnabled = false

    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    // LUT infrastructure
    // Placeholder is a CLEAN PASSTHROUGH table (see passthroughLut) set synchronously in the ctor, so
    // activeLut is always a valid 65536-entry array before the audio thread reads it. The real unity-gain
    // table (~65k pow() — the onCreate ANR when built in the ctor) is generated OFF-MAIN in init{} below
    // and swapped in by the audio thread via pendingLut, exactly like setTrackGain/resetGain.
    private var activeLut: ShortArray = passthroughLut()
    private val pendingLut = AtomicReference<Pair<String, ShortArray>?>(null)
    private val currentTrackToken = AtomicReference("")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Defer the unity-gain LUT off the main thread. Stage with the initial token so the audio thread's
        // swap gate applies it only while no track has set its own gain yet; a later setTrackGain
        // (unconditional pendingLut.set) always wins, and compareAndSet(null,…) here never disturbs it.
        scope.launch {
            val lut = generateDualStageLUT(0f)
            pendingLut.compareAndSet(null, currentTrackToken.get() to lut)
        }
    }

    // Clean unprocessed passthrough for the pre-swap window. The read path is index = rawSample + 32768,
    // output = activeLut[index], so lut[i] = i - 32768 maps every input sample back to ITSELF exactly.
    // (lut[i] = i would add a +32768 DC offset and clip — distortion.) No pow(), so it's cheap on main; it
    // only omits the soft-limiter on >half-scale peaks until the real table swaps in — inaudible, and no
    // audio flows at onCreate anyway.
    private fun passthroughLut() = ShortArray(65536) { (it - 32768).toShort() }

    private fun generateDualStageLUT(gainDb: Float, isNormalizationEnabled: Boolean = true): ShortArray {
        val gainMultiplier = 10.0.pow(gainDb / 20.0)
        val lut = ShortArray(65536)
        for (i in 0..65535) {
            val rawSample = (i - 32768) / 32768.0
            val signedInput = rawSample * gainMultiplier
            val sign = if (signedInput >= 0.0) 1.0 else -1.0
            var x = abs(signedInput)

            // Pass A — 2.5:1 downward compression above threshold 0.4
            if (isNormalizationEnabled && x > 0.4) x = 0.4 * (x / 0.4).pow(PASS_A_EXPONENT)

            // Pass B — cubic soft limiter, C0-continuous at x=0.5 and x=1.25
            val out = when {
                x <= 0.5  -> x
                x <= 1.25 -> (-16.0 / 27.0) * x.pow(3) + (8.0 / 9.0) * x.pow(2) + (5.0 / 9.0) * x + (2.0 / 27.0)
                else      -> 1.0
            }

            lut[i] = (out * sign * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return lut
    }

    fun setTrackGain(gainDb: Float?, trackId: String?) {
        val token = trackId ?: ""
        currentTrackToken.set(token)
        scope.launch {
            val effectiveGain = if (normalizationEnabled) {
                if (gainDb != null) (gainDb + 4.5f).coerceIn(-15f, 15f)
                else 0f
            } else 0f
            val lut = generateDualStageLUT(effectiveGain, normalizationEnabled)
            if (currentTrackToken.get() == token) {
                pendingLut.set(Pair(token, lut))
            }
        }
    }

    fun resetGain() {
        val token = currentTrackToken.get()
        scope.launch {
            val lut = generateDualStageLUT(0f, normalizationEnabled)
            if (currentTrackToken.get() == token) {
                pendingLut.set(Pair(token, lut))
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        return inputAudioFormat
    }
    
    // 🚀 Feature: Hardware-bypassable Loudness Normalization
    // This processor handles the heavy PCM Loudness Normalization (Dual-stage LUT).
    // ExoPlayer queries isActive() at stream configuration time. If we return false here
    // based on normalizationEnabled, toggling it mid-stream won't work until the next track.
    // Instead, we stay active if general audio processing is enabled, and perform a blazing-fast
    // buffer copy (memcpy) in queueInput when normalization is turned off.
    override fun isActive(): Boolean {
        return isProcessingEnabled
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // Swap in pending LUT if available and generation token still matches
        pendingLut.get()?.let { (lutToken, lutArray) ->
            if (lutToken == currentTrackToken.get()) {
                activeLut = lutArray
                pendingLut.set(null)
            }
        }

        val fmt = configuredFormat
        val output = replaceOutputBuffer(inputBuffer.remaining())

        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.sampleRate <= 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        // FAST PASSTHROUGH: If normalization is disabled, or overall audio processing is disabled,
        // use a highly-optimized native block copy.
        // This consumes virtually zero CPU, honoring the original author's intent to save battery.
        // It also ensures that toggling "Audio Processing" off in the UI bypasses the LUT instantly.
        if (!normalizationEnabled || !isProcessingEnabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channelCount = fmt.channelCount

        while (inputBuffer.remaining() >= channelCount * 2) {
            repeat(channelCount) {
                val raw = inputBuffer.short.toInt()
                val index = (raw + 32768).coerceIn(0, 65535)
                val processed = activeLut[index] / 32768f
                output.putShort(
                    (processed * 32768f).toInt()
                        .coerceIn(-32768, 32767).toShort()
                )
            }
        }

        output.flip()
    }

    private companion object {
        const val PASS_A_EXPONENT = 1.0 / 2.5  // 2.5:1 compression ratio, threshold 0.4
    }
}
