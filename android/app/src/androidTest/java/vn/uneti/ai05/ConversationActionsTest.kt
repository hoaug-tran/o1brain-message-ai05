package vn.uneti.ai05

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ConversationActionsTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun holdingConversationChangesActualUnreadFilter() {
        val store = MessageStore(context)
        val sender = "O1Brain unread " + UUID.randomUUID()
        val id = store.storeObserved(sender, "Synthetic read contract", System.currentTimeMillis())
        store.classify(id, "ham", "test-only", "normal")
        store.markRead(store.conversations().single { it.address == sender })
        val controller = MessengerController.get(context)
        controller.refresh()
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender }
            }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
            compose
                .onNodeWithTag("conversation_${store.key(sender)}")
                .performScrollTo()
                .performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(R.string.mark_unread)).performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.unread == 1 }
            }
            compose.onNodeWithTag("conversation_filter").performClick()
            compose.onNodeWithTag("filter_unread").performClick()
            compose
                .onNodeWithTag("conversation_${store.key(sender)}")
                .performScrollTo()
                .performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(R.string.mark_read)).performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.unread == 0 }
            }
            compose.onNodeWithText(sender).assertDoesNotExist()
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun senderRuleSurvivesHistoryDeletionAndCanBeRemoved() {
        val store = MessageStore(context)
        val sender = "O1Brain filter " + UUID.randomUUID()
        try {
            store.storeObserved(sender, "Synthetic ordinary message", System.currentTimeMillis())
            store.flag(store.key(sender), "blocked", true, sender)
            assertTrue(store.conversations().single { it.address == sender }.spam)
            assertEquals(
                sender,
                store.senderFilters().single { it.key == store.key(sender) }.address,
            )
            store.delete(store.conversations().single { it.address == sender })
            assertTrue(store.senderFilters().any { it.key == store.key(sender) })
            store.storeObserved(
                sender,
                "Synthetic new ordinary message",
                System.currentTimeMillis() + 1,
            )
            assertTrue(store.conversations().single { it.address == sender }.spam)
            store.flag(store.key(sender), "trusted", true)
            assertFalse(store.conversations().single { it.address == sender }.spam)
            assertFalse(store.senderFilters().any { it.key == store.key(sender) })
        } finally {
            store.flag(store.key(sender), "blocked", false)
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            store.close()
        }
    }

    @Test
    fun keyboardBackspaceAndSelectionDeletionKeepEditableState() {
        compose.setContent { LocTheme { QuickCheckScreen() } }
        val input = compose.onNodeWithTag("check_input")
        input.performTextInput("abc")
        input.performKeyInput { pressKey(Key.Backspace) }
        input.assert(hasText("ab", substring = false))
        input.performTextReplacement("Xin chào")
        input.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
            pressKey(Key.Backspace)
        }
        input.assert(hasText("", substring = false))
        input.performTextInput("Another draft")
        compose
            .onNodeWithContentDescription(context.getString(R.string.clear_message_input))
            .performClick()
        input.assert(hasText("", substring = false))
        compose.onNodeWithTag("check_submit").assertIsNotEnabled()
    }
}
