package ai.opstate.rokid.glasses.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Optional privacy-preserving energy VAD. It discards PCM immediately and emits
 * only speechRatio and RMS dBFS. Disable this feature for the first MVP unless
 * microphone permission and study consent are already settled.
 */
class VadOnlyGate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onWindow: suspend (VadWindow) -> Unit,
    private val sampleRate: Int = 16_000,
    private val windowMs: Long = 2_500L,
    private val frameMs: Int = 20,
    private val speechThresholdDbfs: Double = -42.0,
) {
    private var job: Job? = null

    fun start(): Result<Unit> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("RECORD_AUDIO not granted"))
        }
        if (job?.isActive == true) return Result.success(Unit)
        job = scope.launch(Dispatchers.IO) { recordLoop() }
        return Result.success(Unit)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun recordLoop() {
        // Permission can change between start() and this background coroutine.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val frameSamples = sampleRate * frameMs / 1_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameSamples * 4),
            )
        } catch (denied: SecurityException) {
            android.util.Log.w("OPSTATE", "VAD microphone permission unavailable", denied)
            return
        }
        val buffer = ShortArray(frameSamples)
        var windowStart = android.os.SystemClock.elapsedRealtime()
        var frames = 0
        var speechFrames = 0
        var energySum = 0.0
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                var square = 0.0
                for (i in 0 until read) {
                    val normalized = buffer[i] / 32768.0
                    square += normalized * normalized
                    buffer[i] = 0 // erase PCM as soon as aggregated
                }
                val rms = sqrt(square / read.coerceAtLeast(1))
                val dbfs = if (rms <= 1e-9) -120.0 else 20.0 * log10(rms)
                frames++
                energySum += dbfs
                if (dbfs >= speechThresholdDbfs) speechFrames++
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowStart >= windowMs) {
                    onWindow(
                        VadWindow(
                            windowStartElapsedMs = windowStart,
                            windowEndElapsedMs = now,
                            frameCount = frames,
                            speechRatio = if (frames == 0) 0.0 else speechFrames.toDouble() / frames,
                            meanDbfs = if (frames == 0) -120.0 else energySum / frames,
                        )
                    )
                    windowStart = now
                    frames = 0
                    speechFrames = 0
                    energySum = 0.0
                }
            }
        } catch (denied: SecurityException) {
            android.util.Log.w("OPSTATE", "VAD microphone permission revoked", denied)
        } catch (unavailable: IllegalStateException) {
            android.util.Log.w("OPSTATE", "VAD recorder unavailable", unavailable)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            buffer.fill(0)
        }
    }
}

data class VadWindow(
    val windowStartElapsedMs: Long,
    val windowEndElapsedMs: Long,
    val frameCount: Int,
    val speechRatio: Double,
    val meanDbfs: Double,
)
