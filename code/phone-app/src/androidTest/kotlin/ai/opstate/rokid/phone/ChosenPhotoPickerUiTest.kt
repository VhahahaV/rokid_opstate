package ai.opstate.rokid.phone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.review.SessionReviewRepository
import ai.opstate.rokid.phone.ui.ChosenPhotoPicker
import ai.opstate.rokid.phone.ui.StudyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Choosing the picture a staged run is made on.
 *
 * The picker used to be a list of timestamps: the only way to find out what you had chosen was to
 * choose it and watch the chain run on it. What is asserted here is the thing that was missing - that
 * the photographs are shown, and that the run is offered after seeing one rather than on the first tap.
 */
@RunWith(AndroidJUnit4::class)
class ChosenPhotoPickerUiTest {
    @get:Rule val compose=createComposeRule()

    private fun photos(count: Int): List<CapturedPhoto> {
        val graph=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneApplication
        runBlocking { graph.storageReady.await() }
        return runBlocking {
            withContext(Dispatchers.IO) {
                val repository=SessionReviewRepository(graph.journal)
                graph.database.dao().sessions()
                    .firstNotNullOfOrNull {session->repository.photos(session.sessionId).takeIf {it.isNotEmpty()}}
                    .orEmpty()
            }
        }.take(count)
    }

    @Test fun thePickerShowsThePhotographsAndOnlyOffersTheRunOnceOneIsChosen() {
        val listed=photos(6)
        assertTrue("no photographs on this device to pick from",listed.isNotEmpty())
        var chosen: CapturedPhoto?=null
        compose.setContent {StudyTheme {ChosenPhotoPicker(listed,onDismiss={},choose={chosen=it})}}

        // The pictures themselves, not a list of times. Every tile carries the photograph's id.
        listed.forEach {photo->compose.onNodeWithTag("photo-${photo.id}").assertExists()}
        compose.onNodeWithTag("chosen-photo-grid").assertExists()
        // Nothing has run yet: tapping a picture opens it, it does not fire the chain.
        assertNull(chosen)
        compose.onNodeWithTag("chosen-photo-details").assertDoesNotExist()
        capture("chosen-photo-grid.png")

        compose.onNodeWithTag("photo-${listed.first().id}").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("chosen-photo-details").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("用这张跑一次").assertExists()
        assertNull(chosen)
        capture("chosen-photo-detail.png")

        compose.onNodeWithText("用这张跑一次").performClick()
        assertEquals(listed.first().id,chosen?.id)
    }

    /**
     * Kept where it can be pulled off the device, so the screen can be looked at and not only asserted.
     * The whole display rather than the composition's root: the picker is a dialog window, so the
     * compose tree the rule owns is the empty one behind it.
     */
    private fun capture(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),name)
            .outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    @Test fun goingBackReturnsToThePicturesWithoutChoosing() {
        val listed=photos(4)
        var chosen: CapturedPhoto?=null
        compose.setContent {StudyTheme {ChosenPhotoPicker(listed,onDismiss={},choose={chosen=it})}}
        compose.onNodeWithTag("photo-${listed.last().id}").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("chosen-photo-details").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("chosen-photo-grid").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("photo-${listed.last().id}").assertExists()
        assertNull(chosen)
    }
}
