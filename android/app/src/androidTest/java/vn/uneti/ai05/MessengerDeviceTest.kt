package vn.uneti.ai05

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessengerDeviceTest {
    @Test
    fun encryptedConversationsDraftsReadArchiveAndSpamOverride() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MessageStore(context)
        val recipient = "AI05-TEST-ONLY-" + java.util.UUID.randomUUID().toString()
        val original = "Synthetic contract-only SMS with private marker 741203"
        val stamp = System.currentTimeMillis()
        try {
            val id = store.storeObserved(recipient, original, stamp)
            store.storeObserved(recipient, original, stamp)
            store.classify(id, "spam", "contract-test-version")
            var conversation = store.conversations().single { it.key == store.key(recipient) }
            assertEquals(1, conversation.messages.size)
            assertEquals(original, conversation.latest.body)
            assertTrue(conversation.spam)
            assertEquals("spam", conversation.latest.modelLabel)
            assertEquals("contract-test-version", conversation.latest.modelVersion)
            assertNull(conversation.spamOverride)
            store.flag(conversation.key, "spam", false)
            conversation = store.conversations().single { it.key == conversation.key }
            assertFalse("User correction must override classifier", conversation.spam)
            assertEquals(false, conversation.spamOverride)
            assertEquals("spam", conversation.latest.modelLabel)
            store.flag(conversation.key, "archived", true)
            assertTrue(store.conversations().single { it.key == conversation.key }.archived)
            store.saveDraft(recipient, "Unchanged draft https://example.invalid/741203")
            assertEquals("Unchanged draft https://example.invalid/741203", store.draft(recipient))
            store.markRead(conversation)
            assertEquals(0, store.conversations().single { it.key == conversation.key }.unread)
            val bytes =
                context
                    .getDatabasePath("messages_private.db")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains(original))
            assertFalse(bytes.contains(recipient))
            assertFalse(bytes.contains("741203"))
            store.delete(conversation)
            assertTrue(store.conversations().none { it.key == conversation.key })
        } finally {
            try {
                store
                    .conversations()
                    .firstOrNull { it.address == recipient }
                    ?.let { store.delete(it) }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun previewFixturesOnlyWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("fixture_preview") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MessageStore(context)
        store
            .conversations()
            .filter {
                it.address in
                    setOf(
                        "AI05 UI contract",
                        "AI05 Test A",
                        "AI05 Test B",
                        "AI05 Test Spam",
                        "AI05 Test Priority",
                        "AI05 Test Archive",
                    )
            }
            .forEach {
                check(
                    it.messages.all { message -> message.systemId == null && message.mmsId == null }
                )
                store.delete(it)
            }
        val now = System.currentTimeMillis()
        val fixtures =
            listOf(
                "AI05 Test A" to "Synthetic test: meeting at 9 tomorrow. See you there.",
                "AI05 Test B" to "Synthetic test: your study group has updated the timetable.",
                "AI05 Test Spam" to "Synthetic test: Congratulations claim your free prize now!",
            )
        fixtures.forEachIndexed { index, (sender, body) ->
            val id = store.storeObserved(sender, body, now - index * 60000)
            AppRuntime.get(context).assessMessage(body)?.let {
                store.classify(id, it.prediction.label, it.version, it.decision, it.reasons)
            }
        }
        store.storeObserved("AI05 Test A", "Synthetic test: can we meet tomorrow?", now - 3600000)
        store.storeObserved(
            "AI05 Test A",
            "Synthetic test: yes, see you in the library.",
            now - 1800000,
        )
        store.storeObserved(
            "AI05 Test Priority",
            "Tin nhắn kiểm thử: lịch trao đổi giao diện với nhóm.",
            now - 240000,
        )
        store.flag(store.key("AI05 Test Priority"), "trusted", true)
        store.storeObserved(
            "AI05 Test Archive",
            "Synthetic test: archived project discussion.",
            now - 300000,
        )
        store.flag(store.key("AI05 Test Archive"), "archived", true)
        context
            .getSharedPreferences("messenger_settings", 0)
            .edit()
            .putString("mode", "observer")
            .putBoolean("fixture_preview", true)
            .commit()
        MessengerController.get(context).selectMode("observer")
        store.close()
    }
}
