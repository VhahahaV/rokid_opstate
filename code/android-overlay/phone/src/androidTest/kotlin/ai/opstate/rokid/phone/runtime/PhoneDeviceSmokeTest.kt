package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.ModelCandidate
import ai.opstate.core.inference.ModelObservation
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ObservationKind
import ai.opstate.rokid.phone.transport.ImageFrame
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import ai.opstate.rokid.phone.security.PrivateCredentialStore

@RunWith(AndroidJUnit4::class)
class PhoneDeviceSmokeTest {
    @Test fun cxrMobileLibraryLoadsAndBuildsTypedCommands() {
        assertNotNull(CxrApi.getInstance())
        // Match the mobile SDK loader; the embedded glasses bridge has different JNI classes.
        System.loadLibrary("cxr-sock-proto-jni")
        val payload="OPSTATE-CXR-ABI-验证-".repeat(512)
        val caps=Caps().apply { write(payload) }
        assertEquals(payload,caps.at(0).string)
    }

    @Test fun credentialsAreEncryptedAndRoundTripThroughAndroidKeystore() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=PrivateCredentialStore(context)
        val secret="synthetic-device-test-secret"
        try {
            store.put("client-secret",secret.toByteArray())
            assertEquals(secret,store.clientSecret())
            val encrypted=java.io.File(context.filesDir,"credentials/client-secret.enc").readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(secret))
        } finally { store.remove("client-secret") }
    }

    private fun image(): ImageFrame {
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            bitmap.setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) Color.WHITE else Color.BLACK)
        }
        val jpeg = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        return ImageFrame("capture-device-test", 100, 100, jpeg, 128, 96, 0, hash, "TEST")
    }

    @Test fun jpegQualityAndRepeatedFrameNoveltyWorkOnDevice() {
        val analyzer = LocalImageQualityAnalyzer()
        val frame = image()
        val first = analyzer.analyze(frame)
        assertTrue("Generated high-contrast JPEG should pass the quality gate: $first", first.valid)
        assertEquals(1.0, first.noveltyScore, 0.0)
        assertEquals(0.0, analyzer.analyze(frame).noveltyScore, 0.0)
        assertFalse(analyzer.analyze(frame.copy(jpegBytes = byteArrayOf(1, 2, 3))).valid)
    }

    @Test fun realMultipartClientReachesHarnessThroughAdbReverse() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("harnessUrl")
        assumeTrue("Pass -e harnessUrl http://127.0.0.1:8765 with adb reverse enabled", !url.isNullOrBlank())
        val frame = image()
        val request = InferenceRequest(
            requestId = "device-${UUID.randomUUID()}", anchorId = "anchor-device-test", anchorTimeEpochMs = 100,
            currentAssetId = "asset-device-test", currentImageSha256 = frame.sha256,
            candidate = ModelCandidate("object-device", "episode-device"),
            observations = listOf(ModelObservation(
                "obs-device-test", ObservationKind.SYNTHETIC_CONTEXT, 90, 90,
                "The meeting moved to room B302.", null, "episode-device", 1,
            )),
            receipts = emptyList(), promptHash = "device-smoke", preprocessHash = "device-smoke",
        )
        val client = RemoteHarnessClient(requireNotNull(url), { "local-device-test" }, Json)
        val first = client.evaluate(request, frame.jpegBytes)
        assertEquals(DecisionLabel.SERVICE, first.decision)
        assertEquals(setOf("obs-device-test"), first.evidenceObservationIds)
        assertEquals(first, client.evaluate(request, frame.jpegBytes))
        val silent = client.evaluate(request.copy(requestId = "device-${UUID.randomUUID()}", observations = emptyList()), frame.jpegBytes)
        assertEquals(DecisionLabel.NO_SERVICE, silent.decision)
        assertNull(silent.body)
    }
}
