package ai.opstate.core.sensor

import ai.opstate.core.model.MotionFeatures
import ai.opstate.core.model.Quaternion
import ai.opstate.core.model.SensorSample
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Converts raw rotation-vector quaternions and linear acceleration into a compact
 * window summary. Raw 25–50 Hz samples do not need to leave the glasses.
 */
class MotionFeatureExtractor(
    private val stableAngularRmsDegPerSec: Double = 2.5,
    private val stableLinearAccelRmsMps2: Double = 0.18,
    private val movingAngularRmsDegPerSec: Double = 12.0,
    private val movingLinearAccelRmsMps2: Double = 0.60,
    private val minStableRatio: Double = 0.75,
    private val minSamples: Int = 8,
) {
    fun extract(samples: List<SensorSample>): MotionFeatures {
        val sorted = samples.sortedBy { it.elapsedRealtimeNanos }
        if (sorted.size < minSamples) return invalid(sorted)

        val directAngular = sorted.mapNotNull { it.angularVelocityDegPerSec?.magnitude() }
        val angularVelocities = if (directAngular.size >= minSamples / 2) {
            directAngular.toMutableList()
        } else {
            mutableListOf<Double>().also { derived ->
                for (i in 1 until sorted.size) {
                    val previous = sorted[i - 1]
                    val current = sorted[i]
                    val q1 = previous.rotation?.normalized()
                    val q2 = current.rotation?.normalized()
                    val dtSec = (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
                    if (q1 == null || q2 == null || dtSec <= 0.0 || dtSec > 0.5) continue
                    val angleRad = quaternionAngle(q1, q2)
                    derived += Math.toDegrees(angleRad) / dtSec
                }
            }
        }

        val linearMagnitudes = sorted.mapNotNull { it.linearAccelerationMps2?.magnitude() }
        if (angularVelocities.size < minSamples / 2 || linearMagnitudes.size < minSamples / 2) {
            return invalid(sorted)
        }

        val angularRms = rms(angularVelocities)
        val linearRms = rms(linearMagnitudes)
        val angularP95 = percentile(angularVelocities, 0.95)
        val linearP95 = percentile(linearMagnitudes, 0.95)

        val stablePairs = angularVelocities.zip(linearMagnitudes.drop(1).take(angularVelocities.size))
        val stableRatio = if (stablePairs.isEmpty()) 0.0 else stablePairs.count { (angular, linear) ->
            angular <= stableAngularRmsDegPerSec * 1.5 &&
                linear <= stableLinearAccelRmsMps2 * 1.5
        }.toDouble() / stablePairs.size

        val startMs = sorted.first().elapsedRealtimeNanos / 1_000_000
        val endMs = sorted.last().elapsedRealtimeNanos / 1_000_000
        val isStable = angularRms <= stableAngularRmsDegPerSec &&
            linearRms <= stableLinearAccelRmsMps2 &&
            stableRatio >= minStableRatio
        val isMoving = angularRms >= movingAngularRmsDegPerSec ||
            linearRms >= movingLinearAccelRmsMps2

        return MotionFeatures(
            windowStartElapsedMs = startMs,
            windowEndElapsedMs = endMs,
            sampleCount = sorted.size,
            durationMs = (endMs - startMs).coerceAtLeast(0L),
            angularVelocityRmsDegPerSec = angularRms,
            angularVelocityP95DegPerSec = angularP95,
            linearAccelerationRmsMps2 = linearRms,
            linearAccelerationP95Mps2 = linearP95,
            stableRatio = stableRatio,
            isStable = isStable,
            isMoving = isMoving,
            valid = true,
        )
    }

    private fun invalid(samples: List<SensorSample>): MotionFeatures {
        val startMs = samples.firstOrNull()?.elapsedRealtimeNanos?.div(1_000_000) ?: 0L
        val endMs = samples.lastOrNull()?.elapsedRealtimeNanos?.div(1_000_000) ?: startMs
        return MotionFeatures(
            windowStartElapsedMs = startMs,
            windowEndElapsedMs = endMs,
            sampleCount = samples.size,
            durationMs = (endMs - startMs).coerceAtLeast(0L),
            angularVelocityRmsDegPerSec = Double.NaN,
            angularVelocityP95DegPerSec = Double.NaN,
            linearAccelerationRmsMps2 = Double.NaN,
            linearAccelerationP95Mps2 = Double.NaN,
            stableRatio = 0.0,
            isStable = false,
            isMoving = false,
            valid = false,
        )
    }

    private fun quaternionAngle(a: Quaternion, b: Quaternion): Double {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceIn(0.0, 1.0)
        return 2.0 * acos(dot)
    }

    private fun rms(values: List<Double>): Double =
        sqrt(values.sumOf { it * it } / values.size.coerceAtLeast(1))

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
