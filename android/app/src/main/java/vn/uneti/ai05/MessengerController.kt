package vn.uneti.ai05

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class MessengerState(
    val conversations: List<Conversation> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val mode: String = "unselected",
    val isDefault: Boolean = false,
    val monitoring: Boolean = false,
    val autoSpam: Boolean = true,
    val senderFilters: List<SenderFilter> = emptyList(),
) {
    val unreadSpamCount: Int
        get() = conversations.filter { it.spam }.sumOf { it.unread }
}

class MessengerController private constructor(private val context: Context) {
    private val store = MessageStore(context)
    private val preferences = context.getSharedPreferences("messenger_settings", 0)
    private var analysisUnavailable = false
    private val refreshQueued = AtomicBoolean(false)
    private val executor =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val mutableState =
        MutableStateFlow(
            MessengerState(
                mode = preferences.getString("mode", "unselected") ?: "unselected",
                monitoring = preferences.getBoolean("monitoring", false),
                autoSpam = preferences.getBoolean("auto_spam", true),
            )
        )
    val state: StateFlow<MessengerState> = mutableState
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    init {
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        refresh()
    }

    private fun publish(
        conversations: List<Conversation> = store.conversations(),
        analysisError: String? = null,
    ) {
        val isDefault = store.isDefault()
        val mode =
            if (isDefault) "default"
            else preferences.getString("mode", "unselected") ?: "unselected"
        val senderFilters = store.senderFilters()
        mutableState.update {
            it.copy(
                conversations = conversations,
                loading = false,
                isDefault = isDefault,
                mode = mode,
                senderFilters = senderFilters,
                error = analysisError ?: it.error,
            )
        }
    }

    fun refresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        execute(
            {
                refreshQueued.set(false)
                val conversations = analyzedConversations()
                publish(conversations, if (analysisUnavailable) "model_unavailable" else null)
            },
            { refreshQueued.set(false) },
        )
    }

    fun unfilterSender(thread: String) = execute {
        store.flag(thread, "blocked", false)
        publish()
    }

    private fun analyzedConversations(): List<Conversation> {
        analysisUnavailable = false
        val conversations = store.conversations()
        val pending =
            conversations
                .flatMap { it.messages }
                .filter {
                    !it.outgoing &&
                        it.status != "mms_pending" &&
                        assessmentNeedsRefresh(it.modelVersion) &&
                        inputStatus(it.body) == null
                }
                .take(32)
        var saved = 0
        pending.forEach { message ->
            val risk = AppRuntime.get(context).assessMessage(message.body)
            if (risk == null) analysisUnavailable = true
            else {
                store.classify(
                    message.id,
                    risk.prediction.label,
                    risk.version,
                    risk.decision,
                    risk.reasons,
                )
                saved++
            }
        }
        if (pending.size == 32 && saved == 32) refresh()
        return if (saved > 0) store.conversations() else conversations
    }

    fun report(message: MessageItem, category: String, onComplete: () -> Unit) {
        CommunityClient.get(context).report(message.address, category, onComplete)
    }

    fun setAutoSpam(enabled: Boolean) {
        preferences.edit().putBoolean("auto_spam", enabled).apply()
        mutableState.update { current -> current.copy(autoSpam = enabled) }
        refresh()
    }

    fun selectMode(mode: String) {
        require(mode in setOf("observer", "default"))
        preferences
            .edit()
            .putString("mode", mode)
            .putBoolean("monitoring", mode == "default")
            .apply()
        mutableState.update { current -> current.copy(mode = mode, monitoring = mode == "default") }
        refresh()
    }

    fun setMonitoring(enabled: Boolean) {
        val permitted =
            enabled &&
                context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED
        preferences.edit().putBoolean("monitoring", permitted).apply()
        mutableState.update { current -> current.copy(monitoring = permitted) }
    }

    fun observes(): Boolean =
        preferences.getString("mode", "") == "observer" &&
            preferences.getBoolean("monitoring", false)

    fun receive(
        address: String,
        original: String,
        date: Long,
        subscription: Int,
        defaultDelivery: Boolean,
        finished: () -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val finishOnce = {
            if (completed.compareAndSet(false, true)) finished()
            Unit
        }
        execute(
            {
                try {
                    if (defaultDelivery && !store.isDefault()) return@execute
                    if (!defaultDelivery && (!observes() || store.isDefault())) return@execute
                    val id =
                        if (defaultDelivery) {
                            SmsOperations.receive(context, address, original, date, subscription)
                                ?.lastPathSegment
                                ?.let { "sms:$it" } ?: error("storage_error")
                        } else store.storeObserved(address, original, date)
                    val result = AppRuntime.get(context).assessMessage(original)
                    if (result != null)
                        store.classify(
                            id,
                            result.prediction.label,
                            result.version,
                            result.decision,
                            result.reasons,
                        )
                    val conversations = store.conversations()
                    val conversation = conversations.firstOrNull { it.key == store.key(address) }
                    publish(
                        conversations,
                        if (result == null && inputStatus(original) == null) "model_unavailable"
                        else null,
                    )
                    if (
                        conversation?.blocked != true &&
                            (defaultDelivery || result?.decision in setOf("spam", "review"))
                    )
                        notifyMessage(
                            conversation?.key ?: store.key(address),
                            if (conversation?.spam == true) "spam"
                            else if (result?.decision in setOf("spam", "review")) "review"
                            else "normal",
                        )
                } finally {
                    finishOnce()
                }
            },
            finishOnce,
        )
    }

    fun setRead(conversation: Conversation, read: Boolean, onComplete: () -> Unit = {}) = execute {
        store.markRead(conversation, read)
        publish()
        if (read)
            context
                .getSystemService(NotificationManager::class.java)
                .cancel(conversation.key.hashCode())
        Handler(Looper.getMainLooper()).post { onComplete() }
    }

    fun open(conversation: Conversation) = setRead(conversation, true)

    fun flag(
        conversation: Conversation,
        field: String,
        value: Boolean,
        onComplete: () -> Unit = {},
    ) = execute {
        store.flag(conversation.key, field, value, conversation.address)
        publish()
        if (value && field in setOf("blocked", "spam"))
            context
                .getSystemService(NotificationManager::class.java)
                .cancel(conversation.key.hashCode())
        Handler(Looper.getMainLooper()).post { onComplete() }
    }

    fun delete(conversation: Conversation, onComplete: () -> Unit = {}) =
        execute(
            "delete_failed",
            {
                store.delete(conversation)
                publish()
                Handler(Looper.getMainLooper()).post { onComplete() }
            },
        )

    fun send(address: String, original: String, subscription: Int, onComplete: (Boolean) -> Unit) =
        execute(
            "send_failed",
            {
                store.saveDraft(address, original)
                SmsOperations.send(context, address, original, subscription)
                Handler(Looper.getMainLooper()).post { onComplete(true) }
                execute(
                    "storage_error",
                    {
                        if (store.draft(address) == original) store.saveDraft(address, "")
                        refresh()
                    },
                )
            },
            { Handler(Looper.getMainLooper()).post { onComplete(false) } },
        )

    fun draft(address: String, loaded: (String) -> Unit) = execute {
        val value = store.draft(address)
        Handler(Looper.getMainLooper()).post { loaded(value) }
    }

    fun saveDraft(address: String, original: String) = execute {
        store.saveDraft(address, original)
    }

    fun clearError() {
        mutableState.update { current -> current.copy(error = null) }
    }

    fun reportError(code: String) {
        mutableState.update { current -> current.copy(error = code) }
    }

    fun downloadMms(id: Long, subscription: Int) = execute {
        MmsOperations.download(context, id, subscription)
    }

    fun sendMms(address: String, original: String, attachment: Uri, onComplete: (Boolean) -> Unit) =
        execute(
            "send_failed",
            {
                store.saveDraft(address, original)
                MmsOperations.send(context, address, original, attachment)
                Handler(Looper.getMainLooper()).post { onComplete(true) }
                execute(
                    "storage_error",
                    {
                        if (store.draft(address) == original) store.saveDraft(address, "")
                        refresh()
                    },
                )
            },
            { Handler(Looper.getMainLooper()).post { onComplete(false) } },
        )

    fun openAttachment(attachment: Attachment) = execute {
        MmsOperations.openAttachment(context, attachment)
    }

    private fun execute(work: () -> Unit) = execute(work, {})

    private fun execute(work: () -> Unit, onFailure: () -> Unit) =
        execute("operation_failed", work, onFailure)

    private fun execute(errorCode: String, work: () -> Unit, onFailure: () -> Unit = {}) {
        try {
            executor.execute {
                try {
                    work()
                } catch (failure: Exception) {
                    mutableState.update { current ->
                        current.copy(
                            loading = false,
                            error =
                                when {
                                    failure is SecurityException -> "permission_missing"
                                    failure is IllegalArgumentException &&
                                        failure.message == "invalid_recipient" ->
                                        "invalid_recipient"
                                    failure.message == "storage_error" -> "storage_error"
                                    else -> errorCode
                                },
                        )
                    }
                    onFailure()
                }
            }
        } catch (_: RejectedExecutionException) {
            mutableState.update { current -> current.copy(error = "busy") }
            onFailure()
        }
    }

    private fun notifyMessage(thread: String, decision: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "messages",
                context.getString(R.string.messages_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("thread_key", thread)
        val pending =
            PendingIntent.getActivity(
                context,
                thread.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, "messages")
                .setSmallIcon(R.drawable.ic_loc_notification)
                .setContentTitle(
                    context.getString(
                        when (decision) {
                            "spam" -> R.string.spam_result
                            "review" -> R.string.needs_review
                            else -> R.string.new_message
                        }
                    )
                )
                .setContentText(context.getString(R.string.private_notification))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        manager.notify(thread.hashCode(), notification)
    }

    companion object {
        @Volatile private var instance: MessengerController? = null

        fun get(context: Context): MessengerController =
            instance
                ?: synchronized(this) {
                    instance
                        ?: MessengerController(context.applicationContext).also { instance = it }
                }
    }
}
