package ai.opstate.rokid.phone.replay

import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.FrozenHarnessContract
import kotlinx.serialization.json.*
import java.time.Instant

/** Only the checksum-verified public projection is accessible here. Evaluator labels are not app assets. */
class PublicReplayDataset(private val read: (String)->ByteArray) {
    val catalog=StudyWire.json.parseToJsonElement(read("catalog.json").toString(Charsets.UTF_8)).jsonObject
    private fun rows(path: String,hashKey: String): List<JsonObject> {
        val bytes=read(path)
        check(StudyWire.sha256(bytes)==catalog.text(hashKey)) { "DATASET_CHECKSUM_MISMATCH" }
        return bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { StudyWire.json.parseToJsonElement(it).jsonObject }.toList()
    }
    val anchors=rows("anchors.jsonl","public_anchor_sha256")
    private val source=rows("observations.jsonl","public_observation_sha256")
    val observations: List<ModelObservation>
    private val images=anchors.associate { it.getValue("current_asset").jsonObject.let { asset -> asset.text("image_id") to asset } }
    init {
        check(catalog["teacher_receipts_imported"]==JsonPrimitive(false)) { "TEACHER_RECEIPTS_NOT_ALLOWED" }
        val revisions=mutableMapOf<String,Long>();val lastText=mutableMapOf<Pair<String,String?>,String>()
        observations=source.map { row ->
            require(row.keys==setOf("observation_id","available_at","global_sequence_index","modality","payload"))
            val payload=row.getValue("payload").jsonObject
            val visual=row.text("modality")=="visual"
            require(payload.keys.all { it in if(visual) setOf("current_asset") else setOf("surface","text","task_episode_handle") })
            val available=epoch(row.text("available_at"));val asset=payload["current_asset"]?.jsonObject
            val handle=payload["task_episode_handle"]?.jsonPrimitive?.contentOrNull
            val revision=handle?.let {
                val key=it to payload["surface"]?.jsonPrimitive?.contentOrNull
                if(lastText[key]!=payload.text("text")) { revisions[it]=(revisions[it] ?: 0)+1;lastText[key]=payload.text("text") }
                revisions.getValue(it)
            }
            val occurred=asset?.let { epoch(it.text("timestamp")) } ?: available
            check(occurred<=available) { "FUTURE_OBSERVATION_TIME" }
            ModelObservation(row.text("observation_id"),if(visual) ObservationKind.IMAGE else ObservationKind.SYNTHETIC_CONTEXT,
                occurred,available,if(visual) "Recorded visual observation: ${asset!!.text("image_id")}. Historical pixels are not attached." else payload.text("text"),
                asset?.text("image_id"),handle,revision)
        }
        require(observations.zipWithNext().all { (a,b)->a.availableAtEpochMs<=b.availableAtEpochMs })
        require(observations.map { it.observationId }.distinct().size==observations.size)
        require(anchors.size==catalog.getValue("anchor_count").jsonPrimitive.int && observations.size==catalog.getValue("observation_count").jsonPrimitive.int)
        require(anchors.map {it.text("anchor_id")}.distinct().size==anchors.size)
        anchors.forEach { require(it.keys==setOf("anchor_id","anchor_index","available_at","current_asset","query","task_episode_handle")) }
    }
    fun select(limit: Int): List<JsonObject> {
        require(limit in 1..anchors.size)
        return if(limit==1) listOf(anchors.first()) else (0 until limit).map { anchors[it*(anchors.size-1)/(limit-1)] }
    }
    fun request(anchor: JsonObject,contract: FrozenHarnessContract,runId: String): InferenceRequest {
        val at=epoch(anchor.text("available_at"));val asset=anchor.getValue("current_asset").jsonObject
        val handle=anchor.text("task_episode_handle")
        val visible=observations.takeWhile { it.availableAtEpochMs<=at }.map {
            if(it.assetId==asset.text("image_id")) it.copy(content="Current egocentric image attached as the multipart image.") else it
        }
        val query=ModelObservation("query-${anchor.text("anchor_id")}",ObservationKind.SYNTHETIC_CONTEXT,at,at,anchor.text("query"),null,handle,null)
        return InferenceRequest(contract.protocolVersion,"$runId-${anchor.getValue("anchor_index").jsonPrimitive.int}",anchor.text("anchor_id"),at,
            asset.text("image_id"),asset.text("sha256"),ModelCandidate("obj_${StudyWire.sha256(handle.toByteArray()).take(16)}",handle),
            visible+query,emptyList(),contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash)
    }
    fun hasImage(assetId: String)=assetId in images
    fun image(assetId: String): ByteArray {
        val asset=images.getValue(assetId)
        val path=asset.text("relative_path")
        require(path.matches(Regex("[0-9]{6}/[0-9]{2}/[A-Za-z0-9_.-]+\\.jpg")))
        val bytes=read("images/$path")
        require(bytes.size in 1..8*1024*1024 && StudyWire.sha256(bytes)==asset.text("sha256")) { "DATASET_IMAGE_CHECKSUM_MISMATCH" }
        return bytes
    }
    companion object {
        const val ASSET_ROOT="datasets/january-v10-r6/"
        fun epoch(value: String)=Instant.parse(value).toEpochMilli()
    }
}
internal fun JsonObject.text(key: String)=getValue(key).jsonPrimitive.content
