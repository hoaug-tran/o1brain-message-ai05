package vn.uneti.ai05

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MessengerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun swipeArchivesAndDeleteStillRequiresConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = MessengerController.get(context)
        controller.selectMode("observer")
        val store = MessageStore(context)
        val address = "AI05 swipe fixture " + java.util.UUID.randomUUID().toString()
        store.storeObserved(address, "Synthetic swipe-only message", System.currentTimeMillis())
        store.flag(store.key(address), "archived", false)
        controller.refresh()
        try {
            compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == address }
            }
            compose
                .onNodeWithTag("conversation_${store.key(address)}")
                .performScrollTo()
                .performTouchInput { swipeRight() }
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == address && it.archived }
            }
            compose.onNodeWithTag("conversation_filter").performClick()
            compose.onNodeWithTag("filter_archive").performClick()
            compose
                .onNodeWithTag("conversation_${store.key(address)}")
                .performScrollTo()
                .performTouchInput { swipeLeft() }
            compose
                .onNodeWithText(context.getString(R.string.delete_confirmation))
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.onNodeWithText(address).assertIsDisplayed()
            org.junit.Assert.assertTrue(store.conversations().any { it.address == address })
            compose.onNodeWithTag("conversation_${store.key(address)}").performTouchInput {
                swipeRight()
            }
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == address && !it.archived }
            }
            compose.onNodeWithTag("conversation_filter").performClick()
            compose.onNodeWithTag("filter_inbox").performClick()
            compose
                .onNodeWithTag("conversation_${store.key(address)}")
                .performScrollTo()
                .performTouchInput { swipeLeft() }
            compose
                .onNodeWithText(context.getString(R.string.delete_action), useUnmergedTree = true)
                .performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.none { it.address == address }
            }
            org.junit.Assert.assertTrue(store.conversations().none { it.address == address })
        } finally {
            store.conversations().firstOrNull { it.address == address }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun navigationSearchAndSettingsDisclosure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = MessengerController.get(context)
        controller.selectMode("observer")
        val store = MessageStore(context)
        val sender = "AI05 UI contract " + java.util.UUID.randomUUID().toString()
        val body = "Synthetic UI-only message " + java.util.UUID.randomUUID().toString()
        val id = store.storeObserved(sender, body, System.currentTimeMillis())
        controller.refresh()
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations
                    .singleOrNull { it.address == sender }
                    ?.messages
                    ?.map { it.id } == listOf(id)
            }
            compose.onNodeWithTag("search").performTextInput("no-results-741203")
            compose.onNodeWithText(context.getString(R.string.empty_search)).assertIsDisplayed()
            compose
                .onNodeWithContentDescription(context.getString(R.string.clear_search))
                .performClick()
            compose.onNodeWithTag("nav_spam").performClick()
            compose.onNodeWithTag("nav_spam").assertIsSelected()
            compose.onNodeWithText(context.getString(R.string.spam_banner)).assertDoesNotExist()
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithText(context.getString(R.string.community_title)).assertDoesNotExist()
            compose
                .onNodeWithText(context.getString(R.string.privacy_messenger))
                .assertDoesNotExist()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(context.getString(R.string.privacy_title)))
            compose.onNodeWithText(context.getString(R.string.privacy_title)).performClick()
            compose
                .onNodeWithText(context.getString(R.string.privacy_messenger))
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.close)).performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(context.getString(R.string.mode)))
            compose.onNodeWithText(context.getString(R.string.mode)).performClick()
            compose
                .onNodeWithText(context.getString(R.string.choose_mode_title))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithTag("nav_inbox").performClick()
            compose.onNodeWithText(sender).performScrollTo().performClick()
            compose.onNodeWithText(body).performClick()
            compose.onNodeWithText(context.getString(R.string.forward_message)).performClick()
            compose.onNodeWithText(context.getString(R.string.recipient)).assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText(body)).assertIsDisplayed()
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun externalDraftAndNotificationSelectConversation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = MessengerController.get(context)
        controller.selectMode("observer")
        compose.setContent {
            LocTheme { MessengerApp(controller, 0, "AI05 draft", "Raw đ OTP 741203", handled = {}) }
        }
        compose.onNode(hasSetTextAction() and hasText("Raw đ OTP 741203")).assertIsDisplayed()
        compose
            .onNode(hasSetTextAction() and hasText("AI05 draft"))
            .performTextReplacement("AI05 revised recipient")
        compose
            .onNode(hasSetTextAction() and hasText("Raw đ OTP 741203"))
            .assertIsDisplayed()
            .performTextReplacement("Revised raw đ OTP 741203")
        compose
            .onNode(hasSetTextAction() and hasText("AI05 revised recipient"))
            .performTextReplacement("AI05 final recipient")
        compose
            .onNode(hasSetTextAction() and hasText("Revised raw đ OTP 741203"))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithTag("nav_inbox").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(context.getString(R.string.new_conversation))
            .performClick()
        compose
            .onNodeWithText(context.getString(R.string.message_content))
            .performTextInput("Body before recipient")
        compose
            .onNodeWithText(context.getString(R.string.recipient))
            .performTextInput("AI05 body-first recipient")
        compose.onNode(hasSetTextAction() and hasText("Body before recipient")).assertIsDisplayed()
    }
}
