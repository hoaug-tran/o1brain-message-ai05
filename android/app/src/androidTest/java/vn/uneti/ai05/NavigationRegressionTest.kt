package vn.uneti.ai05

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class NavigationRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readSpamClearsUnreadBadgeAndBackReturnsToSpam() {
        val store = MessageStore(context)
        val sender = "AI05 navigation regression " + java.util.UUID.randomUUID().toString()
        val id =
            store.storeObserved(
                sender,
                "Synthetic navigation test only",
                System.currentTimeMillis(),
            )
        store.classify(id, "spam", "test-only")
        val controller = MessengerController.get(context)
        controller.selectMode("observer")
        controller.refresh()
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender }
            }
            compose.onNodeWithTag("nav_spam").performClick()
            compose.onNodeWithText(sender).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.first { it.address == sender }.unread == 0
            }
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithTag("nav_spam").assertIsSelected()
            val unread = controller.state.value.conversations.filter { it.spam }.sumOf { it.unread }
            if (unread == 0)
                compose
                    .onNode(
                        hasAnyAncestor(hasTestTag("nav_spam")) and
                            hasText(
                                controller.state.value.conversations.count { it.spam }.toString()
                            )
                    )
                    .assertDoesNotExist()
            else
                compose
                    .onNode(hasAnyAncestor(hasTestTag("nav_spam")) and hasText(unread.toString()))
                    .assertIsDisplayed()
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun backFromConversationPreservesInboxScrollPosition() {
        val store = MessageStore(context)
        val stamp = System.currentTimeMillis()
        repeat(25) {
            store.storeObserved(
                "AI05 scroll regression $it",
                "Synthetic scroll test only",
                stamp + it,
            )
        }
        val controller = MessengerController.get(context)
        controller.selectMode("observer")
        controller.refresh()
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any {
                    it.address == "AI05 scroll regression 0"
                }
            }
            compose
                .onNodeWithTag("inbox_list")
                .performScrollToNode(hasText("AI05 scroll regression 3"))
            compose.onNodeWithText("AI05 scroll regression 3").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithTag("nav_inbox").assertIsSelected()
            compose.onNodeWithText("AI05 scroll regression 3").assertIsDisplayed()
        } finally {
            store
                .conversations()
                .filter { it.address.startsWith("AI05 scroll regression ") }
                .forEach { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }
}
