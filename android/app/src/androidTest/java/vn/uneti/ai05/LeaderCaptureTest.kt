package vn.uneti.ai05

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class LeaderCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun text(id: Int) = context.getString(id)

    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        val variant = InstrumentationRegistry.getArguments().getString("capture_variant") ?: "light"
        require(variant in setOf("light", "dark", "large"))
        val folder = File(context.getExternalFilesDir(null), "leader-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$variant-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun captureCurrentProductWithSyntheticFixtures() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("ui_capture") == "true"
        )
        check(
            context
                .getSharedPreferences("messenger_settings", 0)
                .getBoolean("fixture_preview", false)
        )
        val store = MessageStore(context)
        check(store.conversations().all { it.address.startsWith("AI05 Test") }) {
            "Capture requires isolated synthetic inbox"
        }
        store.saveDraft("AI05 Test A", "")
        val controller = MessengerController.get(context)
        controller.refresh()
        compose.waitUntil(10_000) { !controller.state.value.loading }
        compose.onNodeWithTag("nav_inbox").performClick()
        snap("01-inbox")
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_unread").performClick()
        snap("02-unread")
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_trusted").performClick()
        snap("03-priority")
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_archive").performClick()
        snap("04-archive")
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_inbox").performClick()
        compose.onNodeWithTag("search").performTextInput("no-fixture-matches-741203")
        snap("05-empty-search")
        compose.onNodeWithContentDescription(text(R.string.clear_search)).performClick()
        compose.onNodeWithText("AI05 Test A").performScrollTo().performClick()
        snap("06-chat")
        compose.onNodeWithContentDescription(text(R.string.conversation_options)).performClick()
        snap("07-conversation-menu")
        back()
        compose
            .onNodeWithText("Synthetic test: meeting at 9 tomorrow. See you there.")
            .performClick()
        snap("08-message-actions")
        compose.onNodeWithText(text(R.string.forward_message)).performClick()
        snap("09-forward")
        compose
            .onNode(
                hasSetTextAction() and
                    hasText("Synthetic test: meeting at 9 tomorrow. See you there.")
            )
            .performClick()
        snap("10-composer-focus")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithContentDescription(text(R.string.new_conversation)).performClick()
        snap("11-compose-empty")
        compose
            .onNodeWithText(text(R.string.message_content))
            .performTextInput("Tin nhắn kiểm thử giao diện, chưa gửi.")
        compose.onNodeWithText(text(R.string.recipient)).performTextInput("+1 202-555-0147")
        snap("12-compose-draft")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("nav_spam").performClick()
        snap("13-spam")
        compose.onNodeWithText("AI05 Test Spam").performClick()
        snap("14-spam-chat")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("nav_inbox").performClick()
        compose
            .onNodeWithTag("conversation_${store.key("AI05 Test B")}")
            .performScrollTo()
            .performTouchInput { swipeRight() }
        compose.waitUntil(10_000) {
            controller.state.value.conversations.any { it.address == "AI05 Test B" && it.archived }
        }
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_archive").performClick()
        snap("15-swipe-archived")
        compose
            .onNodeWithTag("conversation_${store.key("AI05 Test B")}")
            .performScrollTo()
            .performTouchInput { swipeLeft() }
        compose.onNodeWithText(text(R.string.delete_confirmation)).assertIsDisplayed()
        snap("16-swipe-delete-confirmation")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithTag("conversation_${store.key("AI05 Test B")}").performTouchInput {
            swipeRight()
        }
        compose.waitUntil(10_000) {
            controller.state.value.conversations.any { it.address == "AI05 Test B" && !it.archived }
        }
        compose.onNodeWithTag("conversation_filter").performClick()
        compose.onNodeWithTag("filter_inbox").performClick()
        compose.onNodeWithTag("nav_settings").performClick()
        snap("17-settings")
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText(text(R.string.privacy_title)))
        compose.onNodeWithText(text(R.string.privacy_title))
        snap("18-settings-bottom")
        compose.onNodeWithText(text(R.string.privacy_title)).performClick()
        snap("19-privacy")
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText(text(R.string.filter_details)))
        compose.onNodeWithText(text(R.string.filter_details)).performClick()
        snap("20-model-details")
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.mode)))
        compose.onNodeWithText(text(R.string.mode)).performClick()
        snap("21-modes-top")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        snap("22-modes-bottom")
        compose.onNodeWithText(text(R.string.how_observer_works)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.observer_storage)).performScrollTo()
        snap("23-observer-details")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        controller.reportError("ui_test_injected_failure")
        compose.onNodeWithText(text(R.string.operation_failed)).assertIsDisplayed()
        snap("24-injected-error")
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.onNodeWithTag("nav_inbox").performClick()
        compose.onNodeWithTag("nav_check").performClick()
        snap("25-quick-check")
        compose.onNodeWithText(text(R.string.sample_prize)).performClick()
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("check_result").performScrollTo()
        snap("26-quick-result")
        compose
            .onNodeWithTag("check_input")
            .performScrollTo()
            .performTextReplacement("Xin chào, đây là tin nhắn tiếng Việt.")
        compose.onNodeWithTag("check_submit").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("check_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("check_error").assertDoesNotExist()
        snap("27-vietnamese")
        compose.onNodeWithTag("nav_settings").performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText(text(R.string.filter_details)))
        compose.onNodeWithText(text(R.string.filter_details)).performClick()
        compose.onNodeWithText(text(R.string.model_evaluation)).performScrollTo()
        snap("28-model-evidence")
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.onNodeWithTag("nav_inbox").performClick()
        store.close()
    }
}
