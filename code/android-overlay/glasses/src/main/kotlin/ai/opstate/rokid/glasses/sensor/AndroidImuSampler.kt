package ai.opstate.rokid.glasses.sensor

import ai.opstate.core.model.Quaternion
import ai.opstate.core.model.SensorSample
import ai.opstate.core.model.Vector3
import ai.opstate.core.sensor.MotionFeatureExtractor
import android.content.Context
import android.hardware.*
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Samples rotation vector and linear acceleration on glasses and emits only a
 * compact window summary. Raw samples remain in memory for a few seconds.
 */
class AndroidImuSampler(
    context: Context,
    private val scope: CoroutineScope,
    private val emit: suspend (MotionWindowSummary) -> Unit,
    private val extractor: MotionFeatureExtractor = MotionFeatureExtractor(),
    private val emitIntervalMs: Long = 1_000L,
    private val keepWindowMs: Long = 4_000L,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val samples = ConcurrentLinkedQueue<SensorSample>()
    private var latestRotation: Quaternion? = null
    private var latestLinear: Vector3? = null
    private var latestAngularDegPerSec: Vector3? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        // SENSOR_DELAY_GAME is normally around 50 Hz; actual timestamps are used.
        rotation?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linear?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        job = scope.launch {
            while (isActive) {
                delay(emitIntervalMs)
                prune()
                val features = extractor.extract(samples.toList())
                emit(MotionWindowSummary.from(features))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        manager.unregisterListener(this)
        samples.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                latestRotation = Quaternion(q[1].toDouble(), q[2].toDouble(), q[3].toDouble(), q[0].toDouble())
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                latestLinear = Vector3(
                    event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestAngularDegPerSec = Vector3(
                    Math.toDegrees(event.values[0].toDouble()),
                    Math.toDegrees(event.values[1].toDouble()),
                    Math.toDegrees(event.values[2].toDouble()),
                )
            }
        }
        samples += SensorSample(
            elapsedRealtimeNanos = event.timestamp,
            rotation = latestRotation,
            linearAccelerationMps2 = latestLinear,
            angularVelocityDegPerSec = latestAngularDegPerSec,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun prune() {
        val cutoffNanos = SystemClock.elapsedRealtimeNanos() - keepWindowMs * 1_000_000L
        while (samples.peek()?.elapsedRealtimeNanos?.let { it < cutoffNanos } == true) samples.poll()
    }
}

data class MotionWindowSummary(
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val sampleCount: Int,
    val angularRms: Double,
    val angularP95: Double,
    val linearRms: Double,
    val linearP95: Double,
    val stableRatio: Double,
    val stable: Boolean,
    val moving: Boolean,
    val valid: Boolean,
) {
    companion object {
        fun from(f: ai.opstate.core.model.MotionFeatures) = MotionWindowSummary(
            f.windowStartElapsedMs, f.windowEndElapsedMs, f.sampleCount,
            f.angularVelocityRmsDegPerSec, f.angularVelocityP95DegPerSec,
            f.linearAccelerationRmsMps2, f.linearAccelerationP95Mps2,
            f.stableRatio, f.isStable, f.isMoving, f.valid,
        )
    }
}
