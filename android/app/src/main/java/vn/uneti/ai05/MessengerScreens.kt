package vn.uneti.ai05

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val CheckShieldIcon =
    ImageVector.Builder("CheckShield", 24.dp, 24.dp, 24f, 24f)
        .apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 2f)
                lineTo(3f, 6f)
                lineTo(3f, 12f)
                curveTo(3f, 17f, 6.8f, 21f, 12f, 23f)
                curveTo(17.2f, 21f, 21f, 17f, 21f, 12f)
                lineTo(21f, 6f)
                close()
                moveTo(10.5f, 16.5f)
                lineTo(6.5f, 12.5f)
                lineTo(8f, 11f)
                lineTo(10.5f, 13.5f)
                lineTo(16f, 8f)
                lineTo(17.5f, 9.5f)
                close()
            }
        }
        .build()

private val SpamMessageIcon =
    ImageVector.Builder("SpamMessage", 24.dp, 24.dp, 24f, 24f)
        .apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 3f)
                lineTo(20f, 3f)
                curveTo(21.1f, 3f, 22f, 3.9f, 22f, 5f)
                lineTo(22f, 17f)
                curveTo(22f, 18.1f, 21.1f, 19f, 20f, 19f)
                lineTo(7f, 19f)
                lineTo(2f, 23f)
                lineTo(2f, 5f)
                curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
                close()
                moveTo(12f, 5f)
                curveTo(8.7f, 5f, 6f, 7.7f, 6f, 11f)
                curveTo(6f, 14.3f, 8.7f, 17f, 12f, 17f)
                curveTo(15.3f, 17f, 18f, 14.3f, 18f, 11f)
                curveTo(18f, 7.7f, 15.3f, 5f, 12f, 5f)
                close()
                moveTo(12f, 7f)
                curveTo(14.2f, 7f, 16f, 8.8f, 16f, 11f)
                curveTo(16f, 11.7f, 15.8f, 12.4f, 15.4f, 13f)
                lineTo(10f, 7.6f)
                curveTo(10.6f, 7.2f, 11.3f, 7f, 12f, 7f)
                close()
                moveTo(8.6f, 9f)
                lineTo(14f, 14.4f)
                curveTo(13.4f, 14.8f, 12.7f, 15f, 12f, 15f)
                curveTo(9.8f, 15f, 8f, 13.2f, 8f, 11f)
                curveTo(8f, 10.3f, 8.2f, 9.6f, 8.6f, 9f)
                close()
            }
        }
        .build()

internal fun visibleConversations(
    conversations: List<Conversation>,
    filter: String,
    query: String,
): List<Conversation> =
    conversations.filter { conversation ->
        when (filter) {
            "spam" -> conversation.spam
            "archive" -> conversation.archived && !conversation.spam
            "unread" -> conversation.unread > 0 && !conversation.archived && !conversation.spam
            "trusted" -> conversation.trusted && !conversation.archived && !conversation.spam
            else -> !conversation.archived && !conversation.spam
        } &&
            (query.isBlank() ||
                conversation.address.contains(query, true) ||
                conversation.messages.any { it.body.contains(query, true) })
    }

@Composable
internal fun LocNavigation(route: String, onRoute: (String) -> Unit, spamCount: Int) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        listOf(
                Triple("inbox", R.string.all_messages, Icons.Default.Email),
                Triple("check", R.string.quick_check, CheckShieldIcon),
                Triple("spam", R.string.spam_short, SpamMessageIcon),
                Triple("settings", R.string.settings, Icons.Default.Settings),
            )
            .forEach { (key, label, icon) ->
                NavigationBarItem(
                    selected = route == key,
                    onClick = { onRoute(key) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (key == "spam" && spamCount > 0)
                                    Badge(Modifier.testTag("spam_unread_badge")) {
                                        Text(spamCount.toString())
                                    }
                            }
                        ) {
                            Icon(icon, stringResource(label))
                        }
                    },
                    modifier = Modifier.testTag("nav_$key"),
                )
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxScreen(
    state: MessengerState,
    query: String,
    onQuery: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    hasReceive: Boolean,
    onSetup: () -> Unit,
    onOpen: (Conversation) -> Unit,
    onArchive: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onCompose: () -> Unit,
    onActions: (Conversation) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val visible =
        remember(state.conversations, filter, query) {
            visibleConversations(state.conversations, filter, query)
        }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("inbox_list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (filter == "spam") R.string.spam_messages else R.string.inbox_title
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (filter != "spam")
                        IconButton(onClick = onCompose) {
                            Icon(Icons.Default.Edit, stringResource(R.string.new_conversation))
                        }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (
                        state.mode == "unselected" ||
                            state.mode == "default" && !state.isDefault ||
                            state.mode == "observer" && !hasReceive
                    )
                        TextButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = onSetup,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Icon(Icons.Default.Info, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (state.mode == "default") R.string.setup_required
                                    else R.string.sms_permission_short
                                )
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text(stringResource(R.string.search_messages)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty())
                                IconButton(onClick = { onQuery("") }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                                }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors =
                            TextFieldDefaults.colors(
                                unfocusedIndicatorColor =
                                    androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor =
                                    androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("search"),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (filter != "spam") ConversationFilter(filter, onFilter)
                        else
                            Text(
                                stringResource(R.string.spam_short),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        if (!state.loading)
                            Text(
                                visible.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                            )
                    }
                }
            }
            if (state.loading && state.conversations.isEmpty())
                item { LoadingSkeleton(Modifier.padding(horizontal = 20.dp)) }
            if (visible.isEmpty() && !state.loading)
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .fillParentMaxHeight(.5f)
                            .padding(horizontal = 32.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(
                                if (query.isNotBlank()) R.string.empty_search
                                else if (filter == "inbox") R.string.empty_inbox
                                else R.string.empty_folder
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (query.isNotBlank())
                            TextButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = { onQuery("") },
                            ) {
                                Text(stringResource(R.string.clear_search))
                            }
                        if (
                            filter == "inbox" &&
                                query.isBlank() &&
                                (state.mode == "unselected" ||
                                    state.mode == "observer" && !hasReceive ||
                                    state.mode == "default" && !state.isDefault)
                        ) {
                            Text(
                                stringResource(
                                    if (state.mode == "observer") R.string.empty_inbox_observer
                                    else R.string.empty_inbox_default
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onSetup) {
                                Text(stringResource(R.string.configure_sms))
                            }
                        }
                    }
                }
            items(visible, key = { it.key }) { conversation ->
                SwipeConversation(
                    conversation,
                    onOpen = { onOpen(conversation) },
                    onArchive = { onArchive(conversation) },
                    onDelete = { onDelete(conversation) },
                    onActions = { onActions(conversation) },
                )
            }
        }
    }
}

@Composable
private fun ConversationFilter(filter: String, onFilter: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options =
        listOf(
            "inbox" to R.string.filter_all,
            "unread" to R.string.filter_unread,
            "trusted" to R.string.filter_trusted,
            "archive" to R.string.archived_messages,
        )
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.testTag("conversation_filter"),
        ) {
            Text(
                stringResource(
                    options.firstOrNull { it.first == filter }?.second ?: R.string.filter_all
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (filter == key) Icon(Icons.Default.Check, null) },
                    onClick = {
                        onFilter(key)
                        expanded = false
                    },
                    modifier = Modifier.testTag("filter_$key"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeConversation(
    conversation: Conversation,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onActions: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val archiveLabel =
        stringResource(if (conversation.archived) R.string.unarchive else R.string.archive)
    val deleteLabel = stringResource(R.string.delete_conversation)
    val contextActionLabel = stringResource(R.string.conversation_options)
    val currentArchive by rememberUpdatedState(onArchive)
    val currentDelete by rememberUpdatedState(onDelete)
    val swipe =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> currentArchive()
                    SwipeToDismissBoxValue.EndToStart -> currentDelete()
                    else -> Unit
                }
                false
            }
        )
    SwipeToDismissBox(
        state = swipe,
        modifier =
            Modifier.padding(horizontal = 4.dp)
                .testTag("conversation_${conversation.key}")
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(contextActionLabel) {
                                onActions()
                                true
                            },
                            CustomAccessibilityAction(archiveLabel) {
                                onArchive()
                                true
                            },
                            CustomAccessibilityAction(deleteLabel) {
                                onDelete()
                                true
                            },
                        )
                },
        backgroundContent = {
            val archive = swipe.dismissDirection != SwipeToDismissBoxValue.EndToStart
            Surface(
                color = if (archive) colors.primaryContainer else colors.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    horizontalArrangement = if (archive) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (archive) Icons.Default.Check else Icons.Default.Delete, null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (archive) archiveLabel else deleteLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
    ) {
        Column {
            Surface(color = colors.background, modifier = Modifier.fillMaxWidth()) {
                ConversationRow(conversation, onOpen, onActions)
            }
            HorizontalDivider(
                Modifier.padding(start = 76.dp, end = 16.dp),
                color = colors.outlineVariant.copy(alpha = .5f),
            )
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: MessengerState,
    hasReceive: Boolean,
    hasRead: Boolean,
    hasSend: Boolean,
    hasNotifications: Boolean,
    onMonitor: (Boolean) -> Unit,
    onAutoSpam: (Boolean) -> Unit,
    onPermission: (Array<String>) -> Unit,
    onRole: () -> Unit,
    onMode: () -> Unit,
    modifier: Modifier,
    onUnfilter: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val appearance = remember { context.getSharedPreferences("appearance", 0) }
    var theme by remember { mutableStateOf(appearance.getString("theme", "system") ?: "system") }
    var themeDialog by remember { mutableStateOf(false) }
    val themeOptions =
        listOf(
            "system" to R.string.theme_system,
            "light" to R.string.theme_light,
            "dark" to R.string.theme_dark,
        )
    if (themeDialog)
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text(stringResource(R.string.appearance)) },
            text = {
                Column {
                    themeOptions.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = theme == key,
                                    role = Role.RadioButton,
                                    onClick = {
                                        theme = key
                                        appearance.edit().putString("theme", key).apply()
                                        themeDialog = false
                                    },
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = theme == key, onClick = null)
                            Text(stringResource(label))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { themeDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    var senderFiltersVisible by remember { mutableStateOf(false) }
    if (senderFiltersVisible) {
        AlertDialog(
            onDismissRequest = { senderFiltersVisible = false },
            title = { Text(stringResource(R.string.filtered_senders)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sender_filter_scope))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (state.senderFilters.isEmpty())
                            item { Text(stringResource(R.string.no_filtered_senders)) }
                        items(state.senderFilters, key = { it.key }) { sender ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(sender.address, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onUnfilter(sender.key) }) {
                                    Text(stringResource(R.string.remove_sender_filter))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { senderFiltersVisible = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    var detail by remember { mutableStateOf<String?>(null) }
    if (detail != null)
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
                LazyColumn(
                    Modifier.weight(1f, false).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            stringResource(
                                if (detail == "privacy") R.string.privacy_title
                                else R.string.filter_details
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    item {
                        Text(
                            stringResource(
                                if (detail == "privacy") R.string.privacy_messenger
                                else R.string.model_scope_short
                            )
                        )
                    }
                    if (detail == "model") {
                        item { Text(stringResource(R.string.model_limits)) }
                        item {
                            Text(
                                stringResource(R.string.load_recent, MessageStore.MAX_SYSTEM_SMS),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item { ModelEvidence() }
                    }
                }
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = { detail = null },
                    modifier =
                        Modifier.align(Alignment.End).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    SettingsRow(
                        Icons.Default.Refresh,
                        stringResource(R.string.mode),
                        stringResource(
                            if (state.mode == "unselected") R.string.setup_required
                            else if (state.mode == "observer") R.string.observer_title
                            else R.string.default_title
                        ),
                        onMode,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.auto_spam_sort),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.auto_spam_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.autoSpam,
                        onCheckedChange = onAutoSpam,
                        modifier = Modifier.testTag("auto_spam_switch"),
                    )
                }
            }
            item { CommunitySettings() }
            item {
                SettingsRow(
                    Icons.Default.Person,
                    stringResource(R.string.filtered_senders),
                    stringResource(R.string.filtered_senders_count, state.senderFilters.size),
                ) {
                    senderFiltersVisible = true
                }
            }
            item {
                SettingsRow(
                    Icons.Default.Settings,
                    stringResource(R.string.appearance),
                    stringResource(
                        themeOptions.firstOrNull { it.first == theme }?.second
                            ?: R.string.theme_system
                    ),
                ) {
                    themeDialog = true
                }
            }

            item {
                Column {
                    Text(
                        stringResource(R.string.sms_and_notifications),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column {
                            if (state.mode == "observer") {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.auto_filter),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            stringResource(
                                                if (!hasReceive) R.string.sms_permission_short
                                                else if (state.monitoring) R.string.monitor_active
                                                else R.string.filter_paused
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = state.monitoring && hasReceive,
                                        onCheckedChange = onMonitor,
                                        enabled = hasReceive,
                                        modifier = Modifier.testTag("monitor_switch"),
                                    )
                                }
                                if (!hasReceive)
                                    SettingsRow(
                                        Icons.Default.Email,
                                        stringResource(R.string.sms_access),
                                        stringResource(R.string.sms_permission_short),
                                    ) {
                                        onPermission(arrayOf(Manifest.permission.RECEIVE_SMS))
                                    }
                            } else {
                                SettingsRow(
                                    Icons.Default.Email,
                                    stringResource(R.string.default_sms_short),
                                    stringResource(
                                        if (state.isDefault) R.string.sms_role_selected
                                        else R.string.setup_required
                                    ),
                                    onRole,
                                )
                                if (state.isDefault && !hasRead)
                                    SettingsRow(
                                        Icons.Default.Email,
                                        stringResource(R.string.read_permission),
                                        stringResource(R.string.not_granted),
                                    ) {
                                        onPermission(arrayOf(Manifest.permission.READ_SMS))
                                    }
                                if (state.isDefault && !hasSend)
                                    SettingsRow(
                                        Icons.AutoMirrored.Filled.Send,
                                        stringResource(R.string.send_permission),
                                        stringResource(R.string.not_granted),
                                    ) {
                                        onPermission(arrayOf(Manifest.permission.SEND_SMS))
                                    }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            SettingsRow(
                                Icons.Default.Notifications,
                                stringResource(R.string.notifications_short),
                                stringResource(
                                    if (hasNotifications) R.string.notifications_private
                                    else R.string.not_granted
                                ),
                            ) {
                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                        context.checkSelfPermission(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                )
                                    onPermission(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                else
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(
                                                Settings.EXTRA_APP_PACKAGE,
                                                context.packageName,
                                            )
                                    )
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    Text(
                        stringResource(R.string.about_filter),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column {
                            SettingsRow(
                                Icons.Default.Info,
                                stringResource(R.string.filter_details),
                                stringResource(R.string.english_on_device),
                            ) {
                                detail = "model"
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            SettingsRow(
                                Icons.Default.Lock,
                                stringResource(R.string.privacy_title),
                                stringResource(R.string.privacy_short),
                            ) {
                                detail = "privacy"
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.app_identity, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeScreen(onObserver: () -> Unit, onDefault: () -> Unit, onBack: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mode)) },
                navigationIcon = {
                    if (onBack != null)
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).widthIn(max = 720.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.choose_mode_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.choose_mode_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.observer_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.observer_mode_brief),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        shape = MaterialTheme.shapes.small,
                        onClick = onObserver,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.use_observer))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.default_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.default_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = onDefault,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.use_default))
                    }
                    TextButton(
                        shape = MaterialTheme.shapes.small,
                        onClick = { expanded = !expanded },
                    ) {
                        Text(stringResource(R.string.how_observer_works))
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            null,
                        )
                    }
                    if (expanded) {
                        Text(stringResource(R.string.observer_description))
                        Text(stringResource(R.string.observer_storage))
                    }
                }
            }
        }
    }
}
