package ai.opstate.rokid.phone

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real authenticated contract through Settings; restores the operator's original configuration. */
@RunWith(AndroidJUnit4::class)
class FrozenModelUiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun settingsDisplaysTheActualFrozenProviderParameters() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("harness_endpoint");val expected=args.getString("expected_model")
        assumeTrue("Run explicitly with harness_endpoint and expected_model",endpoint!=null && expected!=null)
        val app=compose.activity.application as PhoneApplication
        assumeTrue("Only inspect configuration while idle",app.studyRuntime.state.value.sessionId==null && !app.datasetReplay.state.value.running)
        val previousEndpoint=app.settings.getString("harness_url",null)
        val previousModel=app.settings.getString("expected_model_id",null)
        val previousToken=app.credentials.read("harness-token")
        val validation=File(app.filesDir,"validation")
        try {
            app.credentials.put("harness-token",File(validation,"frozen-harness-access-token").readText().trim().toByteArray())
            check(app.settings.edit().putString("harness_url",endpoint).putString("expected_model_id",expected).commit())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
            compose.onNodeWithTag("operator-content-3").performScrollToNode(hasText("读取并校验"))
            compose.waitUntil(10_000) {compose.onAllNodes(hasText("读取并校验") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("读取并校验").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) {compose.onAllNodesWithText("rokid-v5-qwen-flash-v1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("chat_completions").assertExists()
            compose.onNodeWithText("512").assertExists()
            compose.onNodeWithText("Temperature").assertExists()
            compose.onNodeWithText("Seed").assertExists()
            compose.onNodeWithText("参数快照",substring=true).performScrollTo().assertIsDisplayed()
            compose.waitUntil(7000) {compose.onAllNodesWithText("服务已连接，模型配置校验通过").fetchSemanticsNodes().isEmpty()}
            File(validation,"frozen-model-config-ui.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        } catch(error: Throwable) {
            File(validation,"frozen-model-ui-failure.txt").writeText(compose.onRoot().printToString())
            File(validation,"frozen-model-ui-failure.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            throw error
        } finally {
            check(app.settings.edit().putString("harness_url",previousEndpoint).putString("expected_model_id",previousModel).commit())
            if(previousToken==null) app.credentials.remove("harness-token") else app.credentials.put("harness-token",previousToken)
            compose.activityRule.scenario.recreate()
        }
    }
}
