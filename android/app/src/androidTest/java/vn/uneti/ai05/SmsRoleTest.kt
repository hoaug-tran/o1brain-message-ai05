package vn.uneti.ai05

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SmsRoleTest {
    @Test
    fun defaultRoleCanReadInboxAndRefreshWithoutPermissionWarning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("SMS role not held", context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        assertEquals("READ_SMS denied", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_SMS))
        MessageStore(context).use { store ->
            assertTrue("Store rejects SMS role", store.hasReadAccess())
            context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, null)!!.use { assertTrue(it.count >= 0) }
            context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), null, null, null)!!.use { assertTrue(it.count >= 0) }
            store.conversations()
            val controller = MessengerController.get(context)
            controller.refresh()
            val deadline = System.currentTimeMillis() + 30_000
            while (controller.state.value.loading && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Controller does not recognize SMS role", controller.state.value.isDefault)
            assertNotEquals("Stale SMS permission warning", "permission_missing", controller.state.value.error)
        }
    }

    @Test
    fun defaultDeliveryPersistsAndAppearsInInbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SmsOperations.isDefault(context))
        val sender = "+12025550149"
        val body = "AI05 synthetic delivery " + java.util.UUID.randomUUID()
        val date = System.currentTimeMillis()
        var uri: android.net.Uri? = null
        val store = MessageStore(context)
        val controller = MessengerController.get(context)
        try {
            val finished = java.util.concurrent.CountDownLatch(1)
            controller.receive(sender, body, date, -1, true) { finished.countDown() }
            assertTrue("Incoming pipeline did not finish", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            uri = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address=? AND body=? AND date_sent=?", arrayOf(sender, body, date.toString()), null)?.use {
                if (it.moveToFirst()) android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0)) else null
            }
            assertNotNull("Incoming SMS not persisted before broadcast completion", uri)
            assertEquals("Repeated delivery must not duplicate SMS", uri, SmsOperations.receive(context, sender, body, date, -1))
            val id = "sms:" + uri!!.lastPathSegment
            val deadline = System.currentTimeMillis() + 60_000
            while (controller.state.value.conversations.none { conversation -> conversation.messages.any { it.id == id && it.modelVersion != null } } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Incoming SMS absent from inbox state", controller.state.value.conversations.any { conversation -> conversation.messages.any { it.id == id } })
            assertNotEquals("Incoming pipeline reports missing permission", "permission_missing", controller.state.value.error)
            assertTrue("Incoming SMS lacks assessment", store.conversations().flatMap { it.messages }.first { it.id == id }.modelVersion != null)
        } finally {
            val inserted = uri ?: context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address=? AND body=? AND date_sent=?", arrayOf(sender, body, date.toString()), null)?.use {
                if (it.moveToFirst()) android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, it.getLong(0)) else null
            }
            if (inserted != null) {
                context.contentResolver.delete(inserted, null, null)
                store.writableDatabase.delete("classifications", "message_id=?", arrayOf("sms:" + inserted.lastPathSegment))
            }
            context.getSystemService(android.app.NotificationManager::class.java).cancel(store.key(sender).hashCode())
            controller.refresh()
            store.close()
        }
    }


    @Test
    fun defaultRoleIsKnownBeforeInboxLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SmsOperations.isDefault(context))
        assertTrue("Startup incorrectly reports missing SMS role", MessengerController.get(context).state.value.isDefault)
    }

    @Test
    fun movingAutomaticSpamToInboxCompletesAndKeepsOverride() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MessageStore(context)
        val sender = "AI05 move synthetic " + java.util.UUID.randomUUID()
        val id = store.storeObserved(sender, "Synthetic spam override fixture", System.currentTimeMillis())
        val key = store.key(sender)
        try {
            store.classify(id, "spam", "$PRODUCT_POLICY_VERSION:test", "spam")
            val controller = MessengerController.get(context)
            controller.refresh()
            val deadline = System.currentTimeMillis() + 60_000
            while (controller.state.value.conversations.none { it.key == key } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val conversation = controller.state.value.conversations.first { it.key == key }
            assertTrue("Fixture must start in spam", conversation.spam)
            val finished = java.util.concurrent.CountDownLatch(1)
            controller.flag(conversation, "spam", false) { finished.countDown() }
            assertTrue("Move to inbox blocked by background work", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(controller.state.value.conversations.first { it.key == key }.spam)
            store.classify(id, "spam", "$PRODUCT_POLICY_VERSION:test", "spam")
            assertFalse("Model must not reverse manual inbox choice", store.conversations().first { it.key == key }.spam)
        } finally {
            store.writableDatabase.delete("messages", "id=?", arrayOf(id))
            store.writableDatabase.delete("classifications", "message_id=?", arrayOf(id))
            store.writableDatabase.delete("threads", "thread_key=?", arrayOf(key))
            store.close()
        }
    }

}
