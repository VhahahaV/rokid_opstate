package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceResponse
import kotlinx.serialization.json.*

/** Structural checks precede DTO conversion so null/missing HOW and duplicate refs stay distinct. */
object ResponseParser {
    fun parse(json: Json,body: String,protocol: String): InferenceResponse {
        val root=json.parseToJsonElement(body).jsonObject
        val v2=protocol=="opstate-online-v2"
        val common=setOf("request_id","decision_id","decision","produced_at_epoch_ms","expires_at_epoch_ms")
        val required=common+if(v2) setOf("service","evidence_refs") else setOf("evidence_observation_ids")
        val allowed=required+if(v2) setOf("confidence") else setOf("service_mode","title","body")
        require(root.keys.containsAll(required) && allowed.containsAll(root.keys)) {"RESPONSE_SCHEMA_FIELDS"}
        for(key in listOf("request_id","decision_id","decision")) require(root.getValue(key).jsonPrimitive.isString) {"RESPONSE_STRING_TYPE"}
        for(key in listOf("produced_at_epoch_ms","expires_at_epoch_ms")) {
            val value=root.getValue(key).jsonPrimitive
            require(!value.isString && value.longOrNull!=null) {"RESPONSE_TIMESTAMP_TYPE"}
        }
        val refs=root.getValue(if(v2) "evidence_refs" else "evidence_observation_ids").jsonArray
        require(refs.all {it is JsonPrimitive && it.isString} && refs.distinct().size==refs.size) {"INVALID_EVIDENCE_LIST"}
        val label=root.getValue("decision").jsonPrimitive.content
        if(v2) {
            if(label=="NO_SERVICE") require(root["service"]==JsonNull) {"NO_SERVICE_MUST_HAVE_NULL_SERVICE"}
            if(label=="SERVICE") require(root["service"] is JsonObject) {"SERVICE_REQUIRES_HOW"}
            if(root["service"] is JsonObject) {
                val how=root.getValue("service").jsonObject
                require(how.keys==setOf("mode","title","body") && how.values.all {it is JsonPrimitive && it.isString}) {"SERVICE_SCHEMA_FIELDS"}
            }
            root["confidence"]?.let {
                require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.let {number->number in 0.0..1.0}==true) {"INVALID_CONFIDENCE"}
            }
            return json.decodeFromJsonElement<WireInferenceResponseV2>(root).toDomain()
        }
        if(label=="NO_SERVICE") require(setOf("service_mode","title","body").none {it in root}) {"NO_SERVICE_MUST_OMIT_HOW"}
        if(label=="SERVICE") require(listOf("service_mode","title","body").all {root[it] is JsonPrimitive && root.getValue(it).jsonPrimitive.isString}) {"SERVICE_REQUIRES_HOW"}
        return json.decodeFromJsonElement<WireInferenceResponse>(root).toDomain()
    }
}
