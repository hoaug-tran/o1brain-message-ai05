package vn.uneti.ai05

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val defaultDelivery = intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        if (!defaultDelivery && intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val controller = MessengerController.get(context)
        val isDefault = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        if (
            defaultDelivery && !isDefault ||
                !defaultDelivery && (isDefault || !controller.observes())
        )
            return
        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        if (pdus.size !in 1..32) return
        val messages =
            try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } catch (_: RuntimeException) {
                return
            }
        if (messages.isEmpty() || messages.size > 32) return
        val original = messages.joinToString("") { it.messageBody ?: "" }
        if (original.toByteArray(Charsets.UTF_8).size > 32 * 1024) return
        val address = messages[0].originatingAddress ?: ""
        if (messages.any { (it.originatingAddress ?: "") != address }) return
        val pending = goAsync()
        controller.receive(
            address,
            original,
            messages[0].timestampMillis,
            intent.getIntExtra("subscription", -1),
            defaultDelivery,
        ) {
            pending.finish()
        }
    }
}
