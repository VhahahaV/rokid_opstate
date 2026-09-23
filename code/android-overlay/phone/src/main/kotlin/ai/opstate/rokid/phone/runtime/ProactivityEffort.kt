package ai.opstate.rokid.phone.runtime

import kotlinx.serialization.Serializable

/** One setting shared by automatic packet decisions and operator-requested service. */
@Serializable
enum class ProactivityEffort(val level: Int) {
    RESTRAINED(0),
    INCLINED(1),
    GUARANTEED(2);

    companion object {
        fun fromLevel(level: Int): ProactivityEffort = entries.first { it.level == level.coerceIn(0,2) }
    }
}
