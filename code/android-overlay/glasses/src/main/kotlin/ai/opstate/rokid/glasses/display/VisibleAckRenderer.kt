package ai.opstate.rokid.glasses.display

import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos

/**
 * Emit DISPLAY_VISIBLE_ACK only after the service card has survived two Compose
 * frames. A command accepted by transport is not equivalent to a visible service.
 */
class VisibleAckController(
    private val sendVisibleAck: suspend (decisionId: String, visibleAtEpochMs: Long, frameCount: Int) -> Unit,
) {
    suspend fun acknowledgeAfterTwoFrames(decisionId: String) {
        withFrameNanos { }
        withFrameNanos { }
        sendVisibleAck(decisionId, System.currentTimeMillis(), 2)
    }
}

@Immutable
data class ServiceCardUi(
    val decisionId: String,
    val modeLabel: String,
    val title: String,
    val body: String,
    val evidenceBadge: String?,
)

@Composable
fun VisibleServiceCard(
    card: ServiceCardUi,
    ackController: VisibleAckController,
    content: @Composable (ServiceCardUi) -> Unit,
) {
    content(card)
    LaunchedEffect(card.decisionId) {
        ackController.acknowledgeAfterTwoFrames(card.decisionId)
    }
}
