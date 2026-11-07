package vn.uneti.ai05

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun ReportMessageDialog(
    address: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val client = CommunityClient.get(LocalContext.current)
    val state by client.state.collectAsState()
    var category by remember { mutableStateOf("spam") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.report_message)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(address, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.report_scope))
                listOf(
                        "spam" to R.string.community_spam,
                        "phishing" to R.string.community_phishing,
                        "advertising" to R.string.community_advertising,
                    )
                    .forEach { (value, label) ->
                        TextButton(onClick = { category = value }) {
                            RadioButton(selected = category == value, onClick = null)
                            Text(stringResource(label))
                        }
                    }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.error != null)
                    Text(
                        stringResource(
                            if (state.error == "community_invalid_phone")
                                R.string.community_invalid_phone
                            else R.string.community_failed
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = { onConfirm(category) }) {
                Text(stringResource(R.string.report_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationActions(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onRead: (Boolean) -> Unit,
    onFlag: (String, Boolean) -> Unit,
    onReport: (MessageItem) -> Unit,
    onDelete: () -> Unit,
) {
    val incoming = conversation.messages.lastOrNull { !it.outgoing }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    conversation.address,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item {
                TextButton(
                    enabled = incoming != null,
                    onClick = { onRead(conversation.unread > 0) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conversation.unread > 0) R.string.mark_read
                            else R.string.mark_unread
                        )
                    )
                }
            }
            item {
                TextButton(
                    onClick = { onFlag("trusted", !conversation.trusted) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conversation.trusted) R.string.untrust_sender
                            else R.string.trust_sender
                        )
                    )
                }
            }
            item {
                TextButton(
                    onClick = { onFlag("archived", !conversation.archived) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conversation.archived) R.string.unarchive else R.string.archive
                        )
                    )
                }
            }
            item {
                TextButton(
                    onClick = { onFlag("spam", !conversation.spam) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conversation.spam) R.string.not_spam else R.string.move_spam
                        )
                    )
                }
            }
            item {
                TextButton(
                    onClick = { onFlag("blocked", !conversation.blocked) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (conversation.blocked) R.string.unfilter_sender
                            else R.string.filter_sender
                        )
                    )
                }
            }
            item {
                TextButton(
                    enabled = incoming != null,
                    onClick = { incoming?.let(onReport) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.report_message))
                }
            }
            item {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.delete_conversation),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
