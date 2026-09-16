package ai.opstate.core.runtime

/**
 * Single-owner state machine. Transport callbacks, timers and UI actions dispatch
 * events; they do not mutate trial state directly.
 */
class StudyStateMachine {
    sealed interface State {
        data object Idle : State
        data class Monitoring(val sessionId: String, val epoch: Long) : State
        data class Capturing(val sessionId: String, val epoch: Long, val captureId: String) : State
        data class Evaluating(val sessionId: String, val epoch: Long, val anchorId: String, val requestId: String) : State
        data class DeliveryPending(val sessionId: String, val epoch: Long, val decisionId: String) : State
        data class ServiceVisible(val sessionId: String, val epoch: Long, val decisionId: String) : State
        data class SilentComplete(val sessionId: String, val epoch: Long, val decisionId: String) : State
        data class Failed(val sessionId: String?, val epoch: Long?, val code: String, val recoverable: Boolean) : State
    }

    sealed interface Event {
        data class Start(val sessionId: String, val epoch: Long) : Event
        data class CaptureRequested(val captureId: String) : Event
        data class CaptureReady(val captureId: String) : Event
        data class AnchorReady(val anchorId: String, val requestId: String) : Event
        data class DecisionReady(val decisionId: String, val service: Boolean) : Event
        data class DisplayVisible(val decisionId: String) : Event
        data class DeliveryFailed(val decisionId: String, val code: String) : Event
        data class Recover(val sessionId: String, val epoch: Long) : Event
        data class Fail(val code: String, val recoverable: Boolean) : Event
        data object Reset : Event
    }

    var state: State = State.Idle
        private set

    fun dispatch(event: Event): State {
        state = reduce(state, event)
        return state
    }

    private fun reduce(current: State, event: Event): State = when (event) {
        is Event.Start -> State.Monitoring(event.sessionId, event.epoch)
        Event.Reset -> State.Idle
        is Event.Fail -> State.Failed(sessionId(current), epoch(current), event.code, event.recoverable)
        is Event.Recover -> State.Monitoring(event.sessionId, event.epoch)
        is Event.CaptureRequested -> when (current) {
            is State.Monitoring -> State.Capturing(current.sessionId, current.epoch, event.captureId)
            else -> illegal(current, event)
        }
        is Event.CaptureReady -> when (current) {
            is State.Capturing -> {
                require(current.captureId == event.captureId) { "capture correlation mismatch" }
                State.Monitoring(current.sessionId, current.epoch)
            }
            else -> illegal(current, event)
        }
        is Event.AnchorReady -> when (current) {
            is State.Monitoring -> State.Evaluating(current.sessionId, current.epoch, event.anchorId, event.requestId)
            else -> illegal(current, event)
        }
        is Event.DecisionReady -> when (current) {
            is State.Evaluating -> if (event.service) {
                State.DeliveryPending(current.sessionId, current.epoch, event.decisionId)
            } else {
                State.SilentComplete(current.sessionId, current.epoch, event.decisionId)
            }
            else -> illegal(current, event)
        }
        is Event.DisplayVisible -> when (current) {
            is State.DeliveryPending -> {
                require(current.decisionId == event.decisionId) { "decision correlation mismatch" }
                State.ServiceVisible(current.sessionId, current.epoch, event.decisionId)
            }
            else -> illegal(current, event)
        }
        is Event.DeliveryFailed -> when (current) {
            is State.DeliveryPending -> State.Failed(current.sessionId, current.epoch, event.code, recoverable = true)
            else -> illegal(current, event)
        }
    }

    private fun sessionId(state: State): String? = when (state) {
        State.Idle -> null
        is State.Monitoring -> state.sessionId
        is State.Capturing -> state.sessionId
        is State.Evaluating -> state.sessionId
        is State.DeliveryPending -> state.sessionId
        is State.ServiceVisible -> state.sessionId
        is State.SilentComplete -> state.sessionId
        is State.Failed -> state.sessionId
    }

    private fun epoch(state: State): Long? = when (state) {
        State.Idle -> null
        is State.Monitoring -> state.epoch
        is State.Capturing -> state.epoch
        is State.Evaluating -> state.epoch
        is State.DeliveryPending -> state.epoch
        is State.ServiceVisible -> state.epoch
        is State.SilentComplete -> state.epoch
        is State.Failed -> state.epoch
    }

    private fun illegal(current: State, event: Event): Nothing =
        error("illegal transition: ${current::class.simpleName} + ${event::class.simpleName}")
}
