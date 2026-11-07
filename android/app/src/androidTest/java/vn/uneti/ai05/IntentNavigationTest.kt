package vn.uneti.ai05

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IntentNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun consumedNotificationDoesNotReopenChatAfterBackOrRecreation() {
        val context = compose.activity
        val store = MessageStore(context)
        val sender = "O1Brain intent " + UUID.randomUUID()
        val id =
            store.storeObserved(sender, "Synthetic intent contract", System.currentTimeMillis())
        store.classify(id, "ham", "test-only", "normal")
        val controller = MessengerController.get(context)
        controller.refresh()
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender }
            }
            val thread = store.key(sender)
            compose.runOnUiThread {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("thread_key", thread)
                )
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithContentDescription(context.getString(R.string.back))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithContentDescription(context.getString(R.string.back))
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("nav_inbox").assertIsSelected()
            compose.runOnIdle {
                assertNull(compose.activity.intent.getStringExtra("thread_key"))
                assertEquals(Intent.ACTION_MAIN, compose.activity.intent.action)
                assertTrue(compose.activity.intent.hasCategory(Intent.CATEGORY_LAUNCHER))
                assertNull(compose.activity.intent.data)
            }
            controller.refresh()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("nav_inbox").assertIsSelected()
            compose
                .onNodeWithContentDescription(context.getString(R.string.back))
                .assertDoesNotExist()
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun priorityNoticeIsDiscardedWhenLeavingItsConversation() {
        val context = compose.activity
        val store = MessageStore(context)
        val sender = "O1Brain notice " + UUID.randomUUID()
        val id =
            store.storeObserved(sender, "Synthetic notice contract", System.currentTimeMillis())
        store.classify(id, "ham", "test-only", "normal")
        val controller = MessengerController.get(context)
        controller.refresh()
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender }
            }
            compose
                .onNodeWithTag("conversation_${store.key(sender)}")
                .performScrollTo()
                .performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.trust_sender))
                .performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.trusted }
            }
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.new_conversation))
                .performClick()
            compose
                .onNodeWithText(context.getString(R.string.priority_added, sender))
                .assertDoesNotExist()
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }
}
