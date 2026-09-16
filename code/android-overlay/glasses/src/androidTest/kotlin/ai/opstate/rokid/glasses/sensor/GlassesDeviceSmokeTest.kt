package ai.opstate.rokid.glasses.sensor

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesDeviceSmokeTest {
    @Test fun deviceReportsCameraCapability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        assertTrue("No camera exposed by the device", manager.cameraIdList.isNotEmpty())
    }

    @Test fun imuSamplerEmitsRealSensorWindows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = CompletableDeferred<MotionWindowSummary>()
        val sampler = AndroidImuSampler(context, scope, emit = { window ->
            if (window.sampleCount >= 8) received.complete(window)
        })
        try {
            sampler.start()
            val window = withTimeout(10_000) { received.await() }
            assertTrue("Window has no valid motion features", window.valid)
            assertTrue(window.endElapsedMs > window.startElapsedMs)
            assertTrue(window.angularRms.isFinite())
            assertTrue(window.linearRms.isFinite())
        } finally {
            sampler.stop()
            scope.cancel()
        }
    }
}
