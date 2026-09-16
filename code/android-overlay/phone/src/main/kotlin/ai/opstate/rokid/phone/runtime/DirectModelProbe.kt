package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.UUID

/** A settings-only check with generated pixels and synthetic text; no participant image/history is sent. */
object DirectModelProbe {
    suspend fun run(client: DirectModelClient,onRaw: (RawHarnessResponse)->Unit={}): InferenceResponse {
        val contract=client.contract();val now=System.currentTimeMillis()
        val image=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888)
        for(y in 0 until image.height) for(x in 0 until image.width) image.setPixel(x,y,if((x/20+y/20)%2==0) Color.WHITE else Color.BLACK)
        val jpeg=ByteArrayOutputStream().use {image.compress(Bitmap.CompressFormat.JPEG,80,it);it.toByteArray()};image.recycle()
        val request=InferenceRequest(contract.protocolVersion,"api-probe-${UUID.randomUUID()}","anchor-probe",now,"asset-probe",StudyWire.sha256(jpeg),
            ModelCandidate("obj-probe","ep-probe"),listOf(
                ModelObservation("probe-context",ObservationKind.SYNTHETIC_CONTEXT,now,now,"Connection check with generated test pixels. No real person, task or pending action is represented.",null,"ep-probe",0),
                ModelObservation("probe-image",ObservationKind.IMAGE,now,now,"Generated checkerboard.","asset-probe",null,null)),emptyList(),
            contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash)
        return client.evaluate(request,jpeg,onRaw)
    }
}
