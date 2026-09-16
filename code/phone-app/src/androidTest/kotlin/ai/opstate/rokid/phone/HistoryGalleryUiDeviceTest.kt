package ai.opstate.rokid.phone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opstate.rokid.phone.review.SessionReviewRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only UI regression. Uses existing captured photos; it never starts a live service or a model. */
@RunWith(AndroidJUnit4::class)
class HistoryGalleryUiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.application as PhoneApplication
    @Test fun loadingPublicHistoryLeavesRuntimeIdleAndCreatesNoSession()=runBlocking<Unit> {
        graph.storageReady.await()
        val before=withContext(Dispatchers.IO) {graph.database.dao().sessions().size}
        compose.onNodeWithText("导入历史").performClick()
        compose.onNodeWithText("内置历史数据").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("加载为历史").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("加载为历史").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("更换历史").fetchSemanticsNodes().isNotEmpty()}
        assertNull(graph.studyRuntime.state.value.sessionId)
        assertEquals("IDLE",graph.studyRuntime.state.value.phase)
        assertFalse(graph.datasetReplay.state.value.running)
        assertEquals(before,withContext(Dispatchers.IO) {graph.database.dao().sessions().size})
        compose.onNodeWithText("在线验证").assertDoesNotExist()
        compose.onNodeWithText("清除已加载历史").performClick()
    }
    @Test fun capturedPhotoOpensFromAlbumWithOriginalMetadata()=runBlocking<Unit> {
        graph.storageReady.await()
        val review=withContext(Dispatchers.IO) {
            val repository=SessionReviewRepository(graph.journal)
            val session=graph.database.dao().sessions().first {it.scenarioId=="local-collection-v1" && it.endedAtEpochMs!=null && repository.photos(it.sessionId).isNotEmpty()}
            repository.load(session.sessionId)
        }
        compose.onNodeWithText("记录",useUnmergedTree=true).performClick()
        val tag="session-${review.session.sessionId}"
        compose.onNodeWithTag("operator-content-1").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("照片与记录").fetchSemanticsNodes().isNotEmpty()}
        val photo="photo-${review.photos.first().id}"
        compose.onNodeWithTag("review-content").performScrollToNode(hasTestTag(photo))
        compose.onNodeWithTag(photo).performClick()
        compose.onNodeWithTag("photo-details").performScrollToNode(hasText("图片尺寸"))
        compose.onNodeWithText("手机收到时间").assertExists()
        compose.onNodeWithTag("photo-details").performScrollToNode(hasText("手机接收时的位置"))
        compose.onNodeWithText("手机接收时的位置").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("照片与记录").assertExists()
    }
}
