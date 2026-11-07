package vn.uneti.ai05

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    private var composeAddress by mutableStateOf<String?>(null)
    private var composeBody by mutableStateOf("")
    private var threadKey by mutableStateOf<String?>(null)
    private var externalRequestId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        when (getSharedPreferences("appearance", 0).getString("theme", "system")) {
            "light" -> setTheme(R.style.Theme_O1Brain_Loc_Light)
            "dark" -> setTheme(R.style.Theme_O1Brain_Loc_Dark)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            LocTheme {
                MessengerApp(
                    MessengerController.get(this),
                    revision,
                    composeAddress,
                    composeBody,
                    threadKey,
                    externalRequestId,
                ) {
                    composeAddress = null
                    composeBody = ""
                    threadKey = null
                    setIntent(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(componentName)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        revision++
        MessengerController.get(this).refresh()
    }

    private fun handleIntent(intent: Intent?) {
        composeAddress = null
        composeBody = ""
        threadKey = null
        if (intent?.action == Intent.ACTION_SENDTO) {
            composeAddress = intent.data?.schemeSpecificPart?.substringBefore('?')?.take(100) ?: ""
            composeBody =
                (intent.getStringExtra("sms_body")
                        ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: "")
                    .take(20_000)
        }
        threadKey =
            if (intent?.action == Intent.ACTION_SENDTO) null
            else intent?.getStringExtra("thread_key")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        if (composeAddress != null || threadKey != null) externalRequestId++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerApp(
    controller: MessengerController,
    revision: Int,
    externalAddress: String?,
    externalBody: String = "",
    externalThread: String? = null,
    externalRequestId: Long = 0,
    handled: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val state by controller.state.collectAsState()
    var route by rememberSaveable { mutableStateOf("inbox") }
    var parentRoute by rememberSaveable { mutableStateOf("inbox") }
    val destinations = rememberSaveableStateHolder()
    var address by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("inbox") }
    var actionKey by remember { mutableStateOf<String?>(null) }
    var reportTarget by remember { mutableStateOf<MessageItem?>(null) }
    var reportSaved by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var menu by remember { mutableStateOf(false) }
    var roleError by remember { mutableStateOf(false) }
    var priorityFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    var permissionDenied by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var initialBody by rememberSaveable { mutableStateOf("") }
    var composeSession by rememberSaveable { mutableIntStateOf(0) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDenied = it.values.any { granted -> !granted }
            permissionRevision++
            if (it[Manifest.permission.RECEIVE_SMS] == true && controller.state.value.mode == "observer")
                controller.setMonitoring(true)
            controller.refresh()
        }
    val role =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionDenied = false
            permissionRevision++
            controller.refresh()
        }
    val hasReceive =
        remember(revision, permissionRevision) {
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        }
    val hasRead =
        remember(revision, permissionRevision, state.isDefault) {
            context.checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        }
    val hasSend =
        remember(revision, permissionRevision, state.isDefault) {
            context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
        }
    val hasNotifications =
        remember(revision, permissionRevision) {
            context
                .getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED)
        }
    fun requestRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val manager = context.getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_SMS))
                role.launch(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            else roleError = true
        } else
            role.launch(
                Intent("android.provider.Telephony.ACTION_CHANGE_DEFAULT")
                    .putExtra("package", context.packageName)
            )
    }
    LaunchedEffect(externalRequestId, externalAddress) {
        if (externalAddress != null) {
            parentRoute = if (route == "chat") parentRoute else route
            composeSession++
            address = externalAddress
            initialBody = externalBody
            selectedKey = null
            route = "chat"
            handled()
        }
    }
    LaunchedEffect(externalRequestId, externalThread, state.loading) {
        if (externalThread != null && !state.loading) {
            state.conversations
                .firstOrNull { it.key == externalThread }
                ?.let {
                    parentRoute = if (it.spam) "spam" else "inbox"
                    composeSession++
                    selectedKey = it.key
                    address = it.address
                    route = "chat"
                }
            handled()
        }
    }
    val selected =
        state.conversations.firstOrNull { it.key == selectedKey }
            ?: state.conversations.firstOrNull { address.isNotBlank() && it.address == address }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by
        remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) {
                priorityFeedback = null
                snackbarHost.currentSnackbarData?.dismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.setVisibleThread(null) }
    }
    LaunchedEffect(route) {
        focusManager.clearFocus()
        keyboard?.hide()
        actionKey = null
        reportTarget = null
        reportSaved = false
    }
    LaunchedEffect(priorityFeedback, route, resumed) {
        snackbarHost.currentSnackbarData?.dismiss()
        val event = priorityFeedback
        if (event != null && resumed && route == "chat")
            try {
                snackbarHost.showSnackbar(
                    context.getString(
                        if (event.second) R.string.priority_added else R.string.priority_removed,
                        event.first,
                    ),
                    duration = SnackbarDuration.Short,
                )
            } finally {
                if (priorityFeedback == event) priorityFeedback = null
                snackbarHost.currentSnackbarData?.dismiss()
            }
        else {
            priorityFeedback = null
        }
    }
    LaunchedEffect(route, selected?.key, selected?.unread, resumed) {
        controller.setVisibleThread(if (resumed && route == "chat") selected?.key else null)
        if (resumed && route == "chat" && selected != null && selected.unread > 0)
            controller.open(selected)
    }
    fun updatePriority(conversation: Conversation) {
        val session = composeSession
        val enabled = !conversation.trusted
        controller.flag(conversation, "trusted", enabled) {
            if (
                resumed &&
                    route == "chat" &&
                    composeSession == session &&
                    (selectedKey == conversation.key ||
                        selectedKey == null && address == conversation.address)
            )
                priorityFeedback = conversation.address to enabled
        }
    }
    fun open(conversation: Conversation) {
        if (route == "chat") return
        parentRoute = route
        composeSession++
        initialBody = ""
        selectedKey = conversation.key
        address = conversation.address
        route = "chat"
    }
    fun backFromChat() {
        priorityFeedback = null
        snackbarHost.currentSnackbarData?.dismiss()
        route = parentRoute.takeIf { it in setOf("inbox", "spam", "check", "settings") } ?: "inbox"
        menu = false
    }
    BackHandler {
        when (route) {
            "chat" -> backFromChat()
            "inbox" -> (context as? android.app.Activity)?.finish()
            "modes" -> route = "settings"
            else -> route = "inbox"
        }
    }
    if (route == "modes") {
        ModeScreen(
            onObserver = {
                controller.selectMode("observer")
                if (state.isDefault)
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    )
                else {
                    route = "inbox"
                    if (!hasReceive || !hasNotifications) permissions.launch(
                        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.RECEIVE_SMS)
                    )
                }
            },
            onDefault = {
                controller.selectMode("default")
                route = "inbox"
                requestRole()
            },
            onBack =
                if (state.mode == "unselected") null
                else {
                    { route = "settings" }
                },
        )
        return
    }
    state.conversations
        .firstOrNull { it.key == actionKey }
        ?.let { conversation ->
            ConversationActions(
                conversation,
                onDismiss = { actionKey = null },
                onRead = { read ->
                    actionKey = null
                    controller.setRead(conversation, read)
                },
                onFlag = { field, value ->
                    actionKey = null
                    controller.flag(conversation, field, value)
                },
                onReport = {
                    actionKey = null
                    reportTarget = it
                },
                onDelete = {
                    actionKey = null
                    deleteTarget = conversation
                },
            )
        }
    if (reportTarget != null)
        ReportMessageDialog(
            address = reportTarget!!.address,
            onDismiss = { reportTarget = null },
            onConfirm = { category ->
                reportTarget?.let { message ->
                    controller.report(message, category) {
                        reportTarget = null
                        reportSaved = true
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
    if (deleteTarget != null)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_confirmation)) },
            text = {
                Text(
                    stringResource(
                        if (state.isDefault) R.string.delete_default_warning
                        else R.string.delete_observer_warning
                    )
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        val target = deleteTarget ?: return@TextButton
                        controller.delete(target) {
                            if (deleteTarget?.key == target.key) deleteTarget = null
                            if (route == "chat" && selectedKey == target.key) backFromChat()
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    if (roleError)
        AlertDialog(
            onDismissRequest = { roleError = false },
            text = { Text(stringResource(R.string.role_unavailable)) },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { roleError = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    if (permissionDenied)
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            text = { Text(stringResource(R.string.permission_denied)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionDenied = false
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName),
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.open_android_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionDenied = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    if (state.error != null && state.error != "model_unavailable")
        AlertDialog(
            onDismissRequest = { controller.clearError() },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(stringResource(messengerErrorResource(state.error!!))) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = { controller.clearError() },
                ) {
                    Text(stringResource(R.string.close))
                }
            },
            dismissButton = {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = {
                        controller.clearError()
                        route = "settings"
                    },
                ) {
                    Text(stringResource(R.string.settings))
                }
            },
        )
    val destination = if (route == "chat") parentRoute else route
    val chatTransition = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    chatTransition.targetState = route == "chat"
    val chatShown = chatTransition.currentState || chatTransition.targetState
    Box(Modifier.fillMaxSize()) {
        destinations.SaveableStateProvider(destination) {
            Box(
                if (chatShown)
                    Modifier.fillMaxSize()
                        .clearAndSetSemantics {}
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(
                                        androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                    )
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                else Modifier.fillMaxSize()
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        LocNavigation(
                            destination,
                            onRoute = { route = it },
                            spamCount = state.unreadSpamCount,
                        )
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        when (destination) {
                            "check" -> QuickCheckScreen()
                            "settings" ->
                                SettingsScreen(
                                    state,
                                    hasReceive,
                                    hasRead,
                                    hasSend,
                                    hasNotifications,
                                    onMonitor = { controller.setMonitoring(it) },
                                    onAutoSpam = { controller.setAutoSpam(it) },
                                    onPermission = { permissions.launch(it) },
                                    onRole = { requestRole() },
                                    onMode = { route = "modes" },
                                    onUnfilter = { controller.unfilterSender(it) },
                                    modifier = Modifier,
                                )
                            else ->
                                InboxScreen(
                                    state,
                                    query,
                                    { query = it },
                                    if (destination == "spam") "spam" else filter,
                                    { filter = it },
                                    hasReceive,
                                    onSetup = { route = "settings" },
                                    onOpen = { open(it) },
                                    onActions = { actionKey = it.key },
                                    onArchive = { controller.flag(it, "archived", !it.archived) },
                                    onDelete = { deleteTarget = it },
                                    onCompose = {
                                        parentRoute = if (route == "chat") parentRoute else route
                                        composeSession++
                                        address = ""
                                        initialBody = ""
                                        selectedKey = null
                                        route = "chat"
                                    },
                                )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visibleState = chatTransition,
            enter = slideInHorizontally(animationSpec = LocMotion.Chat) { it },
            exit = slideOutHorizontally(animationSpec = LocMotion.Chat) { it },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHost) },
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    address.ifBlank {
                                        context.getString(R.string.new_conversation)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selected?.trusted == true)
                                    Text(
                                        stringResource(R.string.filter_trusted),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { backFromChat() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.back),
                                )
                            }
                        },
                        actions = {
                            if (selected != null)
                                IconToggleButton(checked = selected.trusted, onCheckedChange = { updatePriority(selected) }) {
                                    Icon(
                                        tint =
                                            if (selected.trusted) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        imageVector =
                                            if (selected.trusted) Icons.Default.Star
                                            else PriorityOutlineIcon,
                                        contentDescription =
                                            stringResource(
                                                if (selected.trusted) R.string.untrust_sender
                                                else R.string.trust_sender
                                            ),
                                    )
                                }
                            if (route == "chat" && selected != null)
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            stringResource(R.string.conversation_options),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menu,
                                        onDismissRequest = { menu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (selected.unread > 0) R.string.mark_read
                                                        else R.string.mark_unread
                                                    )
                                                )
                                            },
                                            enabled = selected.messages.any { !it.outgoing },
                                            onClick = {
                                                val read = selected.unread > 0
                                                menu = false
                                                if (!read) backFromChat()
                                                controller.setRead(selected, read)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (selected.archived) R.string.unarchive
                                                        else R.string.archive
                                                    )
                                                )
                                            },
                                            onClick = {
                                                controller.flag(
                                                    selected,
                                                    "archived",
                                                    !selected.archived,
                                                )
                                                menu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.report_message)) },
                                            enabled = selected.messages.any { !it.outgoing },
                                            onClick = {
                                                reportTarget = selected.messages.lastOrNull { !it.outgoing }
                                                menu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (selected.spam) R.string.not_spam
                                                        else R.string.move_spam
                                                    )
                                                )
                                            },
                                            onClick = {
                                                val moveToSpam = !selected.spam
                                                controller.flag(selected, "spam", moveToSpam) {
                                                    backFromChat()
                                                    query = ""
                                                    if (!moveToSpam) filter = "inbox"
                                                    route = if (moveToSpam) "spam" else "inbox"
                                                }
                                                menu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (selected.trusted)
                                                            R.string.untrust_sender
                                                        else R.string.trust_sender
                                                    )
                                                )
                                            },
                                            onClick = {
                                                updatePriority(selected)
                                                menu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.delete_conversation))
                                            },
                                            onClick = {
                                                deleteTarget = selected
                                                menu = false
                                            },
                                        )
                                    }
                                }
                        },
                    )
                },
            ) { padding ->
                key(composeSession) {
                    ChatScreen(
                        controller,
                        address,
                        { address = it },
                        selected,
                        state.isDefault,
                        hasSend,
                        Modifier.padding(padding).consumeWindowInsets(padding),
                        initialBody,
                        onSpamChanged = { moveToSpam ->
                            backFromChat()
                            query = ""
                            if (!moveToSpam) filter = "inbox"
                            route = if (moveToSpam) "spam" else "inbox"
                        },
                        onDefault = { requestRole() },
                        onForward = {
                            composeSession++
                            initialBody = it
                            address = ""
                            selectedKey = null
                        },
                    )
                }
            }
        }
    }
}
