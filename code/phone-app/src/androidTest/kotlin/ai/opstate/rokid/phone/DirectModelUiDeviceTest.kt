package ai.opstate.rokid.phone

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DirectModelUiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun savesAScopedKeyThenValidatesTheRealModelFromSettings() {
        assumeTrue("Explicit API settings check only",InstrumentationRegistry.getArguments().getString("direct_api_ui")=="true")
        val app=compose.activity.application as PhoneApplication
        val previous=app.credentials.read("model-api-key")
        assumeTrue("A real model Key has already been securely imported",previous!=null)
        val scope=app.modelConfig().credentialScope()
        try {
            compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
            compose.onNodeWithTag("operator-content-3").performScrollToNode(hasText("模型 ID"))
            val keyNode=compose.onNode(hasSetTextAction() and hasText("API Key",substring=true))
            keyNode.performScrollTo().performTextReplacement("fixture-ui-key-not-for-network")
            compose.runOnIdle {
                compose.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)
            }
            compose.onNodeWithText("保存模型与 Key").performScrollTo().assertIsDisplayed().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {app.credentials.modelApiKey(scope)=="fixture-ui-key-not-for-network"}
            assertTrue("Key entry must be cleared after save",compose.onAllNodes(hasSetTextAction() and hasText("fixture-ui-key-not-for-network")).fetchSemanticsNodes().isEmpty())
        } catch(error: Throwable) {
            File(app.filesDir,"validation/direct-api-ui-failure.txt").writeText(compose.onRoot().printToString())
            throw error
        } finally {
            app.credentials.put("model-api-key",previous!!)
            compose.activityRule.scenario.recreate()
        }
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("operator-content-3").performScrollToNode(hasText("验证已保存的 API Key"))
        compose.waitUntil(5000) {compose.onAllNodes(hasText("验证已保存的 API Key") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("验证已保存的 API Key").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(25000) {compose.onAllNodesWithText("API Key 验证通过").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("API Key 验证通过").performScrollTo()
        compose.waitUntil(7000) {compose.onAllNodesWithText("API Key 可用，手机已直连模型").fetchSemanticsNodes().isEmpty()}
        val directory=File(app.filesDir,"validation").apply {mkdirs()}
        File(directory,"direct-api-config-ui.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
