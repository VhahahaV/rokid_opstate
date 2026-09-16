package ai.opstate.rokid.phone.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class SyntheticContextType(val label: String) {EMAIL("Email"),CALENDAR("Calendar"),MESSAGE("消息"),NOTE("备忘")}

@Serializable
data class SyntheticContextInput(val type: SyntheticContextType,val title: String,val body: String,
    val updateCurrentTask: Boolean=true,val showOnGlasses: Boolean=true) {
    fun validate() {require(title.isNotBlank() && title.length<=80 && body.isNotBlank() && body.length<=4000) {"CONTEXT_INPUT_INVALID"}}
    fun publicText()="[${type.name}] ${title.trim()}\n${body.trim()}"
}
