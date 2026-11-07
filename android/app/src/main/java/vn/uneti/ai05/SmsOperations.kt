package vn.uneti.ai05

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.UUID

object SmsOperations {
    @Suppress("DEPRECATION")
    fun manager(context: Context, subscription: Int): SmsManager =
        if (subscription >= 0) SmsManager.getSmsManagerForSubscriptionId(subscription)
        else SmsManager.getDefault()

    fun send(
        context: Context,
        address: String,
        original: String,
        subscription: Int = SubscriptionManager.getDefaultSmsSubscriptionId(),
    ): Uri {
        require(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) {
            "default_required"
        }
        require(validRecipient(address)) { "invalid_recipient" }
        require(original.isNotBlank() && original.toByteArray(Charsets.UTF_8).size <= 20_000) {
            "invalid_message"
        }
        val manager = manager(context, subscription)
        val parts = manager.divideMessage(original)
        require(parts.size in 1..20) { "too_many_parts" }
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val uri =
            context.contentResolver.insert(
                Telephony.Sms.Outbox.CONTENT_URI,
                ContentValues().apply {
                    put("address", address)
                    put("body", original)
                    put("date", System.currentTimeMillis())
                    put("thread_id", threadId)
                    put("read", 1)
                    put("seen", 1)
                    put("sub_id", subscription)
                    put("status", Telephony.Sms.STATUS_PENDING)
                },
            ) ?: error("storage_error")
        val sent = ArrayList<PendingIntent>()
        for (index in parts.indices) {
            val intent =
                Intent(context, SmsStatusReceiver::class.java)
                    .setAction("vn.uneti.ai05.SMS_SENT")
                    .setData(
                        Uri.parse("ai05://sent/${uri.lastPathSegment}/$index/${UUID.randomUUID()}")
                    )
                    .putExtra("sms_uri", uri.toString())
                    .putExtra("part_count", parts.size)
                    .putExtra("part_index", index)
            sent +=
                PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
        }
        try {
            manager.sendMultipartTextMessage(address, null, parts, sent, null)
        } catch (failure: RuntimeException) {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put("type", Telephony.Sms.MESSAGE_TYPE_FAILED)
                    put("error_code", -1)
                },
                null,
                null,
            )
            throw failure
        }
        return uri
    }

    fun receive(
        context: Context,
        address: String,
        original: String,
        date: Long,
        subscription: Int,
    ): Uri? {
        require(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)
        context.contentResolver
            .query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("_id"),
                "address=? AND date_sent=? AND body=?",
                arrayOf(address, date.toString(), original),
                null,
            )
            ?.use { cursor ->
                if (cursor.moveToFirst())
                    return Uri.withAppendedPath(
                        Telephony.Sms.CONTENT_URI,
                        cursor.getLong(0).toString(),
                    )
            }
        return context.contentResolver.insert(
            Telephony.Sms.Inbox.CONTENT_URI,
            ContentValues().apply {
                put("address", address)
                put("body", original)
                put("date", System.currentTimeMillis())
                put("date_sent", date)
                put("read", 0)
                put("seen", 0)
                put("sub_id", subscription)
                put("thread_id", Telephony.Threads.getOrCreateThreadId(context, address))
            },
        )
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "vn.uneti.ai05.SMS_SENT") return
        val uri = intent.getStringExtra("sms_uri")?.let { Uri.parse(it) } ?: return
        if (
            uri.authority != "sms" ||
                Telephony.Sms.getDefaultSmsPackage(context) != context.packageName
        )
            return
        val count = intent.getIntExtra("part_count", 0)
        val part = intent.getIntExtra("part_index", -1)
        if (count !in 1..20 || part !in 0 until count) return
        val preferences = context.getSharedPreferences("send_receipts", 0)
        synchronized(SmsStatusReceiver::class.java) {
            val identity = uri.lastPathSegment ?: return
            val currentType =
                context.contentResolver.query(uri, arrayOf("type"), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                } ?: return
            if (smsReceiptIsFinal(currentType)) return
            val failed =
                preferences.getBoolean("$identity:failed", false) ||
                    resultCode != android.app.Activity.RESULT_OK
            val mask = preferences.getInt("$identity:mask", 0) or (1 shl part)
            if (mask == (1 shl count) - 1) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(
                            "type",
                            if (failed) Telephony.Sms.MESSAGE_TYPE_FAILED
                            else Telephony.Sms.MESSAGE_TYPE_SENT,
                        )
                        put(
                            "status",
                            if (failed) Telephony.Sms.STATUS_FAILED
                            else Telephony.Sms.STATUS_COMPLETE,
                        )
                    },
                    null,
                    null,
                )
                preferences.edit().remove("$identity:failed").remove("$identity:mask").apply()
            } else
                preferences
                    .edit()
                    .putBoolean("$identity:failed", failed)
                    .putInt("$identity:mask", mask)
                    .apply()
        }
        MessengerController.get(context).refresh()
    }
}

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            intent?.action == "android.intent.action.RESPOND_VIA_MESSAGE" &&
                Telephony.Sms.getDefaultSmsPackage(this) == packageName
        ) {
            val address = intent.data?.schemeSpecificPart?.substringBefore('?')
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!address.isNullOrBlank() && !text.isNullOrBlank())
                Thread {
                        try {
                            SmsOperations.send(this, address, text)
                        } catch (_: RuntimeException) {
                            MessengerController.get(this).reportError("send_failed")
                        } finally {
                            stopSelf(startId)
                        }
                    }
                    .start()
            else stopSelf(startId)
        } else stopSelf(startId)
        return START_NOT_STICKY
    }
}
