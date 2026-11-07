package vn.uneti.ai05

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.PhoneNumberUtils
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SenderFilter(val key: String, val address: String)

data class Attachment(val uri: String, val mimeType: String)

data class MessageItem(
    val id: String,
    val address: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val status: String,
    val systemId: Long? = null,
    val subscriptionId: Int = -1,
    val mmsId: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val modelLabel: String? = null,
    val modelVersion: String? = null,
    val riskDecision: String? = null,
    val riskReasons: List<String> = emptyList(),
)

data class Conversation(
    val key: String,
    val address: String,
    val messages: List<MessageItem>,
    val archived: Boolean,
    val spam: Boolean,
    val trusted: Boolean,
    val spamOverride: Boolean? = null,
    val blocked: Boolean = false,
) {
    val latest: MessageItem
        get() = messages.last()

    val unread: Int
        get() = messages.count { !it.read && !it.outgoing }
}

class LocalCipher {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(alias: String, algorithm: String): SecretKey =
        synchronized(LocalCipher::class.java) {
            (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(algorithm, "AndroidKeyStore").run {
                    val builder =
                        KeyGenParameterSpec.Builder(
                            alias,
                            if (algorithm == KeyProperties.KEY_ALGORITHM_AES)
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                            else KeyProperties.PURPOSE_SIGN,
                        )
                    if (algorithm == KeyProperties.KEY_ALGORITHM_AES)
                        builder
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    else builder.setDigests(KeyProperties.DIGEST_SHA256)
                    init(builder.build())
                    generateKey()
                }
        }

    @Synchronized
    fun encrypt(original: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key("message_content", KeyProperties.KEY_ALGORITHM_AES))
            }
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(original.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key("message_content", KeyProperties.KEY_ALGORITHM_AES),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
            }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun thread(address: String): String {
        val normalized =
            if (address.any { it.isLetter() }) address
            else PhoneNumberUtils.normalizeNumber(address)
        val mac =
            Mac.getInstance("HmacSHA256").apply {
                init(key("conversation_key", KeyProperties.KEY_ALGORITHM_HMAC_SHA256))
            }
        return mac.doFinal(normalized.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }
}

private data class ThreadFlags(
    val archived: Boolean,
    val spamOverride: Int,
    val trusted: Boolean,
    val blocked: Boolean,
)

class MessageStore(private val context: Context) :
    SQLiteOpenHelper(context, "messages_private.db", null, 3) {
    companion object {
        const val MAX_SYSTEM_SMS = 3000
    }

    private val cipher = LocalCipher()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(id TEXT PRIMARY KEY, thread_key TEXT NOT NULL, address TEXT NOT NULL, body TEXT NOT NULL, date INTEGER NOT NULL, is_read INTEGER NOT NULL, UNIQUE(thread_key,date,body))"
        )
        db.execSQL(
            "CREATE TABLE threads(thread_key TEXT PRIMARY KEY, archived INTEGER NOT NULL DEFAULT 0, spam INTEGER NOT NULL DEFAULT -1, trusted INTEGER NOT NULL DEFAULT 0, draft TEXT, blocked INTEGER NOT NULL DEFAULT 0, sender_address TEXT)"
        )
        db.execSQL(
            "CREATE TABLE classifications(message_id TEXT PRIMARY KEY, label TEXT NOT NULL, model_version TEXT NOT NULL, decision TEXT NOT NULL DEFAULT 'review', reasons TEXT NOT NULL DEFAULT '')"
        )
        createReports(db)
    }

    private fun createReports(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reports(message_id TEXT PRIMARY KEY, thread_key TEXT NOT NULL, model_version TEXT, decision TEXT, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE classifications ADD COLUMN decision TEXT NOT NULL DEFAULT 'review'"
            )
            db.execSQL("ALTER TABLE classifications ADD COLUMN reasons TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE classifications SET decision='normal' WHERE label='ham'")
            createReports(db)
        }
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL("ALTER TABLE threads ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE threads ADD COLUMN sender_address TEXT")
        }
    }

    fun isDefault(): Boolean = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun hasReadAccess(): Boolean =
        isDefault() &&
            context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    fun key(address: String): String = cipher.thread(address)

    @Synchronized
    fun storeObserved(address: String, original: String, date: Long): String {
        val identity = "observer:" + cipher.thread("$date\u0000$address\u0000$original")
        val thread = key(address)
        val inserted =
            writableDatabase.insertWithOnConflict(
                "messages",
                null,
                ContentValues().apply {
                    put("id", identity)
                    put("thread_key", thread)
                    put("address", cipher.encrypt(address))
                    put("body", cipher.encrypt(original))
                    put("date", date)
                    put("is_read", 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        if (inserted == -1L)
            readableDatabase
                .query("messages", arrayOf("id"), "id=?", arrayOf(identity), null, null, null)
                .use { cursor -> check(cursor.moveToFirst()) { "storage_error" } }
        return identity
    }

    @Synchronized
    fun classify(
        id: String,
        label: String,
        version: String,
        decision: String = label,
        reasons: List<String> = emptyList(),
    ) {
        require(label in setOf("ham", "spam"))
        require(decision in setOf("ham", "normal", "review", "spam", "unassessed"))
        check(
            writableDatabase.insertWithOnConflict(
                "classifications",
                null,
                ContentValues().apply {
                    put("message_id", id)
                    put("label", label)
                    put("model_version", version)
                    put("decision", if (decision == "ham") "normal" else decision)
                    put("reasons", reasons.joinToString(","))
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L
        ) {
            "storage_error"
        }
    }

    @Synchronized
    fun conversations(): List<Conversation> {
        val messages = mutableListOf<MessageItem>()
        readableDatabase.query("messages", null, null, null, null, null, "date DESC", "1000").use {
            cursor ->
            while (cursor.moveToNext()) messages +=
                MessageItem(
                    cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    cipher.decrypt(cursor.getString(cursor.getColumnIndexOrThrow("address"))),
                    cipher.decrypt(cursor.getString(cursor.getColumnIndexOrThrow("body"))),
                    cursor.getLong(cursor.getColumnIndexOrThrow("date")),
                    false,
                    cursor.getInt(cursor.getColumnIndexOrThrow("is_read")) == 1,
                    "received",
                )
        }
        if (hasReadAccess()) {
            context.contentResolver
                .query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id", "address", "body", "date", "type", "read", "sub_id"),
                    null,
                    null,
                    "date DESC",
                )
                ?.use { cursor ->
                    while (messages.size < MAX_SYSTEM_SMS && cursor.moveToNext()) {
                        val type = cursor.getInt(4)
                        if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                        messages +=
                            MessageItem(
                                "sms:" + cursor.getLong(0),
                                cursor.getString(1) ?: "",
                                cursor.getString(2) ?: "",
                                cursor.getLong(3),
                                type != Telephony.Sms.MESSAGE_TYPE_INBOX,
                                cursor.getInt(5) == 1,
                                when (type) {
                                    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                                    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                                    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
                                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                                    else -> "received"
                                },
                                cursor.getLong(0),
                                cursor.getInt(6),
                            )
                    }
                }
            context.contentResolver
                .query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf("_id", "date", "read", "msg_box", "m_type", "sub_id", "resp_st"),
                    null,
                    null,
                    "date DESC",
                )
                ?.use { cursor ->
                    var count = 0
                    while (count++ < 300 && cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())
                        var sender = ""
                        context.contentResolver
                            .query(
                                Uri.withAppendedPath(uri, "addr"),
                                arrayOf("address", "type"),
                                null,
                                null,
                                null,
                            )
                            ?.use { addresses ->
                                while (addresses.moveToNext()) if (
                                    addresses.getInt(1) == (if (cursor.getInt(3) == 1) 137 else 151)
                                )
                                    sender = addresses.getString(0) ?: ""
                            }
                        val chunks = mutableListOf<String>()
                        val attachments = mutableListOf<Attachment>()
                        context.contentResolver
                            .query(
                                Uri.withAppendedPath(uri, "part"),
                                arrayOf("ct", "text", "_id"),
                                null,
                                null,
                                "seq ASC",
                            )
                            ?.use { parts ->
                                while (parts.moveToNext()) {
                                    val type = parts.getString(0)
                                    if (type == "text/plain") chunks += parts.getString(1) ?: ""
                                    else if (type != "application/smil")
                                        attachments +=
                                            Attachment(
                                                "content://mms/part/" + parts.getLong(2),
                                                type,
                                            )
                                }
                            }
                        val pending = cursor.getInt(4) == 130
                        messages +=
                            MessageItem(
                                "mms:$id",
                                sender,
                                if (pending) context.getString(R.string.mms_pending)
                                else chunks.joinToString("\n").ifBlank { "MMS" },
                                cursor.getLong(1) * 1000,
                                cursor.getInt(3) != 1,
                                cursor.getInt(2) == 1,
                                mmsMessageStatus(pending, cursor.getInt(3), cursor.getInt(6)),
                                subscriptionId = cursor.getInt(5),
                                mmsId = id,
                                attachments = attachments,
                            )
                    }
                }
        }
        val flags = mutableMapOf<String, ThreadFlags>()
        readableDatabase
            .query(
                "threads",
                arrayOf("thread_key", "archived", "spam", "trusted", "blocked"),
                null,
                null,
                null,
                null,
                null,
            )
            .use { cursor ->
                while (cursor.moveToNext()) flags[cursor.getString(0)] =
                    ThreadFlags(
                        cursor.getInt(1) == 1,
                        cursor.getInt(2),
                        cursor.getInt(3) == 1,
                        cursor.getInt(4) == 1,
                    )
            }
        val labels = mutableMapOf<String, List<String>>()
        readableDatabase
            .query(
                "classifications",
                arrayOf("message_id", "label", "model_version", "decision", "reasons"),
                null,
                null,
                null,
                null,
                null,
            )
            .use { cursor ->
                while (cursor.moveToNext()) labels[cursor.getString(0)] =
                    (1..4).map { cursor.getString(it) }
            }

        return messages
            .groupBy { key(it.address) }
            .map { (thread, items) ->
                val sorted =
                    items
                        .sortedBy { it.date }
                        .map { item ->
                            val classification =
                                labels[item.id]?.takeUnless { assessmentNeedsRefresh(it[1]) }
                            item.copy(
                                modelLabel = classification?.get(0),
                                modelVersion = classification?.get(1),
                                riskDecision = classification?.get(2),
                                riskReasons =
                                    classification?.get(3)?.split(",")?.filter { it.isNotBlank() }
                                        ?: emptyList(),
                            )
                        }
                val flag = flags[thread] ?: ThreadFlags(false, -1, false, false)
                val predictedSpam =
                    context
                        .getSharedPreferences("messenger_settings", 0)
                        .getBoolean("auto_spam", true) &&
                        !flag.trusted &&
                        sorted.any {
                            !it.outgoing &&
                                !assessmentNeedsRefresh(it.modelVersion) &&
                                it.riskDecision == "spam"
                        }
                Conversation(
                    thread,
                    sorted.last().address,
                    sorted,
                    flag.archived,
                    flag.blocked ||
                        if (flag.spamOverride == -1) predictedSpam else flag.spamOverride == 1,
                    flag.trusted,
                    if (flag.spamOverride == -1) null else flag.spamOverride == 1,
                    flag.blocked,
                )
            }
            .sortedByDescending { it.latest.date }
    }

    @Synchronized
    fun senderFilters(): List<SenderFilter> =
        readableDatabase
            .query(
                "threads",
                arrayOf("thread_key", "sender_address"),
                "blocked=1 AND sender_address IS NOT NULL",
                null,
                null,
                null,
                "rowid DESC",
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        SenderFilter(cursor.getString(0), cipher.decrypt(cursor.getString(1)))
                    )
                }
            }

    @Synchronized
    fun flag(thread: String, field: String, value: Boolean, address: String? = null) {
        require(field in setOf("archived", "spam", "trusted", "blocked"))
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO threads(thread_key) VALUES(?)",
            arrayOf(thread),
        )
        check(
            writableDatabase.update(
                "threads",
                ContentValues().apply {
                    put(field, if (value) 1 else 0)
                    if (field == "blocked" && value) {
                        require(!address.isNullOrBlank() && key(address) == thread)
                        put("sender_address", cipher.encrypt(address))
                    }
                    if (field == "trusted" && value) {
                        put("spam", -1)
                        put("blocked", 0)
                        putNull("sender_address")
                    }
                    if (field == "spam" && !value) {
                        put("blocked", 0)
                        putNull("sender_address")
                    }
                    if (field == "blocked") {
                        if (!value) putNull("sender_address")
                        put("spam", if (value) 1 else -1)
                        if (value) put("trusted", 0)
                    }
                    if (field == "spam" && value) put("trusted", 0)
                },
                "thread_key=?",
                arrayOf(thread),
            ) == 1
        ) {
            "storage_error"
        }
    }

    @Synchronized
    fun markRead(conversation: Conversation, read: Boolean = true) {
        val incoming = conversation.messages.filter { !it.outgoing }
        val selected = if (read) incoming.filter { !it.read } else incoming.takeLast(1)
        val value = if (read) 1 else 0
        selected
            .filter { it.systemId == null && it.mmsId == null }
            .map { it.id }
            .chunked(100)
            .forEach { ids ->
                check(
                    writableDatabase.update(
                        "messages",
                        ContentValues().apply { put("is_read", value) },
                        "id IN (${ids.joinToString(","){"?"}})",
                        ids.toTypedArray(),
                    ) == ids.size
                ) {
                    "storage_error"
                }
            }
        if (hasReadAccess()) {
            selected
                .mapNotNull { it.systemId }
                .chunked(100)
                .forEach { ids ->
                    check(
                        context.contentResolver.update(
                            Telephony.Sms.CONTENT_URI,
                            ContentValues().apply {
                                put("read", value)
                                if (read) put("seen", 1)
                            },
                            "_id IN (${ids.joinToString(","){"?"}})",
                            ids.map { it.toString() }.toTypedArray(),
                        ) == ids.size
                    ) {
                        "storage_error"
                    }
                }
            selected
                .mapNotNull { it.mmsId }
                .forEach { id ->
                    check(
                        context.contentResolver.update(
                            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString()),
                            ContentValues().apply {
                                put("read", value)
                                if (read) put("seen", 1)
                            },
                            null,
                            null,
                        ) == 1
                    ) {
                        "storage_error"
                    }
                }
        }
    }

    @Synchronized
    fun draft(address: String): String {
        readableDatabase
            .query(
                "threads",
                arrayOf("draft"),
                "thread_key=?",
                arrayOf(key(address)),
                null,
                null,
                null,
            )
            .use { cursor ->
                return if (cursor.moveToFirst() && !cursor.isNull(0))
                    cipher.decrypt(cursor.getString(0))
                else ""
            }
    }

    @Synchronized
    fun saveDraft(address: String, original: String) {
        if (address.isBlank()) return
        val thread = key(address)
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO threads(thread_key) VALUES(?)",
            arrayOf(thread),
        )
        check(
            writableDatabase.update(
                "threads",
                ContentValues().apply {
                    if (original.isEmpty()) putNull("draft")
                    else put("draft", cipher.encrypt(original))
                },
                "thread_key=?",
                arrayOf(thread),
            ) == 1
        ) {
            "storage_error"
        }
    }

    @Synchronized
    fun delete(conversation: Conversation) {
        if (isDefault()) {
            for (message in conversation.messages) {
                val uri =
                    message.systemId?.let {
                        Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, it.toString())
                    }
                        ?: message.mmsId?.let {
                            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, it.toString())
                        }
                if (uri != null)
                    check(context.contentResolver.delete(uri, null, null) == 1) { "storage_error" }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (ids in conversation.messages.map { it.id }.chunked(100)) {
                val selection = "message_id IN (${ids.joinToString(",") { "?" }})"
                db.delete("classifications", selection, ids.toTypedArray())
                db.delete("reports", selection, ids.toTypedArray())
            }
            db.delete("messages", "thread_key=?", arrayOf(conversation.key))
            val keepRule =
                db.query(
                        "threads",
                        arrayOf("blocked"),
                        "thread_key=?",
                        arrayOf(conversation.key),
                        null,
                        null,
                        null,
                    )
                    .use { it.moveToFirst() && it.getInt(0) == 1 }
            if (keepRule)
                db.update(
                    "threads",
                    ContentValues().apply {
                        putNull("draft")
                        put("archived", 0)
                    },
                    "thread_key=?",
                    arrayOf(conversation.key),
                )
            else db.delete("threads", "thread_key=?", arrayOf(conversation.key))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
