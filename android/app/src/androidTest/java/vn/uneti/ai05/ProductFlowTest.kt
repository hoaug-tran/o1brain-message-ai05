package vn.uneti.ai05

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductFlowTest {
    @Test
    fun switchingToSingleTrainDiscardsOldAutomaticSpamButKeepsUserRules() {
        val store = MessageStore(context)
        val sender = "AI05 migration single train " + java.util.UUID.randomUUID()
        val id = store.storeObserved(sender, "See you tomorrow.", System.currentTimeMillis())
        try {
            store.classify(id, "spam", "policy-3:expanded", "spam", listOf("content_model"))
            var conversation = store.conversations().single { it.address == sender }
            assertFalse(conversation.spam)
            assertNull(conversation.latest.modelVersion)
            assertEquals("See you tomorrow.", conversation.latest.body)
            store.flag(conversation.key, "spam", true)
            assertTrue(store.conversations().single { it.address == sender }.spam)
            store.flag(conversation.key, "spam", false)
            store.classify(id, "ham", "$PRODUCT_POLICY_VERSION:test", "normal")
            conversation = store.conversations().single { it.address == sender }
            assertFalse(conversation.spam)
            assertEquals("$PRODUCT_POLICY_VERSION:test", conversation.latest.modelVersion)
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            store.close()
        }
    }

    @Test
    fun themeAndAutomaticSpamSettingsPersistAndChangeRealSorting() {
        val appearance = context.getSharedPreferences("appearance", 0)
        val originalTheme = appearance.getString("theme", "system")
        val controller = MessengerController.get(context)
        val originalAuto =
            context.getSharedPreferences("messenger_settings", 0).getBoolean("auto_spam", true)
        val store = MessageStore(context)
        val sender = "AI05 settings sorting synthetic"
        val id =
            store.storeObserved(sender, "Synthetic sorting contract", System.currentTimeMillis())
        store.classify(id, "spam", "test-only", "spam", listOf("content_model"))
        controller.setAutoSpam(true)
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.spam }
            }
            compose.onNodeWithTag("nav_settings").performClick()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(context.getString(R.string.appearance)))
            compose.onNodeWithText(context.getString(R.string.appearance)).performClick()
            compose
                .onNode(
                    hasText(context.getString(R.string.theme_dark)) and
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.Role,
                            androidx.compose.ui.semantics.Role.RadioButton,
                        )
                )
                .performClick()
            assertEquals("dark", appearance.getString("theme", null))
            compose.onNodeWithText(context.getString(R.string.appearance)).performClick()
            compose
                .onNode(
                    hasText(context.getString(R.string.theme_light)) and
                        SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.Role,
                            androidx.compose.ui.semantics.Role.RadioButton,
                        )
                )
                .performClick()
            assertEquals("light", appearance.getString("theme", null))
            compose.onNodeWithTag("auto_spam_switch").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && !it.spam }
            }
            assertFalse(
                context.getSharedPreferences("messenger_settings", 0).getBoolean("auto_spam", true)
            )
            compose.onNodeWithTag("auto_spam_switch").performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.spam }
            }
        } finally {
            appearance.edit().putString("theme", originalTheme).commit()
            controller.setAutoSpam(originalAuto)
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun incomingControllerActuallyPersistsClassifiesAndMarksRead() {
        val store = MessageStore(context)
        val controller = MessengerController.get(context)
        val sender = "AI05 incoming integration synthetic"
        assertTrue(store.conversations().none { it.address == sender })
        val finished = java.util.concurrent.CountDownLatch(1)
        try {
            if (!store.isDefault()) {
                controller.selectMode("observer")
                controller.setMonitoring(true)
            }
            controller.receive(
                sender,
                "Congratulations! You've been selected to receive a $2,500 reward. Reply CLAIM to collect your prize.",
                System.currentTimeMillis(),
                -1,
                store.isDefault(),
            ) {
                finished.countDown()
            }
            assertTrue(finished.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val conversation = store.conversations().single { it.address == sender }
            assertTrue(conversation.spam)
            assertEquals(1, conversation.unread)
            assertTrue(conversation.latest.modelVersion!!.startsWith("$PRODUCT_POLICY_VERSION:"))
            assertTrue(conversation.latest.riskReasons.contains("content_model"))
            controller.open(conversation)
            val deadline = System.currentTimeMillis() + 10_000
            while (
                store.conversations().single { it.address == sender }.unread > 0 &&
                    System.currentTimeMillis() < deadline
            ) Thread.sleep(30)
            assertEquals(0, store.conversations().single { it.address == sender }.unread)
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun outgoingMessagesAlignRightAndExposeActualSendStates() {
        val now = System.currentTimeMillis()
        val messages =
            listOf(
                MessageItem(
                    "incoming",
                    "AI05 bubble alignment synthetic",
                    "Incoming message",
                    now,
                    false,
                    true,
                    "received",
                ),
                MessageItem(
                    "sending",
                    "AI05 bubble alignment synthetic",
                    "Outgoing message",
                    now + 1,
                    true,
                    true,
                    "sending",
                ),
                MessageItem(
                    "sent",
                    "AI05 bubble alignment synthetic",
                    "Sent message",
                    now + 2,
                    true,
                    true,
                    "sent",
                ),
                MessageItem(
                    "failed",
                    "AI05 bubble alignment synthetic",
                    "Failed message",
                    now + 3,
                    true,
                    true,
                    "failed",
                ),
            )
        val conversation =
            Conversation(
                "synthetic-bubble-test",
                "AI05 bubble alignment synthetic",
                messages,
                false,
                false,
                false,
            )
        compose.setContent {
            LocTheme {
                ChatScreen(
                    MessengerController.get(context),
                    conversation.address,
                    {},
                    conversation,
                    true,
                    true,
                    androidx.compose.ui.Modifier,
                    "",
                    {},
                    {},
                )
            }
        }
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithText("Incoming message").performScrollTo()
        assertTrue(
            compose.onNodeWithText("Incoming message").fetchSemanticsNode().boundsInRoot.center.x <
                width / 2
        )
        compose.onNodeWithText("Outgoing message").performScrollTo()
        assertTrue(
            compose.onNodeWithText("Outgoing message").fetchSemanticsNode().boundsInRoot.center.x >
                width / 2
        )
        for (status in listOf(R.string.sending, R.string.sent, R.string.send_failed)) compose
            .onNode(hasText(context.getString(status), substring = true))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.not_evaluated)).assertDoesNotExist()
        compose
            .onNodeWithContentDescription(context.getString(R.string.send_message))
            .assertIsNotEnabled()
    }

    @Test
    fun migrationPreservesMessagesAndSeparatesOldUncertainLabels() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, body TEXT)")
            db.execSQL("INSERT INTO messages VALUES('test','encrypted-marker')")
            db.execSQL(
                "CREATE TABLE classifications(message_id TEXT PRIMARY KEY,label TEXT NOT NULL,model_version TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO classifications VALUES('test','spam','old')")
            db.execSQL(
                "CREATE TABLE threads(thread_key TEXT PRIMARY KEY,archived INTEGER DEFAULT 0,spam INTEGER DEFAULT -1,trusted INTEGER DEFAULT 0,draft TEXT)"
            )
            db.execSQL(
                "INSERT INTO threads(thread_key,spam,trusted,draft) VALUES('keep',0,1,'encrypted-draft')"
            )
            MessageStore(context).use { it.onUpgrade(db, 1, 3) }
            db.rawQuery(
                    "SELECT blocked,sender_address,draft,spam,trusted FROM threads WHERE thread_key='keep'",
                    null,
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals("encrypted-draft", cursor.getString(2))
                    assertEquals(0, cursor.getInt(3))
                    assertEquals(1, cursor.getInt(4))
                }
            db.rawQuery("SELECT body FROM messages", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("encrypted-marker", it.getString(0))
            }
            db.rawQuery("SELECT decision FROM classifications", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("review", it.getString(0))
            }
            db.rawQuery("SELECT COUNT(*) FROM reports", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun automaticAnalysisPriorityAndSpamCorrectionPersist() {
        val store = MessageStore(context)
        val sender = "AI05 product flow synthetic"
        val body =
            "Congratulations! You've been selected to receive a $2,500 reward. Reply CLAIM to collect your prize."
        val id = store.storeObserved(sender, body, System.currentTimeMillis())
        val controller = MessengerController.get(context)
        controller.refresh()
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (
                controller.state.value.conversations.none {
                    it.address == sender &&
                        it.latest.modelVersion?.startsWith("$PRODUCT_POLICY_VERSION:") == true
                } && System.currentTimeMillis() < deadline
            ) Thread.sleep(50)
            var conversation = store.conversations().single { it.address == sender }
            assertTrue(conversation.spam)
            assertEquals("spam", conversation.latest.riskDecision)
            assertTrue(conversation.latest.riskReasons.contains("content_model"))
            store.storeObserved(
                sender,
                "See you at the library tomorrow.",
                System.currentTimeMillis() + 1000,
            )
            conversation = store.conversations().single { it.address == sender }
            assertTrue(
                "An ordinary follow-up must not erase earlier high-risk assessment",
                conversation.spam,
            )
            store.flag(conversation.key, "trusted", true)
            conversation = store.conversations().single { it.address == sender }
            assertFalse(conversation.spam)
            assertTrue(
                visibleConversations(listOf(conversation), "trusted", "").contains(conversation)
            )
            store.flag(conversation.key, "spam", true)
            assertTrue(store.conversations().single { it.address == sender }.spam)
            store.flag(conversation.key, "spam", false)
            assertFalse(store.conversations().single { it.address == sender }.spam)
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }

    @Test
    fun repeatedBackPriorityAndReportConsentWork() {
        val store = MessageStore(context)
        val sender = "AI05 rapid back synthetic " + java.util.UUID.randomUUID().toString()
        val body =
            "Meet outside the library at nine tomorrow. " + java.util.UUID.randomUUID().toString()
        store.storeObserved(sender, body, System.currentTimeMillis())
        val controller = MessengerController.get(context)
        controller.refresh()
        compose.setContent { LocTheme { MessengerApp(controller, 0, null, handled = {}) } }
        try {
            compose.waitUntil(15_000) {
                controller.state.value.conversations.any { it.address == sender }
            }
            repeat(8) { index ->
                compose.onNodeWithText(sender).performScrollTo().performClick()
                if (index % 2 == 0)
                    compose
                        .onNodeWithContentDescription(context.getString(R.string.back))
                        .performClick()
                else {
                    InstrumentationRegistry.getInstrumentation()
                        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                }
                compose.onNodeWithTag("nav_inbox").assertIsSelected()
            }
            compose.onNodeWithText(sender).performScrollTo().performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.trust_sender))
                .performClick()
            compose.waitUntil(10_000) {
                controller.state.value.conversations.any { it.address == sender && it.trusted }
            }
            compose.onNodeWithText(body).performClick()
            compose.onNodeWithText(context.getString(R.string.report_message)).performClick()
            compose.onNodeWithText(context.getString(R.string.report_scope)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.report_save)).assertIsEnabled()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithTag("conversation_filter").performClick()
            compose.onNodeWithTag("filter_trusted").performClick()
            compose.onNodeWithText(sender).assertIsDisplayed()
            compose.onNodeWithText(sender).performClick()
            compose.onNodeWithText(body).performClick()
            compose.onNodeWithText(context.getString(R.string.move_spam)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("nav_spam").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("nav_spam").assertIsSelected()
            compose.onNodeWithText(sender).performScrollTo().performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.conversation_options))
                .performClick()
            compose.onNodeWithText(context.getString(R.string.not_spam)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("nav_inbox").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("nav_inbox").assertIsSelected()
            compose.onNodeWithText(sender).assertIsDisplayed()
            assertFalse(store.conversations().single { it.address == sender }.trusted)
        } finally {
            store.conversations().firstOrNull { it.address == sender }?.let { store.delete(it) }
            controller.refresh()
            store.close()
        }
    }
}
