package vn.uneti.ai05

import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun MessageAssessment(message: MessageItem) {
    var details by remember { mutableStateOf(false) }
    val label = message.riskDecision
    if (label !in setOf("spam", "review")) return
    val title = stringResource(riskTitle(label!!))
    TextButton(
        shape = MaterialTheme.shapes.small,
        onClick = { details = true },
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (label == "spam") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message.riskReasons.forEach { Text(stringResource(riskReason(it))) }
                    Text(stringResource(R.string.caution))

                }
            },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { details = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conversation: Conversation,
    onOpen: () -> Unit,
    onActions: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onActions,
                onLongClickLabel = stringResource(R.string.conversation_options),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (conversation.trusted) Icon(Icons.Default.Star, null)
                else Icon(Icons.Default.Person, null)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                conversation.address.ifBlank { stringResource(R.string.unknown_sender) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (conversation.unread > 0) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                conversation.latest.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (conversation.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT, Locale.forLanguageTag("vi-VN"))
                        .format(Date(conversation.latest.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unread > 0)
                    Text(
                        stringResource(R.string.unread_count, conversation.unread),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
            }
            if (
                !conversation.spam &&
                    conversation.messages.lastOrNull { !it.outgoing }?.riskDecision in setOf("spam", "review")
            )
                Text(
                    stringResource(R.string.needs_review),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatScreen(
    controller: MessengerController,
    address: String,
    onAddress: (String) -> Unit,
    conversation: Conversation?,
    isDefault: Boolean,
    hasSend: Boolean,
    modifier: Modifier,
    initialBody: String,
    onDefault: () -> Unit,
    onForward: (String) -> Unit,
    onSpamChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val inbox by controller.state.collectAsState()
    val recipients = remember(inbox.conversations, address) {
        recentRecipients(inbox.conversations, address)
    }
    val clipboard = LocalClipboardManager.current
    var editor by
        rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(initialBody, TextRange(initialBody.length)))
        }
    val content = editor.text
    var messageActions by remember { mutableStateOf<MessageItem?>(null) }
    var sending by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<MessageItem?>(null) }
    var reportSaved by remember { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<Uri?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { attachment = it }
    var draftEdited by remember { mutableStateOf(false) }
    DisposableEffect(address) {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        if (address.isNotBlank())
            controller.draft(address) {
                if (active.get() && !draftEdited && content.isEmpty())
                    editor = TextFieldValue(it, TextRange(it.length))
            }
        onDispose { active.set(false) }
    }
    val latestContent by rememberUpdatedState(content)
    val latestAddress by rememberUpdatedState(address)
    val latestSending by rememberUpdatedState(sending)
    DisposableEffect(Unit) {
        onDispose {
            if (latestAddress.isNotBlank() && !latestSending)
                controller.saveDraft(latestAddress, latestContent)
        }
    }
    LaunchedEffect(address, content, sending) {
        delay(500)
        if (address.isNotBlank() && !sending) controller.saveDraft(address, content)
    }
    if (reportTarget != null)
        ReportMessageDialog(
            address = reportTarget!!.address,
            onDismiss = { reportTarget = null },
            onConfirm = { category ->
                reportTarget?.let { message ->
                    controller.report(message, category) {
                        if (reportTarget?.id == message.id) {
                            reportTarget = null
                            reportSaved = true
                        }
                    }
                }
            },
        )
    if (reportSaved)
        AlertDialog(
            onDismissRequest = { reportSaved = false },
            text = { Text(stringResource(R.string.report_saved)) },
            confirmButton = {
                TextButton(onClick = { reportSaved = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    Column(modifier.fillMaxSize().imePadding()) {
        if (conversation == null)
            OutlinedTextField(
                value = address,
                onValueChange = onAddress,
                label = { Text(stringResource(R.string.recipient)) },
                supportingText = { Text(stringResource(R.string.recipient_help)) },
                isError = address.isNotBlank() && !validRecipient(address),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = conversation != null,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (conversation == null) {
                item {
                    Text(stringResource(if (recipients.isEmpty()) R.string.recipient_empty else R.string.recent_recipients),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
                items(recipients, key = { it.key }) { recipient ->
                    ListItem(
                        headlineContent = { Text(recipient.address) },
                        leadingContent = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.clickable { onAddress(recipient.address) },
                    )
                }
            }
            items(conversation?.messages?.reversed() ?: emptyList(), key = { it.id }) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        if (message.outgoing) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier.widthIn(max = 310.dp),
                        horizontalAlignment =
                            if (message.outgoing) Alignment.End else Alignment.Start,
                    ) {
                        Surface(
                            color =
                                if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                        ) {
                            Text(
                                message.body,
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    if (message.outgoing)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                modifier =
                                    Modifier.combinedClickable(
                                            onClick = { messageActions = message },
                                            onLongClickLabel =
                                                stringResource(R.string.message_actions),
                                            onLongClick = { messageActions = message },
                                        )
                                        .padding(16.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = messageActions?.id == message.id,
                            onDismissRequest = { messageActions = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_message)) },
                                onClick = {
                                    clipboard.setText(AnnotatedString(messageActions!!.body))
                                    messageActions = null
                                },
                            )
                            if (!message.outgoing)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.report_message)) },
                                    enabled = true,
                                    onClick = {
                                        reportTarget = message
                                        messageActions = null
                                    },
                                )
                            if (conversation != null && !message.outgoing)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (conversation.spam) R.string.not_spam
                                                else R.string.move_spam
                                            )
                                        )
                                    },
                                    onClick = {
                                        controller.flag(conversation, "spam", !conversation.spam) {
                                            onSpamChanged(!conversation.spam)
                                        }
                                        messageActions = null
                                    },
                                )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.forward_message)) },
                                onClick = {
                                    val original = messageActions!!.body
                                    messageActions = null
                                    onForward(original)
                                },
                            )
                        }

                        if (!message.outgoing) MessageAssessment(message)
                        if (message.status == "mms_pending" && message.mmsId != null)
                            TextButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = {
                                    controller.downloadMms(message.mmsId, message.subscriptionId)
                                },
                            ) {
                                Text(stringResource(R.string.download_mms))
                            }
                        message.attachments.forEach { file ->
                            TextButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = { controller.openAttachment(file) },
                            ) {
                                Icon(Icons.Default.Add, null)
                                Text(
                                    stringResource(R.string.open_attachment) +
                                        " (" +
                                        file.mimeType +
                                        ")"
                                )
                            }
                        }
                        Text(
                            DateFormat.getTimeInstance(
                                    DateFormat.SHORT,
                                    Locale.forLanguageTag("vi-VN"),
                                )
                                .format(Date(message.date)) +
                                if (message.outgoing)
                                    "  " +
                                        context.getString(
                                            when (message.status) {
                                                "failed" -> R.string.send_failed
                                                "sending" -> R.string.sending
                                                "sent" -> R.string.sent
                                                else -> R.string.send_pending
                                            }
                                        )
                                else "",
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (message.status == "failed") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        if (!isDefault)
            TextButton(
                shape = MaterialTheme.shapes.small,
                onClick = onDefault,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Text(stringResource(R.string.send_requires_default))
            }
        if (attachment != null)
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.attachment_selected),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { attachment = null }) {
                    Icon(Icons.Default.Close, stringResource(R.string.remove_attachment))
                }
            }
        Row(
            Modifier.fillMaxWidth().padding(12.dp).testTag("message_composer"),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isDefault)
                IconButton(onClick = { picker.launch("image/*") }, enabled = !sending) {
                    Icon(Icons.Default.Add, stringResource(R.string.attach_file))
                }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                BasicTextField(
                    value = editor,
                    onValueChange = {
                        editor = it
                        draftEdited = true
                    },
                    enabled = !sending,
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    cursorBrush =
                        androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp, max = 128.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { field ->
                        Box {
                            if (content.isEmpty())
                                Text(
                                    stringResource(R.string.message_content),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            field()
                        }
                    },
                )
            }
            FilledIconButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    if (!isDefault) {
                        try {
                            context.startActivity(
                                Intent(
                                        Intent.ACTION_SENDTO,
                                        Uri.parse("smsto:" + Uri.encode(address)),
                                    )
                                    .putExtra("sms_body", content)
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            controller.reportError("send_failed")
                        }
                        return@FilledIconButton
                    }
                    sending = true
                    val done: (Boolean) -> Unit = { accepted ->
                        if (accepted) {
                            editor = TextFieldValue()
                            attachment = null
                        }
                        sending = false
                    }
                    if (attachment != null) controller.sendMms(address, content, attachment!!, done)
                    else
                        controller.send(
                            address,
                            content,
                            SubscriptionManager.getDefaultSmsSubscriptionId(),
                            done,
                        )
                },
                enabled =
                    validRecipient(address) &&
                        (content.isNotBlank() || attachment != null) &&
                        !sending &&
                        (!isDefault || hasSend),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    stringResource(
                        if (isDefault) R.string.send_message else R.string.send_via_current
                    ),
                )
            }
        }
    }
}
