package ai.opstate.rokid.glasses

import android.app.Application
import ai.opstate.rokid.glasses.transport.CxrGlassesLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ai.opstate.protocol.artifactSha256
import java.io.File

data class GlassesCard(val id: String,val epoch: Long,val kind: String,val mode: String,val title: String,val body: String,val expiresElapsedMs: Long)
data class GlassesDemoStatus(val phase: String,val context: String,val captures: Int,val receipts: Int,val automaticCapture: Boolean,
    val collection: Boolean=false,val intervalSeconds: Long=60)
data class GlassesUiState(val running: Boolean=false,val cameraReady: Boolean=false,val sessionId: String?=null,val card: GlassesCard?=null,val setupError: String?=null,
    val demoStatus: GlassesDemoStatus?=null,val phoneConnected: Boolean=false,val bufferedPhotos: Int=0,val localCaptures: Int=0,val collectingLocally: Boolean=false,
    /** What the page says when the phone has nothing to show: a session's own capture state. */
    val autonomousMode: String?=null,val captureIntervalMs: Long=0,val frontEndEnabled: Boolean=true,val paused: Boolean=false)
data class VisibleEvent(val decisionId: String,val epoch: Long,val frames: Int,val visibleAtEpochMs: Long)
class GlassesApplication : Application() {
    val apkSha256 by lazy {artifactSha256(File(applicationInfo.sourceDir))}
    val appVersion by lazy {packageManager.getPackageInfo(packageName,0).versionName ?: "unknown"}
    val link by lazy { CxrGlassesLink() }
    val mutableUi=MutableStateFlow(GlassesUiState())
    val ui=mutableUi.asStateFlow()
    val displayVisible=MutableStateFlow(false)
    val visibleEvents=Channel<VisibleEvent>(32)
    val dismissEvents=Channel<String>(32)
    override fun onCreate() {
        super.onCreate()
        // Arm the reboot restart here so it survives even if the wearer never opens the glasses UI.
        BootJobService.schedule(this)
    }
}
