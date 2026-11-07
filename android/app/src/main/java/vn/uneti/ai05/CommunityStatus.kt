package vn.uneti.ai05

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
internal fun CommunityStatus(address: String) {
    val client = CommunityClient.get(LocalContext.current)
    val state by client.state.collectAsState()
    val number = runCatching { client.phone(address) }.getOrNull()
    if (number == null) {
        Text(stringResource(R.string.community_sender_unavailable))
        return
    }
    TextButton(enabled = !state.busy, onClick = { client.lookup(address) }) {
        Text(stringResource(R.string.community_sender_check))
    }
    state.lookup?.takeIf { it.first == number }?.let {
        Text(stringResource(R.string.community_count, it.first, it.second))
    }
    state.reports.firstOrNull { it.phone == number }?.let { row ->
        Text(stringResource(when (row.status) {
            "sent" -> R.string.community_sent
            "failed" -> R.string.community_send_failed
            "rejected" -> R.string.community_rejected
            else -> R.string.community_pending
        }))
        Row {
            if (row.status != "sent") TextButton(enabled = !state.busy, onClick = { client.retry(row) }) {
                Text(stringResource(R.string.community_retry))
            }
            TextButton(enabled = !state.busy, onClick = { client.retract(row) }) {
                Text(stringResource(R.string.community_retract))
            }
        }
    }
}
