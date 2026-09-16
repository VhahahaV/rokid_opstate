package ai.opstate.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Replays a result without repeating an effect, including when an old stop is retried after a new session. */
class CommandReplyCache(private val capacity: Int=128) {
    private data class Key(val session: String,val epoch: Long,val idempotency: String)
    private data class Entry(val request: StudyMessage,val reply: StudyMessage)
    private val entries=linkedMapOf<Key,Entry>()
    init {require(capacity>0)}
    private fun key(request: StudyMessage)=Key(request.session_id,request.session_epoch,request.idempotency_key)
    @Synchronized fun remember(request: StudyMessage,reply: StudyMessage) {
        entries[key(request)]=Entry(request,reply)
        while(entries.size>capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun replay(request: StudyMessage): StudyMessage? {
        val entry=entries[key(request)] ?: return null
        if(request.type!=entry.request.type || request.payload!=entry.request.payload) {
            return entry.reply.copy(type="ERROR",payload=StudyWire.payload("code" to "IDEMPOTENCY_CONFLICT","reply_to" to request.message_id))
        }
        return entry.reply.copy(payload=JsonObject(entry.reply.payload+("reply_to" to JsonPrimitive(request.message_id))))
    }
}
