package ai.opstate.rokid.phone

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only review of the actual completed phone dataset replay; uses UI semantics, not screen coordinates. */
@RunWith(AndroidJUnit4::class)
class OperatorUiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun opensActualTrialImageAndRawRequestThenReturnsToItsListPosition() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val validation=File(context.filesDir,"validation")
        val run=StudyWire.json.parseToJsonElement(File(validation,"phone-dataset-replay.json").readText()).jsonObject
        val sessionId=run.getValue("session_id").jsonPrimitive.content
        compose.onNodeWithText("会话",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText(sessionId,substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("operator-content-1").performScrollToNode(hasText(sessionId))
        compose.onNodeWithText(sessionId).performClick()
        compose.waitUntil(30_000) {compose.onAllNodesWithText("会话记录").fetchSemanticsNodes().isNotEmpty()}
        val first="trial-anchor.vnext.20190102.kildare-stop-progress"
        compose.onNodeWithTag("review-content").performScrollToNode(hasTestTag(first))
        compose.onNodeWithTag(first).assertIsEnabled().performClick()
        try {
            compose.waitUntil(10_000) {
                if(compose.onAllNodesWithText("操作未完成",substring=true).fetchSemanticsNodes().isNotEmpty())
                    error(compose.onRoot().printToString())
                compose.onAllNodesWithText("试次详情").fetchSemanticsNodes().isNotEmpty()
            }
        } catch(error: Throwable) {
            File(validation,"ui-trial-failure.txt").writeText(compose.onRoot().printToString())
            File(validation,"ui-trial-failure.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
            throw error
        }
        compose.onNodeWithTag("review-content").performScrollToNode(hasText("当前图像"))
        compose.waitUntil(10_000) {compose.onAllNodesWithContentDescription("该试次的实际请求图像").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("该试次的实际请求图像").assertIsDisplayed()
        File(validation,"ui-trial-image.png").outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,output)
        }
        compose.onNodeWithTag("review-content").performScrollToNode(hasText("查看请求原文"))
        compose.onNodeWithText("查看请求原文").performClick()
        compose.onNodeWithText("收起请求原文").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("会话记录").assertExists()
        compose.onNodeWithTag(first).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("研究会话").assertExists()
    }
}
