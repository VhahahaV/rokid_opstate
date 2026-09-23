package ai.opstate.rokid.phone

import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.ui.RecentPhotos
import ai.opstate.rokid.phone.ui.StudyTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every photograph says what the glasses' front end decided about it.
 *
 * A service session uploads every frame it captures and the front end looks at each one, so a strip of
 * thumbnails is a list of verdicts as much as a list of pictures: which frames the algorithm fired on,
 * which it passed over, and which carry no verdict at all because they came from an imported record or
 * were shot with the front end switched off. Without the mark, "触发" and "未触发" look identical, and
 * the only way to tell them apart is to open every picture.
 */
@RunWith(AndroidJUnit4::class)
class TriggerVerdictUiTest {
    @get:Rule val compose=createComposeRule()

    private fun photo(id: String,at: Long,vararg metadata: Pair<String,JsonElement>)=
        CapturedPhoto(id,File("/nonexistent/$id.jpg"),at,at,JsonObject(metadata.toMap()))

    private fun triggered(id: String,at: Long)=photo(id,at,
        "trigger_types" to JsonArray(listOf(JsonPrimitive("CVTT_THETA_ON"))),
        "front_end" to JsonPrimitive("cvtt_frontend_v1_calibrated"),"front_end_score" to JsonPrimitive(1.98))

    private fun passedOver(id: String,at: Long)=photo(id,at,
        "trigger_types" to JsonArray(emptyList()),
        "front_end" to JsonPrimitive("cvtt_frontend_v1_calibrated"),"front_end_score" to JsonPrimitive(0.71))

    private fun manual(id: String,at: Long)=photo(id,at,
        "trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL"))),
        "front_end" to JsonPrimitive("BYPASSED_MANUAL"))

    @Test fun aTriggeredFrameAnUntriggeredOneAndAHistoryFrameEachSayWhatTheyAre() {
        val photos=listOf(
            triggered("fired",3_000),
            passedOver("quiet",2_000),
            manual("manual",1_500),
            // An imported history frame: the front end never saw it.
            photo("imported",1_000,"source" to JsonPrimitive("LOCAL_COLLECTION_HISTORY")))
        compose.setContent {StudyTheme {RecentPhotos(photos)}}
        compose.onAllNodesWithText("触发").onFirst().assertExists()
        compose.onAllNodesWithText("未触发").onFirst().assertExists()
        compose.onAllNodesWithText("手动触发").onFirst().assertExists()
        compose.onAllNodesWithText("历史").onFirst().assertExists()
        // The verdict is on the frame, not on the strip: one picture per mark.
        compose.onNodeWithTag("photo-fired").assertExists()
        compose.onNodeWithTag("photo-quiet").assertExists()
    }
}
