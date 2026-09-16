package ai.opstate.rokid.phone.data

import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*
import java.util.UUID

/** Folds verified rows into the small set of unfinished operations; cumulative request bodies are never retained. */
internal class InterruptedOperations {
    data class Cancellation(val stream: String,val type: String,val payload: JsonObject)
    var configuration=JsonObject(emptyMap());private set
    private val captures=linkedSetOf<String>()
    private val anchors=linkedSetOf<String>()
    private val requests=linkedMapOf<String,Pair<String?,Boolean>>()
    private val undecidedDeliveries=linkedSetOf<String>()
    private val displays=linkedSetOf<String>()
    fun record(row: JournalEntity) {
        if(row.stream !in setOf("captures","anchors","inference_requests","decisions","deliveries","receipts","runtime_events")) return
        val data=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
        fun value(key: String)=data[key]?.jsonPrimitive?.contentOrNull
        when(row.stream) {
            "captures" -> value("capture_id")?.let {id->
                if(row.eventType=="CAPTURE_REQUEST") captures+=id
                else if(value("status") in setOf("COMPLETED","FAILED","TIMEOUT","CANCELLED")) captures-=id
            }
            "anchors" -> value("anchor_id")?.let {anchors+=it}
            "inference_requests" -> value("request_id")?.let {id->
                val packet=value("packet_id");val anchor=value("anchor_id")
                requests[id]=(packet ?: anchor) to (packet!=null);anchor?.let {anchors-=it}
            }
            "decisions" -> {
                value("request_id")?.let {requests.remove(it)}
                value("decision_id")?.let {undecidedDeliveries+=it}
            }
            "deliveries" -> value("decision_id")?.let {id->
                undecidedDeliveries-=id
                if(value("status")=="NOT_ATTEMPTED" && value("reason")=="PENDING_VISIBLE_ACK") displays+=id else displays-=id
            }
            "receipts" -> value("decision_id")?.let {displays-=it;undecidedDeliveries-=it}
            "runtime_events" -> when(row.eventType) {
                "SESSION_CONFIGURATION" -> configuration=data
                "TECHNICAL_FAILURE","INFERENCE_CANCELLED" -> value("request_id")?.let {requests.remove(it)}
                "ANCHOR_CANCELLED" -> value("anchor_id")?.let {anchors-=it}
            }
        }
    }
    fun cancelled(now: Long): List<Cancellation> = buildList {
        captures.forEach {add(Cancellation("captures","CAPTURE_CANCELLED",StudyWire.payload("capture_id" to it,"status" to "CANCELLED","code" to "PROCESS_RESTART")))}
        anchors.forEach {add(Cancellation("runtime_events","ANCHOR_CANCELLED",StudyWire.payload("anchor_id" to it,"code" to "PROCESS_RESTART")))}
        requests.forEach {(id,ref)->add(Cancellation("runtime_events","INFERENCE_CANCELLED",StudyWire.payload("request_id" to id,
            (if(ref.second) "packet_id" else "anchor_id") to ref.first,"code" to "PROCESS_RESTART")))}
        undecidedDeliveries.forEach {add(Cancellation("deliveries","DELIVERY_DECISION",StudyWire.payload("delivery_id" to "delivery-${UUID.randomUUID()}",
            "decision_id" to it,"status" to "NOT_ATTEMPTED","reason" to "PROCESS_RESTART","event_at_epoch_ms" to now,"receipt_id" to null)))}
        displays.forEach {add(Cancellation("deliveries","DELIVERY_DECISION",StudyWire.payload("delivery_id" to "delivery-${UUID.randomUUID()}",
            "decision_id" to it,"status" to "DISPLAY_FAILED","reason" to "PROCESS_RESTART","event_at_epoch_ms" to now,"receipt_id" to null)))}
    }
}
