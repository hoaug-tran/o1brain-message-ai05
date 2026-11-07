package vn.uneti.ai05

import android.provider.Telephony
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FilteringDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun latestIncomingAssessmentAndManualSenderRuleHaveDifferentScope() {
        val store = MessageStore(context)
        val sender = "AI05 latest fixture " + UUID.randomUUID()
        val stamp = System.currentTimeMillis()
        val key = store.key(sender)
        try {
            val old = store.storeObserved(sender, "Old synthetic promotion", stamp)
            store.classify(old, "spam", "contract-test", "spam")
            assertTrue(store.conversations().first { it.key == key }.spam)
            val fresh = store.storeObserved(sender, "New synthetic normal message", stamp + 1)
            store.classify(fresh, "ham", "contract-test", "normal")
            assertFalse("Old prediction must not hide later normal SMS", store.conversations().first { it.key == key }.spam)
            store.flag(key, "blocked", true, sender)
            assertTrue("Explicit sender rule covers new messages", store.conversations().first { it.key == key }.spam)
            store.flag(key, "archived", true)
            store.flag(key, "spam", false)
            val restored = store.conversations().first { it.key == key }
            assertFalse(restored.spam)
            assertFalse(restored.archived)
            assertFalse(restored.blocked)
        } finally {
            store.writableDatabase.delete("classifications", "message_id IN (SELECT id FROM messages WHERE thread_key=?)", arrayOf(key))
            store.writableDatabase.delete("messages", "thread_key=?", arrayOf(key))
            store.writableDatabase.delete("threads", "thread_key=?", arrayOf(key))
            store.close()
        }
    }

    @Test fun observerChoiceActivatesAndStoresOnlyLocalPreview() {
        assertFalse("Run observer probe with another SMS default holder", SmsOperations.isDefault(context))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS))
        val preferences = context.getSharedPreferences("messenger_settings", 0)
        val mode = preferences.getString("mode", "unselected")
        val monitoring = preferences.getBoolean("monitoring", false)
        val store = MessageStore(context)
        val sender = "AI05 observer fixture " + UUID.randomUUID()
        val key = store.key(sender)
        val controller = MessengerController.get(context)
        try {
            controller.selectMode("observer")
            assertTrue("Selecting support mode should activate granted SMS monitoring", controller.observes())
            controller.reportError("permission_missing")
            controller.refresh()
            val recovered = System.currentTimeMillis() + 5_000
            while (controller.state.value.error == "permission_missing" && System.currentTimeMillis() < recovered) Thread.sleep(25)
            assertNotEquals("Observer must clear stale READ_SMS errors when RECEIVE_SMS is granted", "permission_missing", controller.state.value.error)
            val complete = CountDownLatch(1)
            controller.receive(sender, "Your code is 123456. Do not share it.", System.currentTimeMillis(), -1, false) { complete.countDown() }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertTrue(store.conversations().any { it.key == key && it.messages.all { message -> message.id.startsWith("observer:") && message.systemId == null } })
            val analyzed = CountDownLatch(1)
            controller.draft(sender) { analyzed.countDown() }
            assertTrue(analyzed.await(15, TimeUnit.SECONDS))
            controller.setMonitoring(false)
            val dropped = CountDownLatch(1)
            controller.receive(sender, "Disabled observer fixture", System.currentTimeMillis() + 1, -1, false) { dropped.countDown() }
            assertTrue(dropped.await(5, TimeUnit.SECONDS))
            assertEquals(1, store.conversations().first { it.key == key }.messages.size)
        } finally {
            preferences.edit().putString("mode", mode).putBoolean("monitoring", monitoring).commit()
            store.writableDatabase.delete("classifications", "message_id IN (SELECT id FROM messages WHERE thread_key=?)", arrayOf(key))
            store.writableDatabase.delete("messages", "thread_key=?", arrayOf(key))
            store.writableDatabase.delete("threads", "thread_key=?", arrayOf(key))
            store.close()
        }
    }

    @Test fun invalidSendCannotCreateAnOutboxMessage() {
        assertTrue(SmsOperations.isDefault(context))
        fun count() = context.contentResolver.query(Telephony.Sms.Outbox.CONTENT_URI, arrayOf("_id"), null, null, null)!!.use { it.count }
        val before = count()
        assertThrows(IllegalArgumentException::class.java) { SmsOperations.send(context, "invalid-recipient", "Synthetic never sent") }
        assertEquals(before, count())
    }
    @Test fun supportPreviewDoesNotDuplicateSystemSmsAfterRoleChange() {
        val store = MessageStore(context)
        val sender = "+12025550147"
        val body = "AI05 role-switch fixture " + UUID.randomUUID()
        val date = System.currentTimeMillis()
        val local = store.storeObserved(sender, body, date)
        var uri: android.net.Uri? = null
        try {
            uri = SmsOperations.receive(context, sender, body, date, -1)
            assertNotNull(uri)
            val matches = store.conversations().flatMap { it.messages }.filter { it.body == body }
            assertEquals("Same SMS must not appear as local preview and system SMS", 1, matches.size)
            assertNotNull(matches.single().systemId)
            context.contentResolver.delete(uri!!, null, null)
            uri = null
            val retained = store.conversations().flatMap { it.messages }.filter { it.body == body }
            assertEquals("Deduplication must preserve encrypted support preview", 1, retained.size)
            assertEquals(local, retained.single().id)
        } finally {
            if (uri != null) context.contentResolver.delete(uri!!, null, null)
            store.writableDatabase.delete("messages", "id=?", arrayOf(local))
            store.writableDatabase.delete("classifications", "message_id=?", arrayOf(local))
            store.close()
        }
    }

    @Test fun fullDeliveryQueueCannotDiscardAnIncomingSms() {
        val controller = MessengerController.get(context)
        val field = MessengerController::class.java.getDeclaredField("deliveryExecutor").apply { isAccessible = true }
        val executor = field.get(controller) as java.util.concurrent.ThreadPoolExecutor
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val sender = "+12025550146"
        val body = "AI05 backpressure fixture " + UUID.randomUUID()
        val date = System.currentTimeMillis()
        var uri: android.net.Uri? = null
        try {
            executor.execute { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(64) { executor.execute {} }
            val finished = CountDownLatch(1)
            controller.receive(sender, body, date, -1, true) { finished.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            uri = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address=? AND body=? AND date_sent=?", arrayOf(sender, body, date.toString()), null)?.use {
                if (it.moveToFirst()) android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0)) else null
            }
            assertNotNull("Backpressure must preserve SMS before finishing delivery", uri)
        } finally {
            release.countDown()
            val done = CountDownLatch(1)
            controller.draft(sender) { done.countDown() }
            done.await(15, TimeUnit.SECONDS)
            if (uri != null) {
                context.contentResolver.delete(uri!!, null, null)
                MessageStore(context).use { it.writableDatabase.delete("classifications", "message_id=?", arrayOf("sms:" + uri!!.lastPathSegment)) }
            }
        }
    }

    @Test fun selectingDefaultModeRestoresDefaultProfile() {
        assertTrue(SmsOperations.isDefault(context))
        val controller = MessengerController.get(context)
        controller.selectMode("default")
        assertEquals("default", context.getSharedPreferences("messenger_settings", 0).getString("mode", null))
        assertTrue(controller.state.value.monitoring)
        MessageStore(context).use { store ->
            for (sender in listOf("+12025550146", "+12025550147", "+12025550149")) {
                context.getSystemService(android.app.NotificationManager::class.java).cancel(store.key(sender).hashCode())
            }
        }
    }

    @Test fun spacedMoneyLinkWarnsForOrdinaryPhoneNumber() {
        val sender = "+12025550145"
        val body = "I will pay you 1000$ so go here to take it http: //hackermoney/getmoney. org"
        val date = System.currentTimeMillis()
        val store = MessageStore(context)
        val controller = MessengerController.get(context)
        val finished = CountDownLatch(1)
        var uri: android.net.Uri? = null
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        val key = store.key(sender)
        try {
            controller.receive(sender, body, date, -1, true) { finished.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            uri = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address=? AND body=? AND date_sent=?", arrayOf(sender, body, date.toString()), null)?.use {
                if (it.moveToFirst()) android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0)) else null
            }
            assertNotNull(uri)
            val id = "sms:" + uri!!.lastPathSegment
            val deadline = System.currentTimeMillis() + 30_000
            while (controller.state.value.conversations.none { it.messages.any { message -> message.id == id && message.modelVersion != null } } && System.currentTimeMillis() < deadline) Thread.sleep(25)
            val conversation = controller.state.value.conversations.first { it.messages.any { message -> message.id == id } }
            val message = conversation.messages.first { it.id == id }
            assertEquals("ham", message.modelLabel)
            assertEquals("review", message.riskDecision)
            assertTrue(message.riskReasons.contains("obfuscated_link"))
            assertFalse(conversation.spam)
            while (notifications.activeNotifications.none { it.id == key.hashCode() } && System.currentTimeMillis() < deadline) Thread.sleep(25)
            val notification = notifications.activeNotifications.first { it.id == key.hashCode() }.notification
            assertEquals(context.getString(R.string.needs_review), notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertNotEquals(body, notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        } finally {
            if (uri != null) {
                context.contentResolver.delete(uri!!, null, null)
                store.writableDatabase.delete("classifications", "message_id=?", arrayOf("sms:" + uri!!.lastPathSegment))
            }
            notifications.cancel(key.hashCode())
            store.close()
        }
    }

}
