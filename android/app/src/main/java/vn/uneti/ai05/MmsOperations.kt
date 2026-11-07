package vn.uneti.ai05

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.BoundedPduParser
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.RetrieveConf
import com.google.android.mms.pdu_alt.SendReq
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

object MmsOperations {
    private val worker = Executors.newSingleThreadExecutor()

    fun notification(context: Context, data: ByteArray, subscription: Int, finished: () -> Unit) {
        worker.execute {
            try {
                if (
                    Telephony.Sms.getDefaultSmsPackage(context) != context.packageName ||
                        data.size > 64 * 1024
                )
                    return@execute
                val pdu = BoundedPduParser(data).parse() as? NotificationInd ?: return@execute
                val location = String(pdu.contentLocation, Charsets.ISO_8859_1)
                val parsed = Uri.parse(location)
                require(
                    location.length <= 2048 &&
                        parsed.scheme in setOf("http", "https") &&
                        !parsed.host.isNullOrBlank() &&
                        parsed.userInfo == null
                )
                val sender = pdu.from?.string ?: ""
                val thread = Telephony.Threads.getOrCreateThreadId(context, sender)
                val uri =
                    context.contentResolver.insert(
                        Telephony.Mms.Inbox.CONTENT_URI,
                        ContentValues().apply {
                            put("m_type", 130)
                            put("msg_box", 1)
                            put("date", System.currentTimeMillis() / 1000)
                            put("read", 0)
                            put("seen", 0)
                            put("ct_l", location)
                            put("tr_id", String(pdu.transactionId, Charsets.ISO_8859_1))
                            put("thread_id", thread)
                            put("sub_id", subscription)
                        },
                    ) ?: error("storage_error")
                context.contentResolver.insert(
                    Uri.withAppendedPath(uri, "addr"),
                    ContentValues().apply {
                        put("address", sender)
                        put("type", 137)
                        put("charset", 106)
                    },
                )
                MessengerController.get(context).refresh()
            } catch (_: Exception) {
                MessengerController.get(context).reportError("mms_failed")
            } finally {
                finished()
            }
        }
    }

    fun download(context: Context, id: Long, subscription: Int) {
        require(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)
        val source = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())
        val location =
            context.contentResolver.query(source, arrayOf("ct_l"), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("mms_failed")
        val parsed = Uri.parse(location)
        require(
            parsed.scheme in setOf("http", "https") &&
                !parsed.host.isNullOrBlank() &&
                parsed.userInfo == null &&
                location.length <= 2048
        )
        val directory = File(context.cacheDir, "mms").apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString() + ".pdu").apply { createNewFile() }
        val destination = FileProvider.getUriForFile(context, context.packageName + ".mms", file)
        context.grantUriPermission(
            "com.android.phone",
            destination,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val callback =
            Intent(context, MmsDownloadReceiver::class.java)
                .setData(destination)
                .putExtra("notification_id", id)
                .putExtra("file_name", file.name)
                .putExtra("subscription_id", subscription)
        val pending =
            PendingIntent.getBroadcast(
                context,
                id.toInt(),
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        SmsOperations.manager(context, subscription)
            .downloadMultimediaMessage(context, location, destination, null, pending)
    }

    fun downloaded(context: Context, result: Int, intent: Intent, finished: () -> Unit) {
        worker.execute {
            try {
                val name = intent.getStringExtra("file_name") ?: error("mms_failed")
                require(name.matches(Regex("[a-f0-9-]+\\.pdu")))
                val file = File(File(context.cacheDir, "mms"), name)
                try {
                    require(
                        result == android.app.Activity.RESULT_OK &&
                            file.length() in 1..5 * 1024 * 1024
                    )
                    val pdu =
                        BoundedPduParser(file.readBytes()).parse() as? RetrieveConf
                            ?: error("mms_failed")
                    require(pdu.body.partsNum in 0..100)
                    val id = intent.getLongExtra("notification_id", -1)
                    require(
                        id >= 0 &&
                            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
                    )
                    val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put("m_type", 132)
                            put("ct_t", "application/vnd.wap.multipart.related")
                            put("sub", pdu.subject?.string)
                            put("sub_cs", 106)
                        },
                        null,
                        null,
                    )
                    for (index in 0 until pdu.body.partsNum) {
                        val part = pdu.body.getPart(index)
                        val type = String(part.contentType, Charsets.ISO_8859_1)
                        val bytes = part.data ?: byteArrayOf()
                        require(bytes.size <= 5 * 1024 * 1024)
                        val partUri =
                            context.contentResolver.insert(
                                Uri.withAppendedPath(uri, "part"),
                                ContentValues().apply {
                                    put("ct", type)
                                    put("seq", index)
                                    put("chset", part.charset)
                                    if (type.startsWith("text/"))
                                        put("text", EncodedStringValue(part.charset, bytes).string)
                                    put(
                                        "cl",
                                        part.contentLocation?.let {
                                            String(it, Charsets.ISO_8859_1)
                                        } ?: "part-$index",
                                    )
                                },
                            ) ?: error("storage_error")
                        if (!type.startsWith("text/"))
                            context.contentResolver.openOutputStream(partUri)?.use {
                                it.write(bytes)
                            } ?: error("storage_error")
                    }
                    MessengerController.get(context).refresh()
                } finally {
                    file.delete()
                    intent.data?.let {
                        context.revokeUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
            } catch (_: Exception) {
                MessengerController.get(context).reportError("mms_failed")
            } finally {
                finished()
            }
        }
    }

    fun send(context: Context, address: String, original: String, attachment: Uri) {
        require(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)
        require(validRecipient(address))
        require(
            original.toByteArray(Charsets.UTF_8).size <= 20_000 && attachment.scheme == "content"
        )
        val type = context.contentResolver.getType(attachment) ?: "application/octet-stream"
        val data =
            context.contentResolver.openInputStream(attachment)?.use {
                readBounded(it, 2 * 1024 * 1024)
            } ?: error("attachment_unavailable")
        require(data.isNotEmpty() && data.size <= 2 * 1024 * 1024)
        val body = PduBody()
        if (original.isNotEmpty())
            body.addPart(
                PduPart().apply {
                    contentType = "text/plain".toByteArray()
                    charset = 106
                    contentLocation = "text.txt".toByteArray()
                    this.data = original.toByteArray(Charsets.UTF_8)
                }
            )
        body.addPart(
            PduPart().apply {
                contentType = type.toByteArray(Charsets.ISO_8859_1)
                contentLocation = "attachment".toByteArray()
                this.data = data
            }
        )
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        val request =
            SendReq().apply {
                to = arrayOf(EncodedStringValue(address))
                date = System.currentTimeMillis() / 1000
                this.body = body
            }
        val encoded = PduComposer(context, request).make() ?: error("mms_failed")
        require(encoded.size <= 3 * 1024 * 1024)
        val uri =
            context.contentResolver.insert(
                Telephony.Mms.Outbox.CONTENT_URI,
                ContentValues().apply {
                    put("m_type", 128)
                    put("msg_box", 4)
                    put("date", System.currentTimeMillis() / 1000)
                    put("read", 1)
                    put("seen", 1)
                    put("ct_t", "application/vnd.wap.multipart.related")
                    put("tr_id", String(request.transactionId, Charsets.ISO_8859_1))
                    put("thread_id", Telephony.Threads.getOrCreateThreadId(context, address))
                    put("sub_id", subscription)
                },
            ) ?: error("storage_error")
        try {
            context.contentResolver.insert(
                Uri.withAppendedPath(uri, "addr"),
                ContentValues().apply {
                    put("address", address)
                    put("type", 151)
                    put("charset", 106)
                },
            )
            for (index in 0 until body.partsNum) {
                val part = body.getPart(index)
                val mime = String(part.contentType, Charsets.ISO_8859_1)
                val partUri =
                    context.contentResolver.insert(
                        Uri.withAppendedPath(uri, "part"),
                        ContentValues().apply {
                            put("ct", mime)
                            put("seq", index)
                            put("chset", 106)
                            if (mime == "text/plain") put("text", original)
                        },
                    ) ?: error("storage_error")
                if (mime != "text/plain")
                    context.contentResolver.openOutputStream(partUri)?.use { it.write(part.data) }
                        ?: error("storage_error")
            }
            val file =
                File(
                        File(context.cacheDir, "mms").apply { mkdirs() },
                        UUID.randomUUID().toString() + ".pdu",
                    )
                    .apply { writeBytes(encoded) }
            val contentUri = FileProvider.getUriForFile(context, context.packageName + ".mms", file)
            context.grantUriPermission(
                "com.android.phone",
                contentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val callback =
                Intent(context, MmsSentReceiver::class.java)
                    .setData(contentUri)
                    .putExtra("mms_uri", uri.toString())
                    .putExtra("file_name", file.name)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    uri.lastPathSegment!!.toInt(),
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            SmsOperations.manager(context, subscription)
                .sendMultimediaMessage(context, contentUri, null, null, pending)
        } catch (failure: Exception) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put("resp_st", 192) },
                null,
                null,
            )
            throw failure
        }
    }

    fun openAttachment(context: Context, attachment: Attachment) {
        val source = Uri.parse(attachment.uri)
        require(source.scheme == "content" && source.authority == "mms")
        val directory = File(context.cacheDir, "mms").apply { mkdirs() }
        directory
            .listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }
            ?.forEach { it.delete() }
        val target = File(directory, UUID.randomUUID().toString() + ".bin")
        val bytes =
            context.contentResolver.openInputStream(source)?.use {
                readBounded(it, 5 * 1024 * 1024)
            } ?: error("attachment_unavailable")
        require(bytes.size <= 5 * 1024 * 1024)
        target.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".mms", target)
        context.startActivity(
            Intent.createChooser(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, attachment.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    context.getString(R.string.open_attachment),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION ||
                Telephony.Sms.getDefaultSmsPackage(context) != context.packageName
        )
            return
        val data = intent.getByteArrayExtra("data") ?: return
        val pending = goAsync()
        MmsOperations.notification(
            context,
            data,
            intent.getIntExtra("subscription", SubscriptionManager.getDefaultSmsSubscriptionId()),
        ) {
            pending.finish()
        }
    }
}

class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        MmsOperations.downloaded(context, resultCode, intent) { pending.finish() }
    }
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) return
        val uri = intent.getStringExtra("mms_uri")?.let { Uri.parse(it) } ?: return
        if (uri.authority != "mms") return
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put("msg_box", if (resultCode == android.app.Activity.RESULT_OK) 2 else 4)
                put("resp_st", if (resultCode == android.app.Activity.RESULT_OK) 128 else 192)
            },
            null,
            null,
        )
        val name = intent.getStringExtra("file_name")
        if (name != null && name.matches(Regex("[a-f0-9-]+\\.pdu")))
            File(File(context.cacheDir, "mms"), name).delete()
        intent.data?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        MessengerController.get(context).refresh()
    }
}
