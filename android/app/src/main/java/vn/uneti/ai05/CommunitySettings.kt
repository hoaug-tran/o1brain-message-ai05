package vn.uneti.ai05

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun CommunitySettings() {
    val client = CommunityClient.get(LocalContext.current)
    val state by client.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    TextButton(onClick = { visible = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.community_title))
    }
    if (visible)
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text(stringResource(R.string.community_title)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.community_scope))
                        if (state.error != null)
                            Text(
                                stringResource(
                                    if (state.error == "community_invalid_phone")
                                        R.string.community_invalid_phone
                                    else R.string.community_failed
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.take(32) },
                            label = { Text(stringResource(R.string.community_phone)) },
                            singleLine = true,
                        )
                        TextButton(
                            enabled = phone.isNotBlank() && !state.busy,
                            onClick = { client.lookup(phone) },
                        ) {
                            Text(stringResource(R.string.community_lookup))
                        }
                        state.lookup?.let { (number, count) ->
                            Text(stringResource(R.string.community_count, number, count))
                        }
                    }
                    items(state.reports, key = { it.phone }) { row ->
                        Column {
                            Text(row.phone, style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(
                                    when (row.category) {
                                        "phishing" -> R.string.community_phishing
                                        "advertising" -> R.string.community_advertising
                                        else -> R.string.community_spam
                                    }
                                )
                            )
                            Text(
                                stringResource(
                                    when (row.status) {
                                        "sent" -> R.string.community_sent
                                        "failed" -> R.string.community_send_failed
                                        "rejected" -> R.string.community_rejected
                                        else -> R.string.community_pending
                                    }
                                )
                            )
                            if (row.retract)
                                Text(stringResource(R.string.community_retract_pending))
                            if (row.status == "sent")
                                Text(
                                    stringResource(R.string.community_count_at_send, row.reporters)
                                )
                            Row {
                                if (row.status != "sent")
                                    TextButton(
                                        enabled = !state.busy,
                                        onClick = { client.retry(row) },
                                    ) {
                                        Text(stringResource(R.string.community_retry))
                                    }
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = { client.retract(row) },
                                ) {
                                    Text(stringResource(R.string.community_retract))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { visible = false }) { Text(stringResource(R.string.close)) }
            },
        )
}
