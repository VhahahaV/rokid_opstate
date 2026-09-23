package ai.opstate.rokid.phone.ui

import ai.opstate.rokid.phone.runtime.ProactivityEffort
import ai.opstate.rokid.phone.runtime.RuntimeViewState
import ai.opstate.rokid.phone.runtime.SessionMode
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProactivityEffortControlUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun sliderShowsThreeLevelsAndCommitsTheSelectedLevel() {
        var selected: ProactivityEffort?=null
        compose.setContent {MaterialTheme {Column {
            ProactivityEffortControl("session",ProactivityEffort.RESTRAINED,true) {selected=it}
        }}}
        compose.onNodeWithTag("proactivity_effort_slider").assertExists()
        compose.onNodeWithText("主动服务 effort · 克制服务").assertExists()
        compose.onNodeWithText("倾向服务").assertExists()
        compose.onNodeWithText("必定服务").assertExists()
        compose.onNodeWithTag("proactivity_effort_slider").performTouchInput {
            down(Offset(36f,center.y))
            moveTo(Offset(center.x*2f-36f,center.y))
            up()
        }
        compose.onNodeWithText("主动服务 effort · 必定服务").assertExists()
        compose.runOnIdle {assertEquals(ProactivityEffort.GUARANTEED,selected)}
    }

    @Test fun liveSessionCardShowsSliderAndManualSourcesHaveNoForceSwitch() {
        val actions=OperatorActions(
            scan={},reconnect={},disconnect={},p2p={},permissions={},bluetooth={},probe={},
            saveSecret={},importLicense={},openDeveloperConsole={},saveModel={_,_->},clearModelKey={},
            openSession={},dismissMessage={},startReplay={},stopReplay={},openSessionId={},
            start={},pause={},resume={},stop={},capture={},seek={},automaticCapture={},demoMode={},
            addContext={_,done->done()},openLogs={},loadHistory={_,_,done->done()},clearHistory={})
        compose.setContent {MaterialTheme {
            OperatorScreen(OperatorViewState(peerReady=true,runtime=RuntimeViewState(
                sessionId="fixture",phase="RUNNING",mode=SessionMode.SERVICE,
                proactivityAvailable=true)),actions)
        }}
        compose.onNodeWithText("当前会话").assertExists()
        compose.onNodeWithTag("proactivity_effort_slider").assertExists()
        compose.onNodeWithTag("operator-content-0").performScrollToIndex(1)
        compose.onNodeWithText("手动触发").performClick()
        compose.onNodeWithText("强制服务").assertDoesNotExist()
    }
}
