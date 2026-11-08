package vn.uneti.ai05

import android.provider.Telephony
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class MoneyLinkRegressionTest {
    @Test
    fun splitDomainWarnsInInstalledRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "i will send you 10583837$. ho here to take it http://hacker. org/cheating money. if you not got this i will kill you!"
        val risk = requireNotNull(AppRuntime.get(context).assessMessage(text))
        assertEquals("ham", risk.prediction.label)
        assertEquals("review", risk.decision)
        assertTrue(risk.reasons.contains("obfuscated_link"))
        assertTrue(risk.version.startsWith("policy-9:"))
    }

    @Test
    fun existingSyntheticIncomingMessageIsReassessedWithoutDeletion() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("existing_fixture") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ids = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id"),
            "body LIKE ?",
            arrayOf("%10583837%"),
            null,
        )!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add("sms:" + cursor.getLong(0)) }
        }
        assertTrue("Synthetic incoming fixture missing", ids.isNotEmpty())
        MessengerController.get(context).refresh()
        MessageStore(context).use { store ->
            val deadline = System.currentTimeMillis() + 30_000
            for (id in ids) {
                var updated = false
                while (!updated && System.currentTimeMillis() < deadline) {
                    updated = store.readableDatabase.query(
                        "classifications",
                        arrayOf("decision", "reasons", "model_version"),
                        "message_id=?",
                        arrayOf(id),
                        null, null, null,
                    ).use {
                        it.moveToFirst() && it.getString(0) == "review" &&
                            it.getString(1).split(",").contains("obfuscated_link") &&
                            it.getString(2).startsWith("policy-9:")
                    }
                    if (!updated) Thread.sleep(50)
                }
                assertTrue("Synthetic message did not acquire updated warning", updated)
            }
        }
    }
}
